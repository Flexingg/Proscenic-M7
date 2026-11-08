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
        val errorMessage: String? = null,
        val discoveredRobotIp: String? = null
    )

    /**
     * Fast LAN + SoftAP UDP 7913 scanner (`PAIRING_LOG_ANALYSIS.md` §6 & `FIELD_NOTES.md`):
     * After leaving SoftAP mode and joining the home Wi-Fi, `network_proxy` keeps UDP port 7913 open
     * on the robot's LAN IP (`wlan0`). When the phone's DHCP IP changes on home Wi-Fi (e.g. `.110` -> `.165`),
     * we can find the robot directly on the `/24` LAN and re-point `setUrl` + `setID` without SoftAP mode!
     */
    suspend fun repointRobotOnLan(
        serverHostIp: String,
        httpPort: Int = 8080,
        gatewayPort: Int = 8081,
        wifiSsid: String = "",
        wifiPassword: String = "",
        userId: String = "local_user",
        knownRobotIp: String? = null,
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

        val cleanUrl = "http://${serverHostIp.trim()}:$httpPort/"
        val snCmd = """{"cmd":"getSn"}"""

        try {
            val discovered = discoverRobotUdp7913(serverHostIp.trim(), knownRobotIp)
                ?: return@withContext RehomeResult(
                    success = false,
                    discoveredPort = defaultPort,
                    robotSerialNumber = null,
                    steps = steps,
                    errorMessage = "Could not find robot on LAN UDP 7913 (scanned ${subnetPrefixOf(serverHostIp)}.1..254:7913). If the robot was rebooted since pairing, hold Power + Home for 5s to re-run SoftAP pairing."
                )

            val targetIp = discovered.first
            val reply = discovered.second
            val serialNumber = reply.optString("sn", "").trim()
            record(0, "0/4 Found Robot on LAN ($targetIp:$defaultPort, SN=$serialNumber)", snCmd, reply.toString(), true)

            // Step 1: setUrl (Channel-A HTTP base URL -> writes /data/bin/Run/Config/url)
            val setUrlHttp = """{"cmd":"setUrl","url":"$cleanUrl"}"""
            val r1 = requireOkRawAt(targetIp, defaultPort, setUrlHttp, "setUrl(url)")
            record(1, "1/4 Update Channel-A URL ($cleanUrl)", setUrlHttp, r1.toString(), true)

            // Step 2: setUrl (Channel-B Push Gateway -> writes /data/bin/Run/Config/ip_port.json)
            val setUrlGw = JSONObject()
                .put("cmd", "setUrl")
                .put("ip", serverHostIp.trim())
                .put("port", gatewayPort)
                .toString()
            val r2 = requireOkRawAt(targetIp, defaultPort, setUrlGw, "setUrl(ip,port)")
            record(2, "2/4 Update Channel-B Gateway (${serverHostIp.trim()}:$gatewayPort)", setUrlGw, r2.toString(), true)

            // Step 3: getCfg
            val getCfg = """{"cmd":"getCfg"}"""
            val r3 = sendUdpRawCommandTo(targetIp, defaultPort, getCfg) ?: JSONObject().put("result", "ok")
            record(3, "3/4 Verify Config (getCfg)", getCfg, r3.toString(), true)

            // Step 4: setID (Re-arms BindUser & triggers HTTP binding + TCP gateway reconnect)
            val setId = JSONObject()
                .put("cmd", "setID")
                .put("id", userId.ifBlank { "local_user" })
                .put("deviceSN", serialNumber)
                .toString()
            val r4 = requireOkRawAt(targetIp, defaultPort, setId, "setID")
            record(4, "4/4 Re-Arm Binding to ${serverHostIp.trim()} (setID)", setId, r4.toString(), true)

            // Optional Step 5 & 6: If saved Wi-Fi credentials are provided, also send setSta + applyCfg
            // so the robot reloads network config immediately if needed.
            if (wifiSsid.isNotBlank() && wifiPassword.length in 8..64 && !wifiSsid.startsWith("Proscenic-", ignoreCase = true)) {
                val setSta = JSONObject()
                    .put("cmd", "setSta")
                    .put("staName", wifiSsid.trim())
                    .put("staPwd", wifiPassword)
                    .toString()
                runCatching {
                    val r5 = requireOkRawAt(targetIp, defaultPort, setSta, "setSta")
                    record(5, "5/6 Confirm Home Wi-Fi ('${wifiSsid.trim()}')", setSta, r5.toString(), true)
                    val applyCfg = """{"cmd":"applyCfg"}"""
                    val r6 = sendUdpRawCommandTo(targetIp, defaultPort, applyCfg)
                    record(6, "6/6 Commit Config (applyCfg)", applyCfg, r6?.toString() ?: """{"result":"ok"}""", true)
                }
            }

            RehomeResult(
                success = true,
                discoveredPort = defaultPort,
                robotSerialNumber = serialNumber,
                steps = steps,
                discoveredRobotIp = targetIp
            )
        } catch (e: Exception) {
            RehomeResult(
                success = false,
                discoveredPort = defaultPort,
                robotSerialNumber = null,
                steps = steps,
                errorMessage = e.message ?: "LAN UDP 7913 re-point failed"
            )
        }
    }

    /**
     * Executes the 6-step re-homing sequence against the physical vacuum on SoftAP (or falls back to LAN UDP 7913).
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
                errorMessage = "Enter your home 2.4GHz Wi-Fi SSID (e.g. 'Robots'), or tap 'Reconnect Robot Already on Home Wi-Fi' above if it is already paired."
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
        var targetIp = robotIp
        var targetPort = defaultPort
        var serialNumber = ""

        try {
            // Step 0: Probe target robotIp:7913 first; if unreachable, also scan the /24 LAN in case robot is already on home Wi-Fi!
            val snCmd = """{"cmd":"getSn"}"""
            val directReply = sendUdpRawCommandTo(targetIp, targetPort, snCmd)
            val reply = if (directReply != null) {
                directReply
            } else {
                val lanFound = discoverRobotUdp7913(serverHostIp.trim(), null)
                if (lanFound != null) {
                    targetIp = lanFound.first
                    lanFound.second
                } else {
                    val swept = sweepPorts(targetIp, 9000..9100)
                        ?: return@withContext RehomeResult(
                            success = false,
                            discoveredPort = targetPort,
                            robotSerialNumber = null,
                            steps = steps,
                            errorMessage = "No reply from $robotIp:$targetPort (or on LAN ${subnetPrefixOf(serverHostIp)}.x:7913). Connect your phone's Wi-Fi to 'Proscenic-XXXX' (hold Power + Home 5s on robot) or tap 'Scan & Reconnect on Home Wi-Fi'."
                        )
                    targetPort = swept.first
                    swept.second
                }
            }
            serialNumber = reply.optString("sn", "").trim()
            record(0, "0/6 Probe $targetIp:$targetPort & Read Serial (getSn)", snCmd, reply.toString(), true)

            // Step 1: setUrl (Channel-A HTTP base URL -> writes /data/bin/Run/Config/url)
            // IMPORTANT: Do NOT escape '/' as '\/' — robot firmware reads raw URL string
            val setUrlHttp = """{"cmd":"setUrl","url":"$cleanUrl"}"""
            val r1 = requireOkRawAt(targetIp, targetPort, setUrlHttp, "setUrl(url)")
            record(1, "1/6 Set Channel-A Base URL ($cleanUrl)", setUrlHttp, r1.toString(), true)

            // Step 2: setUrl (Channel-B Push Gateway -> writes /data/bin/Run/Config/ip_port.json)
            val setUrlGw = JSONObject()
                .put("cmd", "setUrl")
                .put("ip", serverHostIp.trim())
                .put("port", gatewayPort)
                .toString()
            val r2 = requireOkRawAt(targetIp, targetPort, setUrlGw, "setUrl(ip,port)")
            record(2, "2/6 Set Channel-B Push Gateway (${serverHostIp.trim()}:$gatewayPort)", setUrlGw, r2.toString(), true)

            // Step 3: getCfg (Verify config state)
            val getCfg = """{"cmd":"getCfg"}"""
            val r3 = sendUdpRawCommandTo(targetIp, targetPort, getCfg) ?: JSONObject().put("result", "ok")
            record(3, "3/6 Read Current Robot Config (getCfg)", getCfg, r3.toString(), true)

            // Step 4: setID (Arm BindUser thread with userId + deviceSN)
            val setId = JSONObject()
                .put("cmd", "setID")
                .put("id", userId)
                .put("deviceSN", serialNumber)
                .toString()
            val r4 = requireOkRawAt(targetIp, targetPort, setId, "setID")
            record(4, "4/6 Arm Device Binding (setID)", setId, r4.toString(), true)

            // Step 5: setSta (Store home Wi-Fi credentials in memory)
            val setSta = JSONObject()
                .put("cmd", "setSta")
                .put("staName", wifiSsid.trim())
                .put("staPwd", wifiPassword)
                .toString()
            val r5 = requireOkRawAt(targetIp, targetPort, setSta, "setSta")
            record(5, "5/6 Store Home Wi-Fi ('${wifiSsid.trim()}')", setSta, r5.toString(), true)

            // Step 6: applyCfg (Commit Wi-Fi config; robot tears down AP and joins home LAN)
            val applyCfg = """{"cmd":"applyCfg"}"""
            val r6 = sendUdpRawCommandTo(targetIp, targetPort, applyCfg)
            val rxText = r6?.toString() ?: """{"note":"SoftAP closed as robot switched to home Wi-Fi (expected)"}"""
            record(6, "6/6 Commit & Join Home Wi-Fi (applyCfg)", applyCfg, rxText, true)

            RehomeResult(
                success = true,
                discoveredPort = targetPort,
                robotSerialNumber = serialNumber,
                steps = steps,
                discoveredRobotIp = targetIp
            )
        } catch (e: Exception) {
            RehomeResult(
                success = false,
                discoveredPort = targetPort,
                robotSerialNumber = serialNumber.ifBlank { null },
                steps = steps,
                errorMessage = e.message ?: "UDP provisioning failed",
                discoveredRobotIp = targetIp
            )
        }
    }

    private fun subnetPrefixOf(ip: String): String {
        val parts = ip.trim().split('.')
        return if (parts.size == 4) "${parts[0]}.${parts[1]}.${parts[2]}" else "192.168.1"
    }

    /**
     * Broadcasts + sweeps the `/24` LAN subnet on UDP port 7913 for `{"cmd":"getSn"}`.
     */
    fun discoverRobotUdp7913(serverHostIp: String, knownRobotIp: String? = null): Pair<String, JSONObject>? {
        val probeBytes = """{"cmd":"getSn"}""".toByteArray(Charsets.UTF_8)

        // 1. Try knownRobotIp or SoftAP 192.168.78.1 first if provided
        if (!knownRobotIp.isNullOrBlank()) {
            sendUdpRawCommandTo(knownRobotIp.substringBefore(':'), defaultPort, """{"cmd":"getSn"}""", 400)?.let {
                if (it.optString("sn").isNotBlank()) return knownRobotIp.substringBefore(':') to it
            }
        }

        val prefix = subnetPrefixOf(serverHostIp)
        DatagramSocket().use { sock ->
            sock.broadcast = true
            sock.soTimeout = 350

            // Send to subnet broadcast, global broadcast, and SoftAP IP first
            val priorityTargets = listOf("$prefix.255", "255.255.255.255", DEFAULT_AP_IP)
            for (target in priorityTargets) {
                runCatching {
                    val addr = InetAddress.getByName(target)
                    sock.send(DatagramPacket(probeBytes, probeBytes.size, addr, defaultPort))
                }
            }

            // Sweep all 254 hosts in the /24 subnet in two fast batches
            for (host in 1..254) {
                val ip = "$prefix.$host"
                if (ip == serverHostIp) continue
                runCatching {
                    val addr = InetAddress.getByName(ip)
                    sock.send(DatagramPacket(probeBytes, probeBytes.size, addr, defaultPort))
                }
            }

            val deadline = System.currentTimeMillis() + 900L
            val buf = ByteArray(4096)
            while (System.currentTimeMillis() < deadline) {
                try {
                    val pkt = DatagramPacket(buf, buf.size)
                    sock.receive(pkt)
                    val text = String(pkt.data, 0, pkt.length, Charsets.UTF_8).trim()
                    val json = runCatching { JSONObject(text) }.getOrNull() ?: continue
                    if (json.optString("result") == "ok" && json.optString("sn").isNotBlank()) {
                        val senderIp = pkt.address?.hostAddress ?: continue
                        return senderIp to json
                    }
                } catch (_: SocketTimeoutException) {
                    break
                } catch (_: Exception) {
                    break
                }
            }
        }
        return null
    }

    private fun requireOkRawAt(targetIp: String, port: Int, rawJson: String, label: String): JSONObject {
        val reply = sendUdpRawCommandTo(targetIp, port, rawJson)
            ?: throw IllegalStateException("No UDP reply for $label on $targetIp:$port")
        if (reply.optString("result") != "ok") {
            throw IllegalStateException("Robot rejected $label: $reply")
        }
        return reply
    }

    private fun sendUdpRawCommandTo(targetIp: String, port: Int, rawJson: String, customTimeoutMs: Int = timeoutMs): JSONObject? {
        val bytes = rawJson.toByteArray(Charsets.UTF_8)
        val addr = runCatching { InetAddress.getByName(targetIp) }.getOrNull() ?: return null
        DatagramSocket().use { sock ->
            sock.soTimeout = customTimeoutMs
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
                } catch (_: Exception) {
                    return null
                }
            }
        }
        return null
    }

    private fun sweepPorts(targetIp: String, range: IntRange): Pair<Int, JSONObject>? {
        val probe = JSONObject().put("cmd", "getSn").toString().toByteArray(Charsets.UTF_8)
        val addr = runCatching { InetAddress.getByName(targetIp) }.getOrNull() ?: return null
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
