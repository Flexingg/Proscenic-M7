package com.proscenic.m7.server

import com.proscenic.m7.protocol.CleanPath
import com.proscenic.m7.protocol.ConsumablesStatus
import com.proscenic.m7.protocol.MapRegion
import com.proscenic.m7.protocol.ProscenicProtocol
import com.proscenic.m7.protocol.QuietHoursConfig
import com.proscenic.m7.protocol.ScheduleEntry
import com.proscenic.m7.protocol.VacuumMap
import com.proscenic.m7.protocol.VacuumStatus
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/**
 * Embedded Local Replacement Cloud & Push Gateway Server for the Proscenic M7 Pro.
 *
 * Implements both:
 * 1. **Channel A (HTTP Server, default `:8080`)**:
 *    - `POST /cleanPack/binding`
 *    - `POST /cleanPack/register`
 *    - `GET  /cleanPack/getSockAddr`
 *    - `POST /cleanPack/sync`
 *    - `POST /cleanPack/uploadEvents` (receives `20001` status & `20002` LZ4 2D floor maps)
 *    - `POST /cleanPack/response` (receives `21011` path, `21002` schedules, `21004` zones, command ACKs)
 *    - `POST /cleanPack/uploadSingle` (clean history & backup maps)
 *    - **Home Assistant Local Bridge REST API**:
 *      - `GET  /api/status` -> JSON status for HA REST sensor / vacuum template
 *      - `GET  /api/map.json` -> Decoded map metadata, rooms, zones, and path
 *      - `POST /api/command` -> Trigger `start`, `deep_clean`, `pause`, `resume`, `stop`, `return_to_base`, `locate`, `collect_dust`, `set_fan_speed`, `clean_zones`, `clean_rooms`
 * 2. **Channel B (Raw TCP Push Gateway, default `:8081`)**:
 *    - Reads `#\t#`-delimited JSON frames from the vacuum (`10001` handshake, `21006` heartbeat ping)
 *    - Immediately replies to every `21006` ping with `{"encrypt":0,"data":{"infoType":21006,"data":{"isExistConnect":true}}}#\t#`
 *    - Pushes queued `encrypt:0` control commands down the active robot socket.
 */
