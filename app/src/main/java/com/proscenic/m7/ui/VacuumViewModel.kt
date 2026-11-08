package com.proscenic.m7.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.proscenic.m7.protocol.CleanModeType
import com.proscenic.m7.protocol.CleanPath
import com.proscenic.m7.protocol.MapRegion
import com.proscenic.m7.protocol.PathPoint
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
import com.proscenic.m7.simulator.SimulatedVacuumEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

enum class ConnectionMode(val label: String) {
    LOCAL_SERVER("On-Phone Server (:8080/:8081)"),
    LAN_BRIDGE("External LAN Server / HA"),
    SIMULATOR("Interactive Floor Simulator")
}

enum class MapInteractionMode(val label: String) {
    SELECT_ROOMS("Rooms"),
    EDIT_ZONES("Zones & No-Go"),
    PLACED_SPOT("Spot Target"),
    SPLIT_MERGE("Split / Merge")
}

data class VacuumUiState(
    val connectionMode: ConnectionMode = ConnectionMode.LOCAL_SERVER,
    val externalServerUrl: String = "http://192.168.1.10:8080",
    val localLanIp: String = LocalVacuumServer.getLocalLanIpAddress(),
    val serverRunning: Boolean = false,
    val robotSocketConnected: Boolean = false,
    val status: VacuumStatus = VacuumStatus(
        posMm = MapMathDefaultDock,
        isOnline = true
    ),
    val map: VacuumMap = SimulatedVacuumEngine.createInitialHouseMap(),
    val path: CleanPath = CleanPath(),
    val regions: List<MapRegion> = SimulatedVacuumEngine.defaultRegions(),
    val roomNames: Map<Int, String> = SimulatedVacuumEngine.defaultRoomNames,
    val selectedRoomLabels: Set<Int> = emptySet(),
    val selectedZoneIds: Set<Int> = emptySet(),
    val mapInteractionMode: MapInteractionMode = MapInteractionMode.SELECT_ROOMS,
    val activeZoneDrawKind: ZoneKind = ZoneKind.CLEAN_NORMAL,
    val placedSpotTargetMm: PointMm = PointMm(0, 0),
    val placedSpotDeepClean: Boolean = false,
    val yShapedMopEnabled: Boolean = false,
    val mergeFirstRoomLabel: Int? = null,
    val schedules: List<ScheduleEntry> = SimulatedVacuumEngine.defaultSchedules(),
    val quietHours: QuietHoursConfig = QuietHoursConfig(enabled = true, startHour = 23, startMinute = 0, endHour = 7, endMinute = 0),
    val rehomeSteps: List<RehomeClient.StepLog> = emptyList(),
    val rehomeInProgress: Boolean = false,
    val rehomeStatusBanner: String? = null,
    val wireTraces: List<LocalVacuumServer.WireTraceEntry> = emptyList(),
    val lastActionBanner: String = "Local server active on :8080 (HTTP) & :8081 (TCP Gateway)",
    // ColorOS 17 Living Flow Design accessibility & visual preferences
    val darkThemeOverride: Boolean? = true, // Default dark OLED theme; null = follow system
    val reduceTransparency: Boolean = false,
    val reduceMotion: Boolean = false,
    val highContrast: Boolean = false
)

private val MapMathDefaultDock = SimulatedVacuumEngine.createInitialHouseMap().chargeHandlePos ?: PointMm(-2400, -1900)

class VacuumViewModel : ViewModel() {

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

    private val simWaypoints = mutableListOf<PointMm>()
    private var simTickerJob: Job? = null

    private val localServer = LocalVacuumServer(
        httpPort = 8080,
        gatewayPort = 8081,
        onStatusReceived = { status ->
            _uiState.update { it.copy(status = status, robotSocketConnected = true) }
        },
        onMapReceived = { newMap ->
            _uiState.update { state ->
                val mergedRegions = if (newMap.areas.isNotEmpty()) newMap.areas else state.regions
                state.copy(map = newMap.copy(areas = mergedRegions), regions = mergedRegions)
            }
        },
        onPathReceived = { newPath ->
            _uiState.update { it.copy(path = newPath) }
        },
        onZonesReceived = { zones ->
            _uiState.update { state ->
                state.copy(regions = zones, map = state.map.copy(areas = zones))
            }
        },
        onSchedulesReceived = { scheds ->
            _uiState.update { it.copy(schedules = scheds) }
        },
        onWireTrace = { entry ->
            _uiState.update { state ->
                state.copy(wireTraces = (listOf(entry) + state.wireTraces).take(80))
            }
        },
        onExternalHaCommand = { action, payload ->
            handleHomeAssistantCommand(action, payload)
        }
    )

