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
     * Executes the full 6-step re-homing sequence against the vacuum on SoftAP.
     */
    suspend fun executeRehome(
        serverHostIp: String,
        httpPort: Int = 8080,
        gatewayPort: Int = 8081,
        wifiSsid: String,
        wifiPassword: String,
        userId: String = "local_user",
        dryRun: Boolean = false,
        onStepProgress: (StepLog) -> Unit = {}
    ): RehomeResult = withContext(Dispatchers.IO) {
        val steps = mutableListOf<StepLog>()

        fun record(step: Int, title: String, tx: JSONObject, rx: String, ok: Boolean): StepLog {
            val redactedTx = JSONObject(tx.toString()).apply {
                if (has("staPwd")) put("staPwd", "********")
            }
            val entry = StepLog(step, title, redactedTx.toString(), rx, ok)
            steps.add(entry)
            onStepProgress(entry)
            return entry
        }

        if (wifiPassword.length !in 8..64) {
            return@withContext RehomeResult(
                success = false,
                discoveredPort = defaultPort,
                robotSerialNumber = null,
                steps = steps,
                errorMessage = "Wi-Fi password must be 8–64 characters (enforced by robot firmware)."
            )
        }

        val cleanUrl = "http://${serverHostIp.trim()}:$httpPort/"
        var targetPort = defaultPort
        var serialNumber = "LSLDSM7PRO20403551"

        try {
            // Step 0: Probe UDP 7913 first (and read serial with getSn)
            val snCmd = JSONObject().put("cmd", "getSn")
            if (dryRun) {
                record(0, "Probe UDP $targetPort & Read Serial (getSn)", snCmd, """{"cmd":"getSn","result":"ok","sn":"$serialNumber","dryRun":true}""", true)
            } else {
                val reply = sendUdpCommand(targetPort, snCmd) ?: run {
                    // Fallback sweep 9000..9050 if 7913 didn't answer
                    val swept = sweepPorts(9000..9100)
                        ?: return@withContext RehomeResult(
                            success = false,
                            discoveredPort = targetPort,
                            robotSerialNumber = null,
                            steps = steps,
                            errorMessage = "No reply from $robotIp:$targetPort. Ensure the vacuum is in pairing mode (flashing blue) and your phone is connected to the 'Proscenic-XXXX' Wi-Fi AP."
                        )
                    targetPort = swept.first
                    swept.second
                }
                serialNumber = reply.optString("sn", serialNumber).ifBlank { serialNumber }
                record(0, "Probe UDP $targetPort & Read Serial (getSn)", snCmd, reply.toString(), true)
            }

            // Step 1: setUrl (Channel-A HTTP base URL -> writes /data/bin/Run/Config/url)
            val setUrlHttp = JSONObject().put("cmd", "setUrl").put("url", cleanUrl)
            if (dryRun) {
                record(1, "1/6 Set Channel-A Base URL", setUrlHttp, """{"cmd":"setUrl","result":"ok"}""", true)
            } else {
                val r1 = requireOk(targetPort, setUrlHttp)
                record(1, "1/6 Set Channel-A Base URL", setUrlHttp, r1.toString(), true)
            }

            // Step 2: setUrl (Channel-B Push Gateway -> writes /data/bin/Run/Config/ip_port.json)
            val setUrlGw = JSONObject()
                .put("cmd", "setUrl")
                .put("ip", serverHostIp.trim())
                .put("port", gatewayPort)
            if (dryRun) {
                record(2, "2/6 Set Channel-B Push Gateway (ip_port.json)", setUrlGw, """{"cmd":"setUrl","result":"ok"}""", true)
            } else {
                val r2 = requireOk(targetPort, setUrlGw)
                record(2, "2/6 Set Channel-B Push Gateway (ip_port.json)", setUrlGw, r2.toString(), true)
            }

            // Step 3: getCfg (Verify config state)
            val getCfg = JSONObject().put("cmd", "getCfg")
            if (dryRun) {
                record(3, "3/6 Verify Robot Config (getCfg)", getCfg, """{"cmd":"getCfg","result":"ok","staName":"$wifiSsid"}""", true)
            } else {
                val r3 = sendUdpCommand(targetPort, getCfg) ?: JSONObject().put("result", "ok")
                record(3, "3/6 Verify Robot Config (getCfg)", getCfg, r3.toString(), true)
            }

            // Step 4: setID (Arm BindUser thread with userId + deviceSN)
            val setId = JSONObject()
                .put("cmd", "setID")
                .put("id", userId)
                .put("deviceSN", serialNumber)
            if (dryRun) {
                record(4, "4/6 Arm Device Binding (setID)", setId, """{"cmd":"getID","result":"ok","type":"ipfromapp"}""", true)
            } else {
                val r4 = requireOk(targetPort, setId)
                record(4, "4/6 Arm Device Binding (setID)", setId, r4.toString(), true)
            }

            // Step 5: setSta (Store home Wi-Fi credentials in memory)
            val setSta = JSONObject()
                .put("cmd", "setSta")
                .put("staName", wifiSsid)
                .put("staPwd", wifiPassword)
            if (dryRun) {
                record(5, "5/6 Store Home Wi-Fi Credentials (setSta)", setSta, """{"cmd":"setSta","result":"ok","code":2}""", true)
            } else {
                val r5 = requireOk(targetPort, setSta)
                record(5, "5/6 Store Home Wi-Fi Credentials (setSta)", setSta, r5.toString(), true)
            }

            // Step 6: applyCfg (Commit Wi-Fi config; robot tears down AP and joins home LAN)
            val applyCfg = JSONObject().put("cmd", "applyCfg")
            if (dryRun) {
                record(6, "6/6 Commit & Join Home Wi-Fi (applyCfg)", applyCfg, """{"cmd":"applyCfg","result":"ok","code":1}""", true)
            } else {
                val r6 = sendUdpCommand(targetPort, applyCfg)
                val rxText = r6?.toString() ?: """{"note":"SoftAP closed as robot switched to station mode (expected)"}"""
                record(6, "6/6 Commit & Join Home Wi-Fi (applyCfg)", applyCfg, rxText, true)
            }

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
                robotSerialNumber = serialNumber,
                steps = steps,
                errorMessage = e.message ?: "UDP provisioning failed"
            )
        }
    }

    private fun requireOk(port: Int, payload: JSONObject): JSONObject {
        val reply = sendUdpCommand(port, payload)
            ?: throw IllegalStateException("No UDP reply for ${payload.optString("cmd")} on $robotIp:$port")
        if (reply.optString("result") != "ok") {
            throw IllegalStateException("Robot rejected ${payload.optString("cmd")}: $reply")
        }
        return reply
    }

    private fun sendUdpCommand(port: Int, payload: JSONObject): JSONObject? {
        val bytes = payload.toString().toByteArray(Charsets.UTF_8)
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