class LocalVacuumServer(
    val httpPort: Int = 8080,
    val gatewayPort: Int = 8081,
    private val onStatusReceived: (VacuumStatus) -> Unit = {},
    private val onMapReceived: (VacuumMap) -> Unit = {},
    private val onPathReceived: (CleanPath) -> Unit = {},
    private val onZonesReceived: (List<MapRegion>) -> Unit = {},
    private val onSchedulesReceived: (List<ScheduleEntry>) -> Unit = {},
    private val onCommandAck: (infoType: Int, message: String, rawJson: String) -> Unit = { _, _, _ -> },
    private val onWireTrace: (WireTraceEntry) -> Unit = {},
    private val onExternalHaCommand: (String, JSONObject) -> Unit = { _, _ -> }
) {
    data class WireTraceEntry(
        val timestampMs: Long = System.currentTimeMillis(),
        val channel: String,   // "A-HTTP" or "B-TCP"
        val direction: String, // "RX" or "TX"
        val summary: String,
        val payload: String
    )

    // Any uncaught exception in a socket/handler coroutine would otherwise crash the whole app.
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, _ -> }
    )
    private var httpJob: Job? = null
    private var gatewayJob: Job? = null
    private var httpServerSocket: ServerSocket? = null
    private var gatewayServerSocket: ServerSocket? = null

    private val activeRobotSocket = AtomicReference<Socket?>(null)
    private val activeRobotOutput = AtomicReference<OutputStream?>(null)
    private val outboundQueue = CopyOnWriteArrayList<String>()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _isRobotConnected = MutableStateFlow(false)
    val isRobotConnected: StateFlow<Boolean> = _isRobotConnected.asStateFlow()

    private val _connectedRobotAddress = MutableStateFlow<String?>(null)
    val connectedRobotAddress: StateFlow<String?> = _connectedRobotAddress.asStateFlow()

    private val _connectedRobotSn = MutableStateFlow<String?>(null)
    val connectedRobotSn: StateFlow<String?> = _connectedRobotSn.asStateFlow()

    private val _heartbeatCount = MutableStateFlow(0)
    val heartbeatCount: StateFlow<Int> = _heartbeatCount.asStateFlow()

    @Volatile
    var latestStatusSnapshot: VacuumStatus = VacuumStatus(sn = "", mode = "offline", battery = 0, isOnline = false)

    @Volatile
    var latestMapSnapshot: VacuumMap? = null

    @Volatile
    var latestPathSnapshot: CleanPath = CleanPath()

    @Volatile
    var latestConsumables: ConsumablesStatus = ConsumablesStatus()

    fun start() {
        if (_isRunning.value) return
        _isRunning.value = true

        httpJob = scope.launch {
            runHttpServer()
        }
        gatewayJob = scope.launch {
            runGatewayServer()
        }
        trace("SYSTEM", "INFO", "Listening on HTTP :$httpPort & TCP Gateway :$gatewayPort (waiting for robot)", "")
    }

    fun stop() {
        _isRunning.value = false
        _isRobotConnected.value = false
        _connectedRobotAddress.value = null
        runCatching { activeRobotSocket.getAndSet(null)?.close() }
        runCatching { httpServerSocket?.close() }
        runCatching { gatewayServerSocket?.close() }
        httpJob?.cancel()
        gatewayJob?.cancel()
    }

    /**
     * Sends a `#\t#`-framed command to the connected robot on Channel B using `Dispatchers.IO`
     * (avoiding `NetworkOnMainThreadException` when called from Android UI button clicks).
     */
    fun sendFrameToRobot(frame: String): Boolean {
        val isConnectedNow = activeRobotOutput.get() != null && _isRobotConnected.value
        scope.launch(Dispatchers.IO) {
            writeFrameInternal(frame)
        }
        return isConnectedNow
    }

    fun sendFramesToRobot(frames: List<String>) {
        scope.launch(Dispatchers.IO) {
            for (frame in frames) {
                writeFrameInternal(frame)
            }
        }
    }

    private fun writeFrameInternal(frame: String): Boolean {
        val out = activeRobotOutput.get()
        if (out != null) {
            return try {
                synchronized(out) {
                    out.write(frame.toByteArray(Charsets.UTF_8))
                    out.flush()
                }
                trace("B-TCP", "TX", "Sent command frame", frame.removeSuffix(ProscenicProtocol.FRAME_DELIMITER))
                true
            } catch (e: Exception) {
                outboundQueue.add(frame)
                trace("B-TCP", "ERR", "Socket write error (${e.javaClass.simpleName}), queued", frame.removeSuffix(ProscenicProtocol.FRAME_DELIMITER))
                false
            }
        } else {
            outboundQueue.add(frame)
            trace("B-TCP", "QUEUE", "Queued command (waiting for robot TCP connection)", frame.removeSuffix(ProscenicProtocol.FRAME_DELIMITER))
            return false
        }
    }

    // =========================================================================
    // Channel B: Raw TCP Push Gateway (Port 8081)
    // =========================================================================

    private suspend fun runGatewayServer() {
        try {
            val server = ServerSocket(gatewayPort)
            gatewayServerSocket = server
            while (scope.isActive && _isRunning.value) {
                val client = server.accept()
                scope.launch {
                    runCatching { handleGatewayClient(client) }
                    runCatching { client.close() }
                }
            }
        } catch (_: Exception) {
            // Stopped or port unavailable
        }
    }

    private fun handleGatewayClient(socket: Socket) {
        socket.keepAlive = true
        socket.tcpNoDelay = true
        activeRobotSocket.getAndSet(socket)?.let { old ->
            if (old !== socket) runCatching { old.close() }
        }
        val out = socket.getOutputStream()
        activeRobotOutput.set(out)
        val remoteAddr = "${socket.inetAddress.hostAddress}:${socket.port}"
        _connectedRobotAddress.value = remoteAddr
        _isRobotConnected.value = true
        trace("B-TCP", "RX", "Robot TCP connected from $remoteAddr", "")

        try {
            val input = socket.getInputStream()
            val buffer = ByteArray(16384)
            val accumulator = StringBuilder()

            while (_isRunning.value && !socket.isClosed) {
                val read = input.read(buffer)
                if (read < 0) break
                accumulator.append(String(buffer, 0, read, Charsets.UTF_8))

                while (true) {
                    val delimIndex = accumulator.indexOf(ProscenicProtocol.FRAME_DELIMITER)
                    if (delimIndex < 0) break
                    val frameJson = accumulator.substring(0, delimIndex).trim()
                    accumulator.delete(0, delimIndex + ProscenicProtocol.FRAME_DELIMITER.length)
                    if (frameJson.isNotEmpty()) {
                        handleInboundChannelBFrame(frameJson, out)
                    }
                }
            }
        } catch (_: Exception) {
            // Disconnected
        } finally {
            if (activeRobotSocket.compareAndSet(socket, null)) {
                activeRobotOutput.set(null)
                _isRobotConnected.value = false
                _connectedRobotAddress.value = null
                trace("B-TCP", "INFO", "Robot TCP disconnected ($remoteAddr)", "")
            }
            runCatching { socket.close() }
        }
    }

    private fun handleInboundChannelBFrame(rawJson: String, out: OutputStream) {
        val obj = runCatching { JSONObject(rawJson) }.getOrNull() ?: return
        val infoType = obj.optInt("infoType", -1)

        when (infoType) {
            10001 -> {
                val data = obj.optJSONObject("data")
                val sn = data?.optString("sn").orEmpty()
                trace("B-TCP", "RX", "Handshake 10001 (sn=$sn)", rawJson)
                if (sn.isNotBlank()) {
                    _connectedRobotSn.value = sn
                    latestStatusSnapshot = latestStatusSnapshot.copy(sn = sn, isOnline = true)
                    onStatusReceived(latestStatusSnapshot)
                }
                // Flush any queued commands + request real status, map, zones, and schedules from robot
                drainOutboundQueue(out)
                writeFrameInternal(ProscenicProtocol.buildRequestStatus())
                writeFrameInternal(ProscenicProtocol.buildRequestMapUploadNow())
                writeFrameInternal(ProscenicProtocol.buildGetAreaTactics())
                writeFrameInternal(ProscenicProtocol.buildGetSchedules())
            }
            21006 -> {
                // Keepalive Ping -> MUST reply immediately with enveloped 21006 + isExistConnect:true
                _heartbeatCount.value = _heartbeatCount.value + 1
                val pong = ProscenicProtocol.buildKeepalivePong(announceAppOnline = true)
                synchronized(out) {
                    out.write(pong.toByteArray(Charsets.UTF_8))
                    out.flush()
                }
                trace("B-TCP", "RX", "Heartbeat 21006 (#${_heartbeatCount.value}) -> replied isExistConnect:true", rawJson)
                drainOutboundQueue(out)
            }
            else -> {
                trace("B-TCP", "RX", "Channel-B infoType $infoType", rawJson)
                dispatchRobotInfoPayload(infoType, obj.optJSONObject("data") ?: obj)
            }
        }
    }

    private fun drainOutboundQueue(out: OutputStream) {
        while (outboundQueue.isNotEmpty()) {
            val frame = outboundQueue.removeAt(0)
            synchronized(out) {
                out.write(frame.toByteArray(Charsets.UTF_8))
                out.flush()
            }
            trace("B-TCP", "TX", "Flushed queued command", frame.removeSuffix(ProscenicProtocol.FRAME_DELIMITER))
        }
    }

    // =========================================================================
    // Channel A: HTTP Server + Home Assistant Local Bridge (Port 8080)
    // =========================================================================

    private suspend fun runHttpServer() {
        try {
            val server = ServerSocket(httpPort)
            httpServerSocket = server
            while (scope.isActive && _isRunning.value) {
                val client = server.accept()
                scope.launch {
                    runCatching { handleHttpClient(client) }
                    runCatching { client.close() }
                }
            }
        } catch (_: Exception) {
            // Stopped
        }
    }

    private fun handleHttpClient(socket: Socket) {
        socket.use { sock ->
            sock.soTimeout = 5000
            val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8))
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0].uppercase()
            val fullPath = parts[1]
            val path = fullPath.substringBefore('?')

            var contentLength = 0
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                if (line.startsWith("Content-Length:", ignoreCase = true)) {
                    contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
                }
            }

            val bodyChars = CharArray(contentLength.coerceIn(0, 8 * 1024 * 1024))
            var totalRead = 0
            while (totalRead < bodyChars.size) {
                val r = reader.read(bodyChars, totalRead, bodyChars.size - totalRead)
                if (r <= 0) break
                totalRead += r
            }
            val body = String(bodyChars, 0, totalRead)

            val responseJson = routeHttpRequest(method, path, body, sock)
            val responseBytes = responseJson.toByteArray(Charsets.UTF_8)
            val out = sock.getOutputStream()
            val headers = buildString {
                append("HTTP/1.1 200 OK\r\n")
                append("Content-Type: application/json; charset=utf-8\r\n")
                append("Access-Control-Allow-Origin: *\r\n")
                append("Content-Length: ${responseBytes.size}\r\n")
                append("Connection: close\r\n\r\n")
            }
            out.write(headers.toByteArray(Charsets.UTF_8))
            out.write(responseBytes)
            out.flush()
        }
    }

    private fun routeHttpRequest(method: String, path: String, body: String, sock: Socket): String {
        return when {
            path.endsWith("/cleanPack/register") -> {
                trace("A-HTTP", "RX", "POST /cleanPack/register", body.take(200))
                JSONObject().apply {
                    put("code", 0)
                    put("message", "ok")
                    put("data", JSONObject().apply {
                        put("bindStatus", 1)
                        put("session", "0123456789abcdef") // 16-byte AES key
                        put("cookies", "local_sid_0123456789abcdef012345")
                    })
                }.toString()
            }

            path.endsWith("/cleanPack/binding") || path.endsWith("/cleanPack/unbinding") -> {
                trace("A-HTTP", "RX", "POST $path", body.take(200))
                JSONObject().put("code", 0).put("message", "ok").put("data", JSONObject()).toString()
            }

            path.endsWith("/cleanPack/getSockAddr") -> {
                val localIp = sock.localAddress?.hostAddress?.takeIf { it != "0.0.0.0" } ?: getLocalLanIpAddress()
                trace("A-HTTP", "RX", "GET /cleanPack/getSockAddr -> $localIp:$gatewayPort", "")
                JSONObject().apply {
                    put("code", 0)
                    put("message", "success")
                    put("data", JSONObject().apply {
                        put("addr_list", JSONArray().put(JSONObject().apply {
                            put("ip", localIp)
                            put("port", gatewayPort)
                        }))
                    })
                }.toString()
            }

            path.endsWith("/cleanPack/sync") || path.endsWith("/cleanPack/uploadSingle") || path.endsWith("/cleanPack/uploadLogs") -> {
                trace("A-HTTP", "RX", "$method $path", body.take(200))
                JSONObject().put("code", 0).put("message", "ok").put("data", JSONObject()).toString()
            }

            path.endsWith("/cleanPack/uploadEvents") || path.endsWith("/cleanPack/response") -> {
                handleRobotUploadOrResponse(path, body)
                JSONObject().put("code", 0).put("message", "ok").put("data", JSONObject()).toString()
            }

            // =================================================================
            // Home Assistant & Local LAN Bridge Endpoints
            // =================================================================
            path == "/api/status" -> {
                val s = latestStatusSnapshot
                JSONObject().apply {
                    put("sn", s.sn)
                    put("state", when (s.mode) {
                        "sweep", "smartClean", "appointClean", "depthTotalClean" -> "cleaning"
                        "backcharge" -> "returning"
                        "pause", "findchargerpause" -> "paused"
                        "charge", "fullcharge" -> "docked"
                        else -> "idle"
                    })
                    put("raw_mode", s.mode)
                    put("battery_level", s.battery)
                    put("fan_speed", s.suction.wireValue)
                    put("clean_area_m2", s.cleanAreaM2)
                    put("clean_time_sec", s.cleanTimeSec)
                    put("position_mm", JSONArray().put(s.posMm.xMm).put(s.posMm.yMm))
                    put("online", s.isOnline)
                }.toString()
            }

            path == "/api/map.json" -> {
                val m = latestMapSnapshot
                val p = latestPathSnapshot
                JSONObject().apply {
                    put("has_map", m != null)
                    if (m != null) {
                        put("mapId", m.mapId)
                        put("autoAreaId", m.autoAreaId)
                        put("width", m.width)
                        put("height", m.height)
                        put("resolution", m.resolution)
                        put("x_min", m.xMin)
                        put("y_min", m.yMin)
                        put("rooms", JSONArray(m.roomLabels.toList()))
                        val zonesArr = JSONArray()
                        m.areas.forEach { zonesArr.put(ProscenicProtocol.serializeRegion(it)) }
                        put("zones", zonesArr)
                    }
                    put("path_points", p.points.size)
                }.toString()
            }

            path == "/api/command" && method == "POST" -> {
                val req = runCatching { JSONObject(body) }.getOrDefault(JSONObject())
                val action = req.optString("command", req.optString("action", ""))
                trace("HA-API", "RX", "Home Assistant command: $action", body)
                onExternalHaCommand(action, req)
                JSONObject().put("status", "ok").put("command", action).toString()
            }

            else -> {
                JSONObject().put("code", 0).put("message", "ok").put("data", JSONObject()).toString()
            }
        }
    }

    private fun handleRobotUploadOrResponse(path: String, formBody: String) {
        val params = parseFormFields(formBody)
        val dataRaw = params["data"] ?: return
        val outer = runCatching { JSONObject(dataRaw) }.getOrNull() ?: return
        val infoType = outer.optInt("infoType", params["infoType"]?.toIntOrNull() ?: -1)
        val message = outer.optString("message", "").trim()
        val innerData = outer.optJSONObject("data") ?: outer

        trace(
            "A-HTTP",
            "RX",
            "POST $path (infoType=$infoType${if (message.isNotEmpty()) ", msg=$message" else ""})",
            dataRaw.take(240)
        )
        dispatchRobotInfoPayload(infoType, innerData, message, dataRaw)
    }

    private fun dispatchRobotInfoPayload(
        infoType: Int,
        innerData: JSONObject,
        message: String = "",
        rawJson: String = ""
    ) {
        try {
            dispatchRobotInfoPayloadUnsafe(infoType, innerData, message, rawJson)
        } catch (_: Exception) {
            // Malformed robot payload — never let it take down the app
        }
    }

    private fun dispatchRobotInfoPayloadUnsafe(
        infoType: Int,
        innerData: JSONObject,
        message: String,
        rawJson: String
    ) {
        when (infoType) {
            20001 -> {
                val updated = ProscenicProtocol.parseStatus20001(innerData, latestStatusSnapshot)
                latestStatusSnapshot = updated
                onStatusReceived(updated)
            }
            20002 -> {
                runCatching {
                    val map = ProscenicProtocol.parseMap20002(innerData)
                    latestMapSnapshot = map
                    onMapReceived(map)
                }
            }
            21011 -> {
                runCatching {
                    val path = ProscenicProtocol.parsePath21011(innerData)
                    latestPathSnapshot = path
                    onPathReceived(path)
                }
            }
            21004 -> {
                val arr = innerData.optJSONArray("value") ?: JSONArray()
                val regions = mutableListOf<MapRegion>()
                for (i in 0 until arr.length()) {
                    val rObj = arr.optJSONObject(i) ?: continue
                    regions.add(ProscenicProtocol.parseRegion(rObj, fallbackId = i + 1))
                }
                onZonesReceived(regions)
            }
            21002 -> {
                val schedules = ProscenicProtocol.parseSchedulesFrom21002(innerData)
                onSchedulesReceived(schedules)
            }
            21001, 21003, 21005, 21012, 21017, 21020, 21022, 21023, 21024, 21025, 21027, 21030 -> {
                val ackMsg = message.ifBlank { innerData.optString("message", "ok") }
                onCommandAck(infoType, ackMsg, rawJson.ifBlank { innerData.toString() })
                // Immediately request fresh status (and fresh map after 21030 room segmentation)
                writeFrameInternal(ProscenicProtocol.buildRequestStatus())
                if (infoType == 21030) {
                    scope.launch(Dispatchers.IO) {
                        delay(1200L)
                        writeFrameInternal(ProscenicProtocol.buildRequestMapUploadNow())
                    }
                }
            }
        }
    }

    private fun parseFormFields(body: String): Map<String, String> {
        // Notice: `data=` in `cleanPack/uploadEvents` is often raw plaintext JSON after `data=`,
        // which may contain `&` characters if unescaped (`PROTOCOL.md` §A).
        val dataMarker = body.indexOf("data=")
        val map = mutableMapOf<String, String>()
        if (dataMarker >= 0) {
            val prefix = body.substring(0, dataMarker)
            val rawData = body.substring(dataMarker + 5)
            for (pair in prefix.split('&')) {
                if (pair.isBlank()) continue
                val k = pair.substringBefore('=')
                val v = pair.substringAfter('=', "")
                map[k] = runCatching { URLDecoder.decode(v, "UTF-8") }.getOrDefault(v)
            }
            val decodedData = if (rawData.startsWith("%7B", ignoreCase = true)) {
                runCatching { URLDecoder.decode(rawData, "UTF-8") }.getOrDefault(rawData)
            } else {
                rawData
            }
            map["data"] = decodedData
        } else {
            for (pair in body.split('&')) {
                if (pair.isBlank()) continue
                val k = pair.substringBefore('=')
                val v = pair.substringAfter('=', "")
                map[k] = runCatching { URLDecoder.decode(v, "UTF-8") }.getOrDefault(v)
            }
        }
        return map
    }

    private fun trace(channel: String, direction: String, summary: String, payload: String) {
        onWireTrace(
            WireTraceEntry(
                channel = channel,
                direction = direction,
                summary = summary,
                payload = payload
            )
        )
    }

    companion object {
        fun getLocalLanIpAddress(): String {
            return try {
                val interfaces = NetworkInterface.getNetworkInterfaces()
                while (interfaces.hasMoreElements()) {
                    val iface = interfaces.nextElement()
                    if (iface.isLoopback || !iface.isUp) continue
                    val addrs = iface.inetAddresses
                    while (addrs.hasMoreElements()) {
                        val addr = addrs.nextElement()
                        if (addr is Inet4Address && !addr.isLoopbackAddress) {
                            val ip = addr.hostAddress ?: continue
                            if (ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.")) {
                                return ip
                            }
                        }
                    }
                }
                "192.168.1.100"
            } catch (_: Exception) {
                "192.168.1.100"
            }
        }
    }
}