    init {
        startEmbeddedServer()
        startSimulatorLoop()
    }

    override fun onCleared() {
        super.onCleared()
        localServer.stop()
        simTickerJob?.cancel()
    }

    private fun startEmbeddedServer() {
        localServer.latestStatusSnapshot = _uiState.value.status
        localServer.latestMapSnapshot = _uiState.value.map
        localServer.start()
        viewModelScope.launch {
            localServer.isRobotConnected.collect { connected ->
                _uiState.update { it.copy(robotSocketConnected = connected, serverRunning = true) }
            }
        }
    }

    private fun startSimulatorLoop() {
        simTickerJob?.cancel()
        simTickerJob = viewModelScope.launch {
            while (isActive) {
                delay(350L)
                val state = _uiState.value
                // Run local animation whenever no physical robot TCP socket is overriding telemetry
                if (!state.robotSocketConnected && (state.status.mode == "sweep" || state.status.mode == "backcharge")) {
                    val dockMm = state.map.chargeHandlePos ?: MapMathDefaultDock
                    val (nextStatus, nextPath) = SimulatedVacuumEngine.stepRobotAlongWaypoints(
                        status = state.status,
                        path = state.path,
                        waypoints = simWaypoints,
                        dockMm = dockMm
                    )
                    localServer.latestStatusSnapshot = nextStatus
                    localServer.latestPathSnapshot = nextPath
                    _uiState.update { it.copy(status = nextStatus, path = nextPath) }
                }
            }
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
    // Core Vacuum Controls (Channel B 21005, 21017, 21012, 21022, 21024, 21020)
    // =========================================================================

    fun startWholeHomeClean(deepClean: Boolean = false) {
        val state = _uiState.value
        val frame = if (deepClean) {
            ProscenicProtocol.buildStartDeepClean(yShapedMop = state.yShapedMopEnabled)
        } else {
            ProscenicProtocol.buildStartSmartClean(yShapedMop = state.yShapedMopEnabled)
        }
        dispatchSingleFrame(frame, if (deepClean) "Started Deep Whole-Home Clean (21005 depthTotalClean)" else "Started Whole-Home Clean (21005 smartClean)")

        // Populate simulator waypoints across whole house
        simWaypoints.clear()
        simWaypoints.addAll(
            SimulatedVacuumEngine.generateZigZagWaypoints(
                minXMm = -2500,
                minYMm = -2000,
                maxXMm = 2400,
                maxYMm = 1900,
                forbiddenRegions = state.regions,
                spacingMm = if (deepClean) 160 else 280
            )
        )
        _uiState.update {
            it.copy(
                status = it.status.copy(mode = "sweep", subMode = if (deepClean) "depth" else "smartClean"),
                path = CleanPath(pathId = System.currentTimeMillis())
            )
        }
    }

    fun pauseOrResumeCleaning() {
        val currentMode = _uiState.value.status.mode
        if (currentMode == "pause") {
            val frame = ProscenicProtocol.buildPauseContinueStop("continue")
            dispatchSingleFrame(frame, "Resumed cleaning (21017 continue)")
            _uiState.update { it.copy(status = it.status.copy(mode = "sweep")) }
        } else {
            val frame = ProscenicProtocol.buildPauseContinueStop("pause")
            dispatchSingleFrame(frame, "Paused cleaning (21017 pause)")
            _uiState.update { it.copy(status = it.status.copy(mode = "pause")) }
        }
    }

    fun stopCleaning() {
        val frame = ProscenicProtocol.buildPauseContinueStop("stop")
        dispatchSingleFrame(frame, "Stopped cleaning job (21017 stop)")
        simWaypoints.clear()
        _uiState.update { it.copy(status = it.status.copy(mode = "idle")) }
    }

    fun returnToDock() {
        val frame = ProscenicProtocol.buildReturnToDock("start")
        dispatchSingleFrame(frame, "Returning to charging dock (21012 start)")
        val dock = _uiState.value.map.chargeHandlePos ?: MapMathDefaultDock
        simWaypoints.clear()
        simWaypoints.add(dock)
        _uiState.update { it.copy(status = it.status.copy(mode = "backcharge")) }
    }

    fun setSuctionLevel(level: SuctionLevel) {
        val frame = ProscenicProtocol.buildSetSuction(level)
        dispatchSingleFrame(frame, "Set suction to ${level.label} (21022 ${level.wireValue})")
        _uiState.update { it.copy(status = it.status.copy(suction = level)) }
    }

    fun setWaterLevel(level: Int) {
        val clamped = level.coerceIn(1, 4)
        val frame = ProscenicProtocol.buildSetWaterLevel(clamped)
        dispatchSingleFrame(frame, "Set mop water level to $clamped/4 (21024 setWaterPump)")
        _uiState.update { it.copy(status = it.status.copy(waterLevel = clamped)) }
    }

    fun setSpeakerVolume(vol: Int) {
        val clamped = vol.coerceIn(0, 10)
        val frame = ProscenicProtocol.buildSetVolume(clamped)
        dispatchSingleFrame(frame, "Set speaker volume to ${clamped * 10}% (21024 setVolume=$clamped)")
        _uiState.update { it.copy(status = it.status.copy(volume = clamped)) }
    }

    fun collectDustNow() {
        val frame = ProscenicProtocol.buildStartDustCollection()
        dispatchSingleFrame(frame, "Started auto-empty dust station (21024 startDustCenter)")
    }

    fun locateRobotBeep() {
        val frame = ProscenicProtocol.buildRemoteControl(3010)
        dispatchSingleFrame(frame, "Playing locate sound on vacuum (21020 ctrlCode=3010)")
    }

    fun spotCleanHere() {
        val frame = ProscenicProtocol.buildRemoteControl(3001)
        dispatchSingleFrame(frame, "Started 1.5m spot clean at current pose (21020 ctrlCode=3001)")
        val pos = _uiState.value.status.posMm
        simWaypoints.clear()
        simWaypoints.addAll(
            SimulatedVacuumEngine.generateZigZagWaypoints(
                minXMm = pos.xMm - 750,
                minYMm = pos.yMm - 750,
                maxXMm = pos.xMm + 750,
                maxYMm = pos.yMm + 750,
                forbiddenRegions = _uiState.value.regions,
                spacingMm = 180
            )
        )
        _uiState.update { it.copy(status = it.status.copy(mode = "sweep", subMode = "curpoint")) }
    }

    /**
     * Manual D-pad remote control (`infoType: 21020`, `ctrlCode` 3005..3008, 4001, 4000).
     */
    fun sendRemoteControlStep(ctrlCode: Int) {
        val frame = ProscenicProtocol.buildRemoteControl(ctrlCode)
        localServer.sendFrameToRobot(frame)

        // Update simulated pose for immediate visual feedback on the 2D map
        _uiState.update { state ->
            val s = state.status
            val headingRad = s.phiMrad / 1000.0
            val nextStatus = when (ctrlCode) {
                3005 -> { // Forward +0.3 m/s
                    val nx = (s.posMm.xMm + cos(headingRad) * 120).roundToInt()
                    val ny = (s.posMm.yMm + sin(headingRad) * 120).roundToInt()
                    s.copy(mode = "rfctrl", posMm = PointMm(nx, ny))
                }
                3006 -> { // Backward -0.1 m/s
                    val nx = (s.posMm.xMm - cos(headingRad) * 80).roundToInt()
                    val ny = (s.posMm.yMm - sin(headingRad) * 80).roundToInt()
                    s.copy(mode = "rfctrl", posMm = PointMm(nx, ny))
                }
                3007 -> s.copy(mode = "rfctrl", phiMrad = (s.phiMrad + 350) % 6283)
                3008 -> s.copy(mode = "rfctrl", phiMrad = (s.phiMrad - 350 + 6283) % 6283)
                4000 -> s.copy(mode = "idle")
                else -> s
            }
            val nextPoints = if (ctrlCode in 3005..3006) {
                (state.path.points + PathPoint(nextStatus.posMm.xMm, nextStatus.posMm.yMm, tagX = 0, tagY = 2)).takeLast(600)
            } else state.path.points
            state.copy(
                status = nextStatus,
                path = state.path.copy(points = nextPoints, totalPoints = nextPoints.size)
            )
        }
    }

    // =========================================================================
    // Room Selection, Zone Editor & Placed Spot Clean (21003, 21023 + 21005, 21030)
    // =========================================================================

    fun toggleRoomSelection(roomLabel: Int) {
        val state = _uiState.value
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
                val frame = ProscenicProtocol.buildRoomMerge(state.map.autoAreaId, first, roomLabel)
                val mergedMap = SimulatedVacuumEngine.mergeRooms(state.map, first, roomLabel)
                localServer.latestMapSnapshot = mergedMap
                dispatchSingleFrame(frame, "Merged Room #$first and Room #$roomLabel (21030 merge)")
                _uiState.update {
                    it.copy(map = mergedMap, mergeFirstRoomLabel = null)
                }
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
        val targetRoom = state.mergeFirstRoomLabel ?: state.selectedRoomLabels.firstOrNull() ?: 1
        val frame = ProscenicProtocol.buildRoomSplit(state.map.autoAreaId, targetRoom, p1Mm, p2Mm)
        val splitMap = SimulatedVacuumEngine.splitRoom(state.map, targetRoom, p1Mm, p2Mm)
        localServer.latestMapSnapshot = splitMap
        dispatchSingleFrame(frame, "Split Room #$targetRoom along line (${p1Mm.xMm},${p1Mm.yMm})->(${p2Mm.xMm},${p2Mm.yMm}) (21030 split)")
        _uiState.update { it.copy(map = splitMap, mergeFirstRoomLabel = null) }
    }

    fun resetRoomSegmentation() {
        val state = _uiState.value
        val frame = ProscenicProtocol.buildRoomResetSegmentation(state.map.autoAreaId)
        val freshMap = SimulatedVacuumEngine.createInitialHouseMap(state.regions)
        localServer.latestMapSnapshot = freshMap
        dispatchSingleFrame(frame, "Reset room watershed segmentation (21030 reset)")
        _uiState.update { it.copy(map = freshMap, mergeFirstRoomLabel = null, selectedRoomLabels = emptySet()) }
    }

    /**
     * Adds a user-drawn rectangular zone (`CLEAN_NORMAL`, `CLEAN_DEEP`, `NO_GO`, `NO_MOP`)
     * in world millimetres and pushes the full list via `infoType: 21003` (`SetAreaTactics`).
     */
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
        pushUpdatedRegions(updatedRegions, "Saved ${kind.label} '$name' (21003 SetAreaTactics)")
    }

    fun deleteZone(zoneId: Int) {
        val updated = _uiState.value.regions.filterNot { it.id == zoneId }
        pushUpdatedRegions(updated, "Removed zone #$zoneId (21003 SetAreaTactics)")
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
        val mapId = _uiState.value.map.mapId
        val frame = ProscenicProtocol.buildSetAreaTactics(mapId, regions)
        dispatchSingleFrame(frame, banner)
        _uiState.update { state ->
            val updatedMap = state.map.copy(areas = regions)
            localServer.latestMapSnapshot = updatedMap
            state.copy(
                regions = regions,
                map = updatedMap,
                selectedZoneIds = state.selectedZoneIds.intersect(regions.map { it.id }.toSet())
            )
        }
    }

    /**
     * Cleans selected rooms (`segmentId`) and/or selected saved zones (`cleanId`) using the
     * proven 2-step `21023` (`ActiveRegions`) -> `21005` (`appointClean`) sequence (`ZONE_CLEAN.md`).
     */
    fun startSelectedRoomsOrZonesClean() {
        val state = _uiState.value
        val roomIds = state.selectedRoomLabels.sorted()
        val zoneIds = state.selectedZoneIds.sorted().ifEmpty {
            // If in zone mode and none explicitly checked, pick all non-forbidden clean zones
            if (state.mapInteractionMode == MapInteractionMode.EDIT_ZONES) {
                state.regions.filter { !it.kind.isForbidden }.map { it.id }
            } else emptyList()
        }

        if (roomIds.isEmpty() && zoneIds.isEmpty()) {
            startWholeHomeClean(deepClean = false)
            return
        }

        val frames = ProscenicProtocol.buildZoneOrRoomCleanSequence(
            storedZoneIds = zoneIds,
            segmentRoomLabels = roomIds,
            includeStoredForbiddenZones = true
        )
        localServer.sendFramesToRobot(frames)

        // Generate simulator waypoints covering the chosen rooms/zones
        simWaypoints.clear()
        val targetRegions = state.regions.filter { it.id in zoneIds && !it.kind.isForbidden }
        for (reg in targetRegions) {
            val minX = reg.verticesMm.minOf { it.xMm }
            val maxX = reg.verticesMm.maxOf { it.xMm }
            val minY = reg.verticesMm.minOf { it.yMm }
            val maxY = reg.verticesMm.maxOf { it.yMm }
            simWaypoints.addAll(
                SimulatedVacuumEngine.generateZigZagWaypoints(minX, minY, maxX, maxY, state.regions)
            )
        }
        for (roomLabel in roomIds) {
            val centroid = state.map.roomCentroidsMm[roomLabel] ?: continue
            simWaypoints.addAll(
                SimulatedVacuumEngine.generateZigZagWaypoints(
                    minXMm = centroid.xMm - 1100,
                    minYMm = centroid.yMm - 900,
                    maxXMm = centroid.xMm + 1100,
                    maxYMm = centroid.yMm + 900,
                    forbiddenRegions = state.regions
                )
            )
        }

        _uiState.update {
            it.copy(
                status = it.status.copy(mode = "sweep", subMode = "appointClean"),
                path = CleanPath(pathId = System.currentTimeMillis()),
                lastActionBanner = "Dispatched 21023 ActiveRegions (rooms=$roomIds, zones=$zoneIds) + 21005 appointClean"
            )
        }
    }

    /**
     * Dispatches a Placed Spot Clean (`GLOSSARY.md` Placed spot cleaning):
     * Sends a 1.5m square around `placedSpotTargetMm` as an inline `extraAreas` `mode:"point"`
     * region in `21023` (with `-3` keeping stored no-go zones), followed by `21005 {"mode":"appointClean"}`.
     */
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
        simWaypoints.clear()
        simWaypoints.addAll(
            SimulatedVacuumEngine.generateZigZagWaypoints(
                minXMm = c.xMm - 750,
                minYMm = c.yMm - 750,
                maxXMm = c.xMm + 750,
                maxYMm = c.yMm + 750,
                forbiddenRegions = state.regions,
                spacingMm = if (state.placedSpotDeepClean) 140 else 220
            )
        )

        _uiState.update {
            it.copy(
                status = it.status.copy(mode = "sweep", subMode = "point"),
                path = CleanPath(pathId = System.currentTimeMillis()),
                lastActionBanner = "Dispatched Placed Spot Clean at (${c.xMm}, ${c.yMm}) mm via 21023 + 21005 appointClean"
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
        pushSchedulesToRobot(current, _uiState.value.quietHours, "Saved schedule '${entry.label}' (${entry.formattedTime}) via 21001")
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
        // Per FUNC_COMMANDS.md §7.1: 21001 always replies ok; follow with 21002 to verify stored list
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
    // SoftAP UDP 7913 Re-homing Wizard
    // =========================================================================

    fun runRehomeWizard(
        robotApIp: String,
        serverHostIp: String,
        wifiSsid: String,
        wifiPassword: String,
        userId: String,
        dryRun: Boolean
    ) {
        if (_uiState.value.rehomeInProgress) return
        _uiState.update {
            it.copy(
                rehomeInProgress = true,
                rehomeSteps = emptyList(),
                rehomeStatusBanner = if (dryRun) "Running dry-run UDP 7913 provisioning..." else "Contacting vacuum on $robotApIp:7913..."
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
                dryRun = dryRun,
                onStepProgress = { stepLog ->
                    _uiState.update { s -> s.copy(rehomeSteps = s.rehomeSteps + stepLog) }
                }
            )
            _uiState.update { s ->
                s.copy(
                    rehomeInProgress = false,
                    rehomeSteps = result.steps,
                    rehomeStatusBanner = if (result.success) {
                        "Re-home succeeded (SN=${result.robotSerialNumber}, UDP port=${result.discoveredPort}). Reconnect phone to '$wifiSsid' — the vacuum will dial $serverHostIp:8081!"
                    } else {
                        "Error: ${result.errorMessage}"
                    }
                )
            }
        }
    }

    // =========================================================================
    // Dispatch Helpers & Home Assistant Bridge Handler
    // =========================================================================

    private fun dispatchSingleFrame(frame: String, banner: String) {
        val state = _uiState.value
        localServer.sendFrameToRobot(frame)
        if (state.connectionMode == ConnectionMode.LAN_BRIDGE) {
            postToExternalBridge(frame)
        }
        _uiState.update { it.copy(lastActionBanner = banner) }
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
