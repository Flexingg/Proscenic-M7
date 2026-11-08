package com.proscenic.m7.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.proscenic.m7.ai.AiMapArtEngine
import com.proscenic.m7.ai.AiMapArtItem
import com.proscenic.m7.ai.ByokProvider
import com.proscenic.m7.export.MapExportManager
import com.proscenic.m7.protocol.CleanPath
import com.proscenic.m7.protocol.MapRegion
import com.proscenic.m7.protocol.PointMm
import com.proscenic.m7.protocol.ProscenicProtocol
import com.proscenic.m7.protocol.QuietHoursConfig
import com.proscenic.m7.protocol.RehomeClient
import com.proscenic.m7.protocol.ScheduleEntry
import com.proscenic.m7.protocol.SuctionLevel
import com.proscenic.m7.protocol.VacuumMap
import com.proscenic.m7.protocol.VacuumStatus
import com.proscenic.m7.protocol.ZoneKind
import com.proscenic.m7.server.LocalVacuumServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt

enum class ConnectionMode(val label: String) {
    LOCAL_SERVER("On-Phone Server (:8080/:8081)"),
    LAN_BRIDGE("External LAN Server / HA")
}

enum class MapInteractionMode(val label: String) {
    SELECT_ROOMS("Rooms"),
    EDIT_ZONES("Zones & No-Go"),
    AI_MAP_ART("AI Map Art"),
    PLACED_SPOT("Spot Target"),
    SPLIT_MERGE("Split / Merge")
}

data class VacuumUiState(
    val connectionMode: ConnectionMode = ConnectionMode.LOCAL_SERVER,
    val externalServerUrl: String = "http://192.168.1.10:8080",
    val localLanIp: String = LocalVacuumServer.getLocalLanIpAddress(),
    val serverRunning: Boolean = false,
    val robotSocketConnected: Boolean = false,
    val connectedRobotAddress: String? = null,
    val connectedRobotSn: String? = null,
    val heartbeatCount: Int = 0,
    val status: VacuumStatus = VacuumStatus(
        sn = "",
        mode = "offline",
        battery = 0,
        posMm = PointMm(0, 0),
        isOnline = false
    ),
    val map: VacuumMap? = null,
    val path: CleanPath = CleanPath(),
    val regions: List<MapRegion> = emptyList(),
    val roomNames: Map<Int, String> = emptyMap(),
    val selectedRoomLabels: Set<Int> = emptySet(),
    val selectedZoneIds: Set<Int> = emptySet(),
    val mapInteractionMode: MapInteractionMode = MapInteractionMode.SELECT_ROOMS,
    val activeZoneDrawKind: ZoneKind = ZoneKind.CLEAN_NORMAL,
    val placedSpotTargetMm: PointMm = PointMm(0, 0),
    val placedSpotDeepClean: Boolean = false,
    val yShapedMopEnabled: Boolean = false,
    val mergeFirstRoomLabel: Int? = null,
    // AI Map Art & BYOK State
    val aiArtItems: List<AiMapArtItem> = emptyList(),
    val selectedAiArtId: String? = null,
    val showAiArtStudioModal: Boolean = false,
    val pendingAiBoxCenterMm: PointMm = PointMm(0, 0),
    val pendingAiBoxWidthCm: Int = 180,
    val pendingAiBoxDepthCm: Int = 90,
    val aiGenerationInProgress: Boolean = false,
    val aiGenerationStatus: String? = null,
    val byokProvider: ByokProvider = ByokProvider.GEMINI,
    val geminiApiKey: String = "",
    val claudeApiKey: String = "",
    val openAiApiKey: String = "",
    val schedules: List<ScheduleEntry> = emptyList(),
    val quietHours: QuietHoursConfig = QuietHoursConfig(enabled = false, startHour = 23, startMinute = 0, endHour = 7, endMinute = 0),
    val rehomeSteps: List<RehomeClient.StepLog> = emptyList(),
    val rehomeInProgress: Boolean = false,
    val rehomeStatusBanner: String? = null,
    val savedWifiSsid: String = "Robots",
    val savedWifiPassword: String = "",
    val lastDiscoveredRobotLanIp: String? = null,
    val wireTraces: List<LocalVacuumServer.WireTraceEntry> = emptyList(),
    val lastActionBanner: String = "Listening on :8080 (HTTP) & :8081 (TCP) — waiting for Proscenic M7 to connect",
    // ColorOS 17 Living Flow Design accessibility & visual preferences
    val darkThemeOverride: Boolean? = true,
    val reduceTransparency: Boolean = false,
    val reduceMotion: Boolean = false,
    val highContrast: Boolean = false
)

class VacuumViewModel : ViewModel() {

    private val storageDir: File = File(System.getProperty("java.io.tmpdir") ?: "/tmp")

    private val prefsFile: File = File(storageDir, "proscenic_m7_pairing_prefs.json")
    private val cachedMapFile: File = File(storageDir, "proscenic_m7_cached_map.json")

    private val _uiState = MutableStateFlow(VacuumUiState())
    val uiState: StateFlow<VacuumUiState> = _uiState.asStateFlow()

    fun setDarkThemeOverride(dark: Boolean?) {
        _uiState.update { it.copy(darkThemeOverride = dark) }
    }

    fun setReduceTransparency(enabled: Boolean) {
        _uiState.update { it.copy(reduceTransparency = enabled) }
    }

    fun setReduceMotion(enabled: Boolean) {
        _uiState.update { it.copy(reduceMotion = enabled) }
    }

    fun setHighContrast(enabled: Boolean) {
        _uiState.update { it.copy(highContrast = enabled) }
    }

    fun updateSavedWifiCredentials(ssid: String, password: String) {
        _uiState.update { it.copy(savedWifiSsid = ssid, savedWifiPassword = password) }
        persistPairingPrefs()
    }

