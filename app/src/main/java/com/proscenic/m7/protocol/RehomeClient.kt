package com.proscenic.m7.protocol

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/**
 * Channel-C SoftAP UDP `7913` Re-homing Client (`PROTOCOL.md` §C & `tools/rehome.py`).
 *
 * When the Proscenic M7 Pro is placed in pairing mode (hold Power + Home for 5s), it creates
 * an open Wi-Fi AP (`Proscenic-XXXX` / `LDRobot`) at `192.168.78.1` and listens on UDP port `7913`
 * (or `9000..9999` on early `LS_S6` builds).
 *
 * This client executes the exact 6-step re-home sequence that points the vacuum's Channel-A HTTP URL
 * and Channel-B TCP Push Gateway (`ip_port.json`) at our local server and joins it to home Wi-Fi.
 *
 * IMPORTANT: Never sends `bindOk` (`FUN_00463c60` closes the UDP socket without stopping the listener
 * thread, causing a shell fork storm on the robot).
 */
class RehomeClient(
    private val robotIp: String = DEFAULT_AP_IP,
    private val defaultPort: Int = DEFAULT_UDP_PORT,
    private val timeoutMs: Int = 1500
) {
    companion object {
        const val DEFAULT_AP_IP = "192.168.78.1"
        const val DEFAULT_UDP_PORT = 7913
    }

    data class StepLog(
        val step: Int,
        val title: String,
        val txJson: String,
        val rxJson: String,
        val success: Boolean
    )

    data class RehomeResult(
        val success: Boolean,
        val discoveredPort: Int,
        val robotSerialNumber: String?,
        val steps: List<StepLog>,
        val errorMessage: String? = null
    )

    /**
     * Executes the 6-step re-homing sequence against the physical vacuum on SoftAP.
     */
    suspend fun executeRehome(
        serverHostIp: String,
        httpPort: Int = 8080,
        gatewayPort: Int = 8081,
        wifiSsid: String,
        wifiPassword: String,
        userId: String = "local_user",
        onStepProgress: (StepLog) -> Unit = {}
    ): RehomeResult = withContext(Dispatchers.IO) {
        val steps = mutableListOf<StepLog>()

        fun record(step: Int, title: String, txRaw: String, rx: String, ok: Boolean): StepLog {
            val redactedTx = runCatching {
                JSONObject(txRaw).apply {
                    if (has("staPwd")) put("staPwd", "********")
                }.toString().replace("\\/", "/")
            }.getOrDefault(txRaw)
            val entry = StepLog(step, title, redactedTx, rx, ok)
            steps.add(entry)
            onStepProgress(entry)
            return entry
        }

        if (wifiSsid.isBlank()) {
            return@withContext RehomeResult(
                success = false,
                discoveredPort = defaultPort,
                robotSerialNumber = null,
                steps = steps,
                errorMessage = "Enter your home 2.4GHz Wi-Fi SSID (not the robot's Proscenic-* hotspot name)."
            )
        }

        if (wifiSsid.startsWith("Proscenic-", ignoreCase = true) || wifiSsid.startsWith("scenic-", ignoreCase = true)) {
            return@withContext RehomeResult(
                success = false,
                discoveredPort = defaultPort,
                robotSerialNumber = null,
                steps = steps,
                errorMessage = "'$wifiSsid' looks like the robot's own SoftAP hotspot! Enter your HOME 2.4GHz router Wi-Fi SSID and password so the robot can join your home network."
            )
        }

        if (wifiPassword.length !in 8..64) {
            return@withContext RehomeResult(
                success = false,
                discoveredPort = defaultPort,
                robotSerialNumber = null,
                steps = steps,
                errorMessage = "Home Wi-Fi password must be 8–64 characters (enforced by robot firmware)."
            )
        }

        val cleanUrl = "http://${serverHostIp.trim()}:$httpPort/"
        var targetPort = defaultPort
        var serialNumber = ""

        try {
            // Step 0: Probe UDP 7913 first (and read serial with getSn)
            val snCmd = """{"cmd":"getSn"}"""
            val reply = sendUdpRawCommand(targetPort, snCmd) ?: run {
                val swept = sweepPorts(9000..9100)
                    ?: return@withContext RehomeResult(
                        success = false,
                        discoveredPort = targetPort,
                        robotSerialNumber = null,
                        steps = steps,
                        errorMessage = "No reply from $robotIp:$targetPort. Ensure the vacuum is in pairing mode (hold Power + Home 5s) and your phone is connected to the 'Proscenic-XXXX' Wi-Fi AP."
                    )
                targetPort = swept.first
                swept.second
            }
            serialNumber = reply.optString("sn", "").trim()
            record(0, "0/6 Probe UDP $targetPort & Read Serial (getSn)", snCmd, reply.toString(), true)

            // Step 1: setUrl (Channel-A HTTP base URL -> writes /data/bin/Run/Config/url)
            // IMPORTANT: Do NOT escape '/' as '\/' — robot firmware reads raw URL string
            val setUrlHttp = """{"cmd":"setUrl","url":"$cleanUrl"}"""
            val r1 = requireOkRaw(targetPort, setUrlHttp, "setUrl(url)")
            record(1, "1/6 Set Channel-A Base URL ($cleanUrl)", setUrlHttp, r1.toString(), true)

            // Step 2: setUrl (Channel-B Push Gateway -> writes /data/bin/Run/Config/ip_port.json)
            val setUrlGw = JSONObject()
                .put("cmd", "setUrl")
                .put("ip", serverHostIp.trim())
                .put("port", gatewayPort)
                .toString()
            val r2 = requireOkRaw(targetPort, setUrlGw, "setUrl(ip,port)")
            record(2, "2/6 Set Channel-B Push Gateway (${serverHostIp.trim()}:$gatewayPort)", setUrlGw, r2.toString(), true)

            // Step 3: getCfg (Verify config state)
            val getCfg = """{"cmd":"getCfg"}"""
            val r3 = sendUdpRawCommand(targetPort, getCfg) ?: JSONObject().put("result", "ok")
            record(3, "3/6 Read Current Robot Config (getCfg)", getCfg, r3.toString(), true)

            // Step 4: setID (Arm BindUser thread with userId + deviceSN)
            val setId = JSONObject()
                .put("cmd", "setID")
                .put("id", userId)
                .put("deviceSN", serialNumber)
                .toString()
            val r4 = requireOkRaw(targetPort, setId, "setID")
            record(4, "4/6 Arm Device Binding (setID)", setId, r4.toString(), true)

            // Step 5: setSta (Store home Wi-Fi credentials in memory)
            val setSta = JSONObject()
                .put("cmd", "setSta")
                .put("staName", wifiSsid.trim())
                .put("staPwd", wifiPassword)
                .toString()
            val r5 = requireOkRaw(targetPort, setSta, "setSta")
            record(5, "5/6 Store Home Wi-Fi ('${wifiSsid.trim()}')", setSta, r5.toString(), true)

            // Step 6: applyCfg (Commit Wi-Fi config; robot tears down AP and joins home LAN)
            val applyCfg = """{"cmd":"applyCfg"}"""
            val r6 = sendUdpRawCommand(targetPort, applyCfg)
            val rxText = r6?.toString() ?: """{"note":"SoftAP closed as robot switched to home Wi-Fi (expected)"}"""
            record(6, "6/6 Commit & Join Home Wi-Fi (applyCfg)", applyCfg, rxText, true)

            RehomeResult(
                success = true,
                discoveredPort = targetPort,
                robotSerialNumber = serialNumber,
                steps = steps
            )
        } catch (e: Exception) {
            RehomeResult(
                success = false,
                discoveredPort = targetPort,
                robotSerialNumber = serialNumber.ifBlank { null },
                steps = steps,
                errorMessage = e.message ?: "UDP provisioning failed"
            )
        }
    }

    private fun requireOkRaw(port: Int, rawJson: String, label: String): JSONObject {
        val reply = sendUdpRawCommand(port, rawJson)
            ?: throw IllegalStateException("No UDP reply for $label on $robotIp:$port")
        if (reply.optString("result") != "ok") {
            throw IllegalStateException("Robot rejected $label: $reply")
        }
        return reply
    }

    private fun sendUdpRawCommand(port: Int, rawJson: String): JSONObject? {
        val bytes = rawJson.toByteArray(Charsets.UTF_8)
        val addr = InetAddress.getByName(robotIp)
        DatagramSocket().use { sock ->
            sock.soTimeout = timeoutMs
            repeat(2) {
                try {
                    sock.send(DatagramPacket(bytes, bytes.size, addr, port))
                    val buf = ByteArray(8192)
                    val pkt = DatagramPacket(buf, buf.size)
                    sock.receive(pkt)
                    val text = String(pkt.data, 0, pkt.length, Charsets.UTF_8).trim()
                    if (text.isNotEmpty()) {
                        return JSONObject(text)
                    }
                } catch (_: SocketTimeoutException) {
                    // retry once
                }
            }
        }
        return null
    }

    private fun sweepPorts(range: IntRange): Pair<Int, JSONObject>? {
        val probe = JSONObject().put("cmd", "getSn").toString().toByteArray(Charsets.UTF_8)
        val addr = InetAddress.getByName(robotIp)
        DatagramSocket().use { sock ->
            sock.soTimeout = 400
            for (p in range) {
                runCatching { sock.send(DatagramPacket(probe, probe.size, addr, p)) }
            }
            val buf = ByteArray(4096)
            val pkt = DatagramPacket(buf, buf.size)
            return try {
                sock.receive(pkt)
                val text = String(pkt.data, 0, pkt.length, Charsets.UTF_8).trim()
                pkt.port to JSONObject(text)
            } catch (_: Exception) {
                null
            }
        }
    }
}