    private fun loadPairingPrefs() {
        runCatching {
            if (prefsFile.exists()) {
                val json = JSONObject(prefsFile.readText(Charsets.UTF_8))
                val ssid = json.optString("wifiSsid", "Robots").ifBlank { "Robots" }
                val pwd = json.optString("wifiPassword", "")
                val lastIp = json.optString("lastRobotLanIp", "").ifBlank { null }
                val providerStr = json.optString("byokProvider", ByokProvider.GEMINI.name)
                val provider = runCatching { ByokProvider.valueOf(providerStr) }.getOrDefault(ByokProvider.GEMINI)
                val geminiKey = json.optString("geminiApiKey", "")
                val claudeKey = json.optString("claudeApiKey", "")
                val openAiKey = json.optString("openAiApiKey", "")
                _uiState.update {
                    it.copy(
                        savedWifiSsid = ssid,
                        savedWifiPassword = pwd,
                        lastDiscoveredRobotLanIp = lastIp,
                        byokProvider = provider,
                        geminiApiKey = geminiKey,
                        claudeApiKey = claudeKey,
                        openAiApiKey = openAiKey
                    )
                }
            }
        }
    }

    private fun persistPairingPrefs() {
        val s = _uiState.value
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val json = JSONObject().apply {
                    put("wifiSsid", s.savedWifiSsid)
                    put("wifiPassword", s.savedWifiPassword)
                    if (!s.lastDiscoveredRobotLanIp.isNullOrBlank()) put("lastRobotLanIp", s.lastDiscoveredRobotLanIp)
                    put("byokProvider", s.byokProvider.name)
                    put("geminiApiKey", s.geminiApiKey)
                    put("claudeApiKey", s.claudeApiKey)
                    put("openAiApiKey", s.openAiApiKey)
                }
                prefsFile.writeText(json.toString(), Charsets.UTF_8)
            }
        }
    }

    private fun loadCachedMapAndArt() {
        runCatching {
            if (cachedMapFile.exists()) {
                val root = JSONObject(cachedMapFile.readText(Charsets.UTF_8))
                val parsedMap = root.optJSONObject("map20002")?.let { ProscenicProtocol.parseMapUpload20002(it) }
                val namesObj = root.optJSONObject("roomNames")
                val namesMap = mutableMapOf<Int, String>()
                if (namesObj != null) {
                    val keys = namesObj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        k.toIntOrNull()?.let { id -> namesMap[id] = namesObj.optString(k, "Room $id") }
                    }
                }
                val artArr = root.optJSONArray("aiMapArtItems")
                val artItems = mutableListOf<AiMapArtItem>()
                if (artArr != null) {
                    for (i in 0 until artArr.length()) {
                        artArr.optJSONObject(i)?.let { obj ->
                            runCatching { AiMapArtItem.fromJson(obj) }.getOrNull()?.let { artItems.add(it) }
                        }
                    }
                }
                _uiState.update { state ->
                    val mergedNames = if (parsedMap != null) {
                        parsedMap.roomLabels.associateWith { id -> namesMap[id] ?: "Room $id" }
                    } else namesMap
                    state.copy(
                        map = parsedMap ?: state.map,
                        regions = parsedMap?.areas ?: state.regions,
                        roomNames = mergedNames.ifEmpty { state.roomNames },
                        aiArtItems = artItems
                    )
                }
            }
        }
    }

    private fun persistCachedMapAndArt() {
        val s = _uiState.value
        val currentMap = s.map ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val bundle = MapExportManager.serializeFullMapBundleJson(
                    map = currentMap,
                    regions = s.regions,
                    roomNames = s.roomNames,
                    aiArtItems = s.aiArtItems
                )
                cachedMapFile.writeText(bundle.toString(), Charsets.UTF_8)
            }
        }
    }

    private val localServer = LocalVacuumServer(
        httpPort = 8080,
        gatewayPort = 8081,
        onStatusReceived = { status ->
            _uiState.update {
                it.copy(
                    status = status,
                    robotSocketConnected = true,
                    lastActionBanner = if (status.isSleepOrShutdown) {
                        "Robot is in '${status.mode}' sleep mode (${status.battery}%). Tap 'Wake Robot' or press physical Power button on vacuum to control."
                    } else {
                        "Live telemetry from ${status.sn} (${status.modeDisplayLabel}, ${status.battery}%)"
                    }
                )
            }
        },
        onMapReceived = { newMap ->
            _uiState.update { state ->
                val mergedRegions = if (newMap.areas.isNotEmpty()) newMap.areas else state.regions
                val autoRoomNames = newMap.roomLabels.associateWith { id ->
                    state.roomNames[id] ?: "Room $id"
                }
                state.copy(
                    map = newMap.copy(areas = mergedRegions),
                    regions = mergedRegions,
                    roomNames = autoRoomNames,
                    lastActionBanner = "Received 20002 floor map (${newMap.width}x${newMap.height}, ${newMap.roomLabels.size} rooms)"
                )
            }
            persistCachedMapAndArt()
        },
        onPathReceived = { newPath ->
            _uiState.update { it.copy(path = newPath) }
        },
        onZonesReceived = { zones ->
            _uiState.update { state ->
                state.copy(
                    regions = zones,
                    map = state.map?.copy(areas = zones),
                    lastActionBanner = "Received ${zones.size} saved zone(s) from robot (21004)"
                )
            }
            persistCachedMapAndArt()
        },
        onSchedulesReceived = { scheds ->
            _uiState.update {
                it.copy(
                    schedules = scheds,
                    lastActionBanner = "Received ${scheds.size} schedule(s) from robot (21002)"
                )
            }
        },
        onCommandAck = { infoType, message, _ ->
            _uiState.update { state ->
                val banner = if (message.equals("fail", ignoreCase = true)) {
                    if (state.status.isSleepOrShutdown) {
                        "⚠ Robot rejected command $infoType ('fail') — Robot is in '${state.status.mode}' sleep mode! Press Power button on vacuum or place on dock to wake."
                    } else {
                        "⚠ Robot returned 'fail' for command $infoType (mode=${state.status.mode})"
                    }
                } else {
                    "✓ Robot ACK $infoType: $message (${state.status.modeDisplayLabel})"
                }
                state.copy(lastActionBanner = banner)
            }
        },
        onWireTrace = { entry ->
            _uiState.update { state ->
                state.copy(wireTraces = (listOf(entry) + state.wireTraces).take(100))
            }
        },
        onExternalHaCommand = { action, payload ->
            handleHomeAssistantCommand(action, payload)
        }
    )

    init {
        loadPairingPrefs()
        loadCachedMapAndArt()
        startEmbeddedServer()
        startLanIpMonitor()
    }

    override fun onCleared() {
        super.onCleared()
        localServer.stop()
    }

    private fun startEmbeddedServer() {
        localServer.start()
        viewModelScope.launch {
            localServer.isRobotConnected.collect { connected ->
                _uiState.update { state ->
                    state.copy(
                        robotSocketConnected = connected,
                        serverRunning = true,
                        status = if (!connected) state.status.copy(isOnline = false, mode = "offline") else state.status.copy(isOnline = true),
                        lastActionBanner = if (connected) {
                            "Robot connected on TCP :8081! Requesting live status & map..."
                        } else {
                            "Listening on ${state.localLanIp}:8080 (HTTP) & :8081 (TCP) — waiting for robot"
                        }
                    )
                }
            }
        }
        viewModelScope.launch {
            localServer.connectedRobotAddress.collect { addr ->
                val ipOnly = addr?.substringBefore(':')?.takeIf { it.isNotBlank() }
                _uiState.update {
                    it.copy(
                        connectedRobotAddress = addr,
                        lastDiscoveredRobotLanIp = ipOnly ?: it.lastDiscoveredRobotLanIp
                    )
                }
                if (ipOnly != null) {
                    persistPairingPrefs()
                }
            }
        }
        viewModelScope.launch {
            localServer.connectedRobotSn.collect { sn ->
                _uiState.update { it.copy(connectedRobotSn = sn) }
            }
        }
        viewModelScope.launch {
            localServer.heartbeatCount.collect { count ->
                _uiState.update { it.copy(heartbeatCount = count) }
            }
        }
    }

    /**
     * Periodically refreshes the phone's local Wi-Fi IP and, when on Home Wi-Fi and not yet
     * connected to the robot, automatically probes LAN UDP 7913 to re-point the robot if the
     * phone's DHCP IP changed (e.g. from `192.168.1.110` to `192.168.1.165`).
     */
    private fun startLanIpMonitor() {
        viewModelScope.launch(Dispatchers.IO) {
            var lastAutoProbedIp = ""
            while (isActive) {
                val currentIp = LocalVacuumServer.getLocalLanIpAddress()
                if (currentIp != _uiState.value.localLanIp) {
                    _uiState.update { it.copy(localLanIp = currentIp) }
                }
                // Auto-repoint once per Home LAN IP if disconnected
                if (!currentIp.startsWith("192.168.78.") &&
                    currentIp != "0.0.0.0" &&
                    !_uiState.value.robotSocketConnected &&
                    !_uiState.value.rehomeInProgress &&
                    currentIp != lastAutoProbedIp
                ) {
                    lastAutoProbedIp = currentIp
                    delay(2000L)
                    if (!_uiState.value.robotSocketConnected && !_uiState.value.rehomeInProgress) {
                        silentAutoRepointOnLan(currentIp)
                    }
                }
                delay(3000L)
            }
        }
    }

    private suspend fun silentAutoRepointOnLan(serverHostIp: String) {
        val state = _uiState.value
        val client = RehomeClient()
        val result = client.repointRobotOnLan(
            serverHostIp = serverHostIp,
            httpPort = 8080,
            gatewayPort = 8081,
            wifiSsid = state.savedWifiSsid,
            wifiPassword = state.savedWifiPassword,
            knownRobotIp = state.lastDiscoveredRobotLanIp
        )
        if (result.success) {
            _uiState.update {
                it.copy(
                    lastDiscoveredRobotLanIp = result.discoveredRobotIp ?: it.lastDiscoveredRobotLanIp,
                    rehomeSteps = result.steps,
                    rehomeStatusBanner = "Auto-discovered robot at ${result.discoveredRobotIp}:7913 (SN=${result.robotSerialNumber}) and updated target IP to $serverHostIp:8081!",
                    lastActionBanner = "Auto-repointed robot at ${result.discoveredRobotIp} to $serverHostIp:8081 over LAN UDP 7913!"
                )
            }
        }
    }

    fun refreshRobotTelemetryNow() {
        localServer.sendFramesToRobot(
            listOf(
                ProscenicProtocol.buildRequestStatus(),
                ProscenicProtocol.buildRequestMapUploadNow(),
                ProscenicProtocol.buildGetAreaTactics(),
                ProscenicProtocol.buildGetSchedules()
            )
        )
        _uiState.update {
            it.copy(
                lastActionBanner = if (it.robotSocketConnected) {
                    "Sent live status (20001), map (20002), zones (21004) & schedules (21002) refresh"
                } else {
                    "Robot is not connected on TCP :8081 yet — commands queued"
                }
            )
        }
    }

    fun setConnectionMode(mode: ConnectionMode) {
        _uiState.update {
            it.copy(
                connectionMode = mode,
                lastActionBanner = "Switched mode to ${mode.label}"
            )
        }
    }

    fun setExternalServerUrl(url: String) {
        _uiState.update { it.copy(externalServerUrl = url) }
    }

    fun setMapInteractionMode(mode: MapInteractionMode) {
        _uiState.update { it.copy(mapInteractionMode = mode, mergeFirstRoomLabel = null) }
    }

    fun setActiveZoneDrawKind(kind: ZoneKind) {
        _uiState.update { it.copy(activeZoneDrawKind = kind) }
    }

    fun toggleYShapedMop(enabled: Boolean) {
        _uiState.update { it.copy(yShapedMopEnabled = enabled) }
    }

    fun setPlacedSpotTarget(pointMm: PointMm) {
        _uiState.update { it.copy(placedSpotTargetMm = pointMm) }
    }

    fun togglePlacedSpotDeepClean(deep: Boolean) {
        _uiState.update { it.copy(placedSpotDeepClean = deep) }
    }

    // =========================================================================
    // Real Vacuum Controls (Channel B 21005, 21017, 21012, 21022, 21024, 21020)
    // =========================================================================

    /**
     * Attempts to wake the vacuum from `shutdown` / `dormant` standby over Channel B
     * by sending remote-control exit (`4000`), clean/pause toggle (`3002`), and status request (`20001`).
     */
    fun wakeRobotFromStandby() {
        localServer.sendFramesToRobot(
            listOf(
                ProscenicProtocol.buildRemoteControl(4000),
                ProscenicProtocol.buildRemoteControl(3002),
                ProscenicProtocol.buildRequestStatus()
            )
        )
        _uiState.update {
            it.copy(
                lastActionBanner = "Sent Wake Sequence (21020 ctrlCode=4000 & 3002). Note: If robot is in deep 'shutdown' off dock, press its physical Power button once."
            )
        }
    }

    fun startWholeHomeClean(deepClean: Boolean = false) {
        val state = _uiState.value
        val frame = if (deepClean) {
            ProscenicProtocol.buildStartDeepClean(yShapedMop = state.yShapedMopEnabled)
        } else {
            ProscenicProtocol.buildStartSmartClean(yShapedMop = state.yShapedMopEnabled)
        }
        if (state.status.isSleepOrShutdown) {
            // Send wake pulse before 21005 start command
            localServer.sendFramesToRobot(
                listOf(
                    ProscenicProtocol.buildRemoteControl(4000),
                    frame,
                    ProscenicProtocol.buildRequestStatus()
                )
            )
        } else {
            dispatchSingleFrame(
                frame,
                if (deepClean) "Sent Deep Whole-Home Clean (21005 depthTotalClean)" else "Sent Whole-Home Clean (21005 smartClean)"
            )
        }
    }

    fun pauseOrResumeCleaning() {
        val currentMode = _uiState.value.status.mode
        if (currentMode.equals("pause", ignoreCase = true) || currentMode.equals("findchargerpause", ignoreCase = true)) {
            val frame = ProscenicProtocol.buildPauseContinueStop("continue")
            dispatchSingleFrame(frame, "Sent Resume Cleaning (21017 continue)")
        } else {
            val frame = ProscenicProtocol.buildPauseContinueStop("pause")
            dispatchSingleFrame(frame, "Sent Pause Cleaning (21017 pause)")
        }
    }

    fun stopCleaning() {
        val frame = ProscenicProtocol.buildPauseContinueStop("stop")
        dispatchSingleFrame(frame, "Sent Stop Cleaning (21017 stop)")
    }

    fun returnToDock() {
        val frame = ProscenicProtocol.buildReturnToDock("start")
        dispatchSingleFrame(frame, "Sent Return to Charging Dock (21012 start)")
    }

    fun setSuctionLevel(level: SuctionLevel) {
        val frame = ProscenicProtocol.buildSetSuction(level)
        dispatchSingleFrame(frame, "Sent Suction ${level.label} (21022 ${level.wireValue})")
    }

    fun setWaterLevel(level: Int) {
        val clamped = level.coerceIn(1, 4)
        val frame = ProscenicProtocol.buildSetWaterLevel(clamped)
        dispatchSingleFrame(frame, "Sent Mop Water Level $clamped/4 (21024 setWaterPump)")
    }

    fun setSpeakerVolume(vol: Int) {
        val clamped = vol.coerceIn(0, 10)
        val frame = ProscenicProtocol.buildSetVolume(clamped)
        dispatchSingleFrame(frame, "Sent Speaker Volume ${clamped * 10}% (21024 setVolume=$clamped)")
    }

    fun collectDustNow() {
        val frame = ProscenicProtocol.buildStartDustCollection()
        dispatchSingleFrame(frame, "Sent Empty Dustbin Station (21024 startDustCenter)")
    }

    fun locateRobotBeep() {
        val frame = ProscenicProtocol.buildRemoteControl(3010)
        dispatchSingleFrame(frame, "Sent Locate Beep (21020 ctrlCode=3010)")
    }

    fun spotCleanHere() {
        val frame = ProscenicProtocol.buildRemoteControl(3001)
        dispatchSingleFrame(frame, "Sent 1.5m Spot Clean at current pose (21020 ctrlCode=3001)")
    }

    /**
     * Manual D-pad remote control (`infoType: 21020`, `ctrlCode` 3005..3008, 4001, 4000).
     * Per `FUNC_COMMANDS.md` §2.3 & `FIELD_NOTES.md`, each movement frame has a 400ms hardware
     * speed timeout (`SetSpeed(..., 300, 10000)`), so directional taps send a 4-frame burst over 750ms.
     */
    fun sendRemoteControlStep(ctrlCode: Int) {
        val frame = ProscenicProtocol.buildRemoteControl(ctrlCode)
        dispatchSingleFrame(frame, "Sent Manual Remote ctrlCode=$ctrlCode (21020)")
        if (ctrlCode in setOf(3005, 3006, 3007, 3008)) {
            viewModelScope.launch(Dispatchers.IO) {
                repeat(3) {
                    delay(240L)
                    localServer.sendFrameToRobot(ProscenicProtocol.buildRemoteControl(ctrlCode))
                }
            }
        }
    }

    // =========================================================================
    // Room Selection, Zone Editor & Placed Spot Clean (21003, 21023 + 21005, 21030)
    // =========================================================================

    fun toggleRoomSelection(roomLabel: Int) {
        val state = _uiState.value
        val currentMap = state.map ?: return
        if (state.mapInteractionMode == MapInteractionMode.SPLIT_MERGE) {
            val first = state.mergeFirstRoomLabel
            if (first == null) {
                _uiState.update {
                    it.copy(
                        mergeFirstRoomLabel = roomLabel,
                        lastActionBanner = "Selected Room #$roomLabel — tap a second room to merge, or draw a line to split Room #$roomLabel"
                    )
                }
            } else if (first != roomLabel) {
                val frame = ProscenicProtocol.buildRoomMerge(currentMap.autoAreaId, first, roomLabel)
                dispatchSingleFrame(frame, "Sent Merge Room #$first & Room #$roomLabel (21030 merge)")
                _uiState.update { it.copy(mergeFirstRoomLabel = null) }
            } else {
                _uiState.update { it.copy(mergeFirstRoomLabel = null) }
            }
            return
        }

        _uiState.update { current ->
            val next = if (roomLabel in current.selectedRoomLabels) {
                current.selectedRoomLabels - roomLabel
            } else {
                current.selectedRoomLabels + roomLabel
            }
            current.copy(selectedRoomLabels = next)
        }
    }

    fun splitSelectedRoom(p1Mm: PointMm, p2Mm: PointMm) {
        val state = _uiState.value
        val currentMap = state.map ?: return
        val targetRoom = state.mergeFirstRoomLabel ?: state.selectedRoomLabels.firstOrNull() ?: return
        val frame = ProscenicProtocol.buildRoomSplit(currentMap.autoAreaId, targetRoom, p1Mm, p2Mm)
        dispatchSingleFrame(frame, "Sent Split Room #$targetRoom along (${p1Mm.xMm},${p1Mm.yMm})->(${p2Mm.xMm},${p2Mm.yMm}) (21030 split)")
        _uiState.update { it.copy(mergeFirstRoomLabel = null) }
    }

    fun resetRoomSegmentation() {
        val state = _uiState.value
        val autoAreaId = state.map?.autoAreaId ?: 0
        val frame = ProscenicProtocol.buildRoomResetSegmentation(autoAreaId)
        dispatchSingleFrame(frame, "Sent Auto-Segment Rooms (21030 reset) + requesting updated 20002 map...")
        _uiState.update { it.copy(mergeFirstRoomLabel = null, selectedRoomLabels = emptySet()) }
        viewModelScope.launch(Dispatchers.IO) {
            delay(1400L)
            localServer.sendFrameToRobot(ProscenicProtocol.buildRequestMapUploadNow())
        }
    }

    fun addDrawnZone(p1Mm: PointMm, p2Mm: PointMm, customName: String? = null) {
        val state = _uiState.value
        val kind = state.activeZoneDrawKind
        val nextId = (state.regions.maxOfOrNull { it.id } ?: 0) + 1
        val name = customName?.takeIf { it.isNotBlank() } ?: "${kind.label} #$nextId"
        val newRegion = MapRegion.createRectangle(
            id = nextId,
            name = name,
            kind = kind,
            p1 = p1Mm,
            p2 = p2Mm,
            suction = state.status.suction
        )
        val updatedRegions = state.regions + newRegion
        pushUpdatedRegions(updatedRegions, "Sent ${kind.label} '$name' to robot (21003 SetAreaTactics)")
    }

    fun deleteZone(zoneId: Int) {
        val updated = _uiState.value.regions.filterNot { it.id == zoneId }
        pushUpdatedRegions(updated, "Removed zone #$zoneId on robot (21003 SetAreaTactics)")
    }

    fun toggleZoneSelection(zoneId: Int) {
        _uiState.update { state ->
            val next = if (zoneId in state.selectedZoneIds) {
                state.selectedZoneIds - zoneId
            } else {
                state.selectedZoneIds + zoneId
            }
            state.copy(selectedZoneIds = next)
        }
    }

    private fun pushUpdatedRegions(regions: List<MapRegion>, banner: String) {
        val mapId = _uiState.value.map?.mapId ?: 0L
        val frame = ProscenicProtocol.buildSetAreaTactics(mapId, regions)
        dispatchSingleFrame(frame, banner)
        _uiState.update { state ->
            val updatedMap = state.map?.copy(areas = regions)
            localServer.latestMapSnapshot = updatedMap
            state.copy(
                regions = regions,
                map = updatedMap,
                selectedZoneIds = state.selectedZoneIds.intersect(regions.map { it.id }.toSet())
            )
        }
    }

    fun startSelectedRoomsOrZonesClean() {
        val state = _uiState.value
        val roomIds = state.selectedRoomLabels.sorted()
        val zoneIds = state.selectedZoneIds.sorted().ifEmpty {
            if (state.mapInteractionMode == MapInteractionMode.EDIT_ZONES) {
                state.regions.filter { !it.kind.isForbidden }.map { it.id }
            } else emptyList()
        }

        if (roomIds.isEmpty() && zoneIds.isEmpty()) {
            startWholeHomeClean(deepClean = false)
            return
        }

        // Convert selected rooms into inline `extraAreas` bounding boxes as well (`FIELD_NOTES.md`),
        // because 0.7.1/1241 firmware honours `extraAreas` (`mode:"area"`) even when `segmentId` is ignored.
        val roomExtraRegions = roomIds.mapNotNull { label ->
            state.map?.roomBoundingBoxRegion(label, state.status.suction)
        }

        val frames = ProscenicProtocol.buildZoneOrRoomCleanSequence(
            storedZoneIds = zoneIds,
            extraAreas = roomExtraRegions,
            segmentRoomLabels = roomIds,
            includeStoredForbiddenZones = true
        )
        localServer.sendFramesToRobot(frames)
        _uiState.update {
            it.copy(
                lastActionBanner = "Sent 21023 ActiveRegions (rooms=$roomIds, zones=$zoneIds) + 21005 appointClean"
            )
        }
    }

    fun startPlacedSpotClean() {
        val state = _uiState.value
        val spotRegion = MapRegion.createPlacedSpotSquare(
            centerMm = state.placedSpotTargetMm,
            deepClean = state.placedSpotDeepClean
        )
        val frames = ProscenicProtocol.buildZoneOrRoomCleanSequence(
            storedZoneIds = emptyList(),
            extraAreas = listOf(spotRegion),
            includeStoredForbiddenZones = true
        )
        localServer.sendFramesToRobot(frames)
        val c = state.placedSpotTargetMm
        _uiState.update {
            it.copy(
                lastActionBanner = "Sent Placed Spot Clean at (${c.xMm}, ${c.yMm}) mm via 21023 + 21005 appointClean"
            )
        }
    }

    // =========================================================================
    // Schedules & Quiet Hours (21001 SetTimeTactics / 21002 GetTimeTactics)
    // =========================================================================

    fun saveOrUpdateSchedule(entry: ScheduleEntry) {
        val current = _uiState.value.schedules.toMutableList()
        val idx = current.indexOfFirst { it.id == entry.id }
        if (idx >= 0) {
            current[idx] = entry
        } else {
            current.add(entry)
        }
        pushSchedulesToRobot(current, _uiState.value.quietHours, "Sent schedule '${entry.label}' (${entry.formattedTime}) via 21001")
    }

    fun toggleScheduleEnabled(scheduleId: String, enabled: Boolean) {
        val updated = _uiState.value.schedules.map {
            if (it.id == scheduleId) it.copy(enabled = enabled) else it
        }
        pushSchedulesToRobot(updated, _uiState.value.quietHours, "Updated schedule unlock=$enabled via 21001")
    }

    fun deleteSchedule(scheduleId: String) {
        val updated = _uiState.value.schedules.filterNot { it.id == scheduleId }
        pushSchedulesToRobot(updated, _uiState.value.quietHours, "Deleted schedule via 21001")
    }

    fun updateQuietHours(config: QuietHoursConfig) {
        pushSchedulesToRobot(
            _uiState.value.schedules,
            config,
            "Updated Quiet Hours (${"%02d:%02d".format(config.startHour, config.startMinute)}–${"%02d:%02d".format(config.endHour, config.endMinute)}) via 21001"
        )
    }

    private fun pushSchedulesToRobot(
        schedules: List<ScheduleEntry>,
        quietHours: QuietHoursConfig,
        banner: String
    ) {
        val setFrame = ProscenicProtocol.buildSetSchedules(schedules, quietHours)
        val verifyFrame = ProscenicProtocol.buildGetSchedules()
        localServer.sendFramesToRobot(listOf(setFrame, verifyFrame))
        _uiState.update {
            it.copy(
                schedules = schedules,
                quietHours = quietHours,
                lastActionBanner = banner
            )
        }
    }

    // =========================================================================
    // AI Map Art (BYOK Claude / Gemini / OpenAI) & Download Map
    // =========================================================================

    fun setByokProvider(provider: ByokProvider) {
        _uiState.update { it.copy(byokProvider = provider) }
        persistPairingPrefs()
    }

    fun setByokApiKey(provider: ByokProvider, key: String) {
        val trimmed = key.trim()
        _uiState.update { state ->
            when (provider) {
                ByokProvider.GEMINI -> state.copy(geminiApiKey = trimmed)
                ByokProvider.CLAUDE -> state.copy(claudeApiKey = trimmed)
                ByokProvider.OPENAI -> state.copy(openAiApiKey = trimmed)
            }
        }
        persistPairingPrefs()
    }

    fun currentApiKeyFor(provider: ByokProvider): String {
        val s = _uiState.value
        return when (provider) {
            ByokProvider.GEMINI -> s.geminiApiKey
            ByokProvider.CLAUDE -> s.claudeApiKey
            ByokProvider.OPENAI -> s.openAiApiKey
        }
    }

    fun onAiFootprintBoxDrawn(p1Mm: PointMm, p2Mm: PointMm) {
        val center = PointMm(
            xMm = (p1Mm.xMm + p2Mm.xMm) / 2,
            yMm = (p1Mm.yMm + p2Mm.yMm) / 2
        )
        val wCm = (abs(p2Mm.xMm - p1Mm.xMm) / 10).coerceIn(20, 800)
        val dCm = (abs(p2Mm.yMm - p1Mm.yMm) / 10).coerceIn(20, 800)
        _uiState.update {
            it.copy(
                pendingAiBoxCenterMm = center,
                pendingAiBoxWidthCm = wCm,
                pendingAiBoxDepthCm = dCm,
                showAiArtStudioModal = true,
                aiGenerationStatus = "Selected ${wCm}cm × ${dCm}cm footprint on map at (${center.xMm}, ${center.yMm}) mm"
            )
        }
    }

    fun openAiArtStudioModal() {
        _uiState.update {
            it.copy(
                showAiArtStudioModal = true,
                aiGenerationStatus = null
            )
        }
    }

    fun dismissAiArtStudioModal() {
        _uiState.update {
            it.copy(
                showAiArtStudioModal = false,
                aiGenerationStatus = null
            )
        }
    }

    fun selectAiArtItem(id: String?) {
        _uiState.update { state ->
            val next = if (state.selectedAiArtId == id) null else id
            state.copy(selectedAiArtId = next)
        }
    }

    fun moveAiArtItem(id: String, centerMm: PointMm) {
        _uiState.update { state ->
            val updated = state.aiArtItems.map { item ->
                if (item.id == id) item.copy(centerMm = centerMm) else item
            }
            state.copy(
                aiArtItems = updated,
                selectedAiArtId = id
            )
        }
        persistCachedMapAndArt()
    }

    fun rotateSelectedAiArt(deltaDeg: Float) {
        val id = _uiState.value.selectedAiArtId ?: return
        _uiState.update { state ->
            val updated = state.aiArtItems.map { item ->
                if (item.id == id) {
                    val nextRot = ((item.rotationDeg + deltaDeg) % 360f + 360f) % 360f
                    item.copy(rotationDeg = nextRot)
                } else item
            }
            state.copy(aiArtItems = updated)
        }
        persistCachedMapAndArt()
    }

    fun resizeSelectedAiArt(scaleFactor: Float) {
        val id = _uiState.value.selectedAiArtId ?: return
        _uiState.update { state ->
            val updated = state.aiArtItems.map { item ->
                if (item.id == id) {
                    val newW = (item.widthMm * scaleFactor).roundToInt().coerceIn(200, 9000)
                    val newH = (item.heightMm * scaleFactor).roundToInt().coerceIn(200, 9000)
                    item.copy(widthMm = newW, heightMm = newH)
                } else item
            }
            state.copy(aiArtItems = updated)
        }
        persistCachedMapAndArt()
    }

    fun deleteSelectedAiArt() {
        val id = _uiState.value.selectedAiArtId ?: return
        _uiState.update { state ->
            state.copy(
                aiArtItems = state.aiArtItems.filterNot { it.id == id },
                selectedAiArtId = null,
                lastActionBanner = "Removed AI Map Art object from floor map"
            )
        }
        persistCachedMapAndArt()
    }

    fun generateAiMapArt(
        photoBitmap: Bitmap?,
        objectLabel: String,
        widthCm: Int,
        depthCm: Int,
        rotationDeg: Float
    ) {
        if (_uiState.value.aiGenerationInProgress) return
        val state = _uiState.value
        val provider = state.byokProvider
        val apiKey = currentApiKeyFor(provider)
        val cleanLabel = objectLabel.ifBlank { "Furniture" }.trim()
        val safeW = widthCm.coerceIn(20, 800)
        val safeD = depthCm.coerceIn(20, 800)

        _uiState.update {
            it.copy(
                aiGenerationInProgress = true,
                aiGenerationStatus = "Analyzing photo & generating top-down '${cleanLabel}' (${safeW}×${safeD}cm) with ${provider.displayName}..."
            )
        }

        viewModelScope.launch {
            val result = AiMapArtEngine.generateTopDownObjectArt(
                provider = provider,
                apiKey = apiKey,
                photoBitmap = photoBitmap,
                objectLabel = cleanLabel,
                widthCm = safeW,
                depthCm = safeD
            )

            result.fold(
                onSuccess = { gen ->
                    val newItem = AiMapArtItem(
                        id = UUID.randomUUID().toString(),
                        label = gen.detectedLabel.ifBlank { cleanLabel },
                        centerMm = _uiState.value.pendingAiBoxCenterMm,
                        widthMm = safeW * 10,
                        heightMm = safeD * 10,
                        rotationDeg = rotationDeg,
                        topDownPngBase64 = gen.topDownPngBase64,
                        sourcePhotoThumbBase64 = null,
                        providerUsed = provider.label,
                        aiDescription = gen.description
                    )
                    _uiState.update { s ->
                        s.copy(
                            aiArtItems = s.aiArtItems + newItem,
                            selectedAiArtId = newItem.id,
                            mapInteractionMode = MapInteractionMode.AI_MAP_ART,
                            aiGenerationInProgress = false,
                            showAiArtStudioModal = false,
                            aiGenerationStatus = null,
                            lastActionBanner = "Placed top-down AI Map Art '${newItem.label}' (${safeW}×${safeD}cm) via ${gen.providerSummary}!"
                        )
                    }
                    persistCachedMapAndArt()
                },
                onFailure = { err ->
                    _uiState.update { s ->
                        s.copy(
                            aiGenerationInProgress = false,
                            aiGenerationStatus = "Error: ${err.message ?: "Failed to generate top-down art"}"
                        )
                    }
                }
            )
        }
    }

    fun downloadMapToDevice(context: Context) {
        val state = _uiState.value
        val currentMap = state.map
        if (currentMap == null) {
            _uiState.update {
                it.copy(lastActionBanner = "No 2D floor map received yet — connect to robot or request map first")
            }
            return
        }
        _uiState.update {
            it.copy(lastActionBanner = "Rendering high-res 2D floor map PNG + JSON bundle to device...")
        }
        viewModelScope.launch(Dispatchers.IO) {
            val res = MapExportManager.downloadMapPngAndJson(
                context = context,
                map = currentMap,
                status = state.status,
                path = state.path,
                regions = state.regions,
                roomNames = state.roomNames,
                aiArtItems = state.aiArtItems
            )
            val msg = res.getOrElse { "Map export failed: ${it.message}" }
            _uiState.update {
                it.copy(lastActionBanner = msg)
            }
        }
    }

    // =========================================================================
    // LAN UDP 7913 Re-Pointing & SoftAP UDP 7913 Re-homing Wizard
    // =========================================================================

    /**
     * Scans the Home Wi-Fi `/24` LAN for UDP 7913 and updates the robot's target server IP
     * (e.g. when the phone's DHCP IP changes from `192.168.1.110` to `192.168.1.165`)
     * without requiring the user to put the robot back into SoftAP mode!
     */
    fun reconnectRobotOnLanNow(
        serverHostIp: String = _uiState.value.localLanIp,
        wifiSsid: String = _uiState.value.savedWifiSsid,
        wifiPassword: String = _uiState.value.savedWifiPassword
    ) {
        if (_uiState.value.rehomeInProgress) return
        _uiState.update {
            it.copy(
                rehomeInProgress = true,
                rehomeSteps = emptyList(),
                rehomeStatusBanner = "Scanning home Wi-Fi LAN for vacuum on UDP 7913 to point at $serverHostIp:8081..."
            )
        }

        viewModelScope.launch {
            val client = RehomeClient()
            val result = client.repointRobotOnLan(
                serverHostIp = serverHostIp,
                httpPort = 8080,
                gatewayPort = 8081,
                wifiSsid = wifiSsid,
                wifiPassword = wifiPassword,
                knownRobotIp = _uiState.value.lastDiscoveredRobotLanIp,
                onStepProgress = { stepLog ->
                    _uiState.update { s -> s.copy(rehomeSteps = s.rehomeSteps + stepLog) }
                }
            )
            _uiState.update { s ->
                s.copy(
                    rehomeInProgress = false,
                    rehomeSteps = result.steps,
                    lastDiscoveredRobotLanIp = result.discoveredRobotIp ?: s.lastDiscoveredRobotLanIp,
                    rehomeStatusBanner = if (result.success) {
                        "Found robot on Home Wi-Fi at ${result.discoveredRobotIp}:7913 (SN=${result.robotSerialNumber}) and updated its target IP to $serverHostIp:8081! Waiting a few seconds for TCP connection..."
                    } else {
                        "Error: ${result.errorMessage}"
                    }
                )
            }
            if (!result.discoveredRobotIp.isNullOrBlank()) {
                persistPairingPrefs()
            }
        }
    }

    fun runRehomeWizard(
        robotApIp: String,
        serverHostIp: String,
        wifiSsid: String,
        wifiPassword: String,
        userId: String
    ) {
        if (_uiState.value.rehomeInProgress) return
        if (wifiSsid.isNotBlank() && !wifiSsid.startsWith("Proscenic-", ignoreCase = true)) {
            updateSavedWifiCredentials(wifiSsid.trim(), wifiPassword)
        }
        _uiState.update {
            it.copy(
                rehomeInProgress = true,
                rehomeSteps = emptyList(),
                rehomeStatusBanner = "Contacting vacuum on $robotApIp:7913 (and scanning LAN)..."
            )
        }

        viewModelScope.launch {
            val client = RehomeClient(robotIp = robotApIp.ifBlank { "192.168.78.1" })
            val result = client.executeRehome(
                serverHostIp = serverHostIp,
                httpPort = 8080,
                gatewayPort = 8081,
                wifiSsid = wifiSsid,
                wifiPassword = wifiPassword,
                userId = userId.ifBlank { "local_user" },
                onStepProgress = { stepLog ->
                    _uiState.update { s -> s.copy(rehomeSteps = s.rehomeSteps + stepLog) }
                }
            )
            _uiState.update { s ->
                s.copy(
                    rehomeInProgress = false,
                    rehomeSteps = result.steps,
                    lastDiscoveredRobotLanIp = result.discoveredRobotIp?.takeIf { it != "192.168.78.1" } ?: s.lastDiscoveredRobotLanIp,
                    rehomeStatusBanner = if (result.success) {
                        "Re-home succeeded (SN=${result.robotSerialNumber}, IP=${result.discoveredRobotIp}:${result.discoveredPort}). Make sure your phone is on '$wifiSsid' ($serverHostIp) — the vacuum will now connect to $serverHostIp:8081!"
                    } else {
                        "Error: ${result.errorMessage}"
                    }
                )
            }
            if (!result.discoveredRobotIp.isNullOrBlank() && result.discoveredRobotIp != "192.168.78.1") {
                persistPairingPrefs()
            }
        }
    }

    // =========================================================================
    // Dispatch Helpers & Home Assistant Bridge Handler
    // =========================================================================

    private fun dispatchSingleFrame(frame: String, banner: String) {
        val state = _uiState.value
        val sentNow = localServer.sendFrameToRobot(frame)
        if (state.connectionMode == ConnectionMode.LAN_BRIDGE) {
            postToExternalBridge(frame)
        }
        val statusPrefix = if (sentNow || state.connectionMode == ConnectionMode.LAN_BRIDGE) {
            banner
        } else {
            "$banner (Queued — waiting for robot to connect on TCP :8081)"
        }
        _uiState.update { it.copy(lastActionBanner = statusPrefix) }
    }

    private fun postToExternalBridge(frame: String) {
        val baseUrl = _uiState.value.externalServerUrl.trimEnd('/')
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val url = URL("$baseUrl/api/raw_frame")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 2500
                    readTimeout = 2500
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
                conn.outputStream.use { it.write(frame.toByteArray(Charsets.UTF_8)) }
                conn.responseCode
                conn.disconnect()
            }
        }
    }

    private fun handleHomeAssistantCommand(action: String, payload: JSONObject) {
        viewModelScope.launch {
            when (action.lowercase()) {
                "start", "clean", "turn_on" -> startWholeHomeClean(deepClean = false)
                "deep_clean" -> startWholeHomeClean(deepClean = true)
                "pause", "play_pause" -> pauseOrResumeCleaning()
                "stop", "turn_off" -> stopCleaning()
                "return_to_base", "dock", "charge" -> returnToDock()
                "locate" -> locateRobotBeep()
                "collect_dust", "empty_bin" -> collectDustNow()
                "spot_clean", "clean_spot" -> spotCleanHere()
                "wake" -> wakeRobotFromStandby()
                "set_fan_speed" -> {
                    val speed = payload.optString("fan_speed", "auto")
                    setSuctionLevel(SuctionLevel.fromWire(speed))
                }
                "clean_rooms" -> {
                    val arr = payload.optJSONArray("rooms")
                    if (arr != null) {
                        val rooms = (0 until arr.length()).map { arr.optInt(it) }.toSet()
                        _uiState.update { it.copy(selectedRoomLabels = rooms) }
                        startSelectedRoomsOrZonesClean()
                    }
                }
                "clean_zones" -> {
                    val arr = payload.optJSONArray("zones")
                    if (arr != null) {
                        val zones = (0 until arr.length()).map { arr.optInt(it) }.toSet()
                        _uiState.update { it.copy(selectedZoneIds = zones) }
                        startSelectedRoomsOrZonesClean()
                    }
                }
            }
        }
    }
}
