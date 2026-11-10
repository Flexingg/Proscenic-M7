package com.proscenic.m7.protocol

import org.json.JSONArray
import org.json.JSONObject
import java.util.TimeZone
import kotlin.math.roundToInt

/**
 * Wire protocol encoder and decoder for the Proscenic M7 Pro (`LS_S6` / `network_proxy`).
 *
 * Implements:
 * - Channel B (`#\t#` = `0x23 0x09 0x23` delimited raw TCP frames) with the required
 *   double-nested `{"encrypt":0,"data":{"infoType":N,"data":{...},"dInfo":{"ts":"...","userId":"..."}}}` envelope
 *   (`CHANNEL_B_INBOUND.md` & `PROTOCOL.md` §B).
 * - Channel A (`cleanPack/uploadEvents`, `cleanPack/response`, `cleanPack/register`, `cleanPack/binding`,
 *   `cleanPack/getSockAddr`, `cleanPack/sync`) payload decoding & response construction.
 */
object ProscenicProtocol {

    const val FRAME_DELIMITER = "#\t#"
    const val DEFAULT_USER_ID = "local_android"

    /**
     * Wraps an inner `<MESSAGE>` (`{"infoType":N,"data":{...},"dInfo":{...}}`) in the outer
     * `{"encrypt":0,"data":<MESSAGE>}#\t#` Channel-B envelope (`PROTOCOL.md` §B).
     */
    fun buildChannelBFrame(
        infoType: Int,
        data: JSONObject = JSONObject(),
        userId: String = DEFAULT_USER_ID,
        ts: String = System.currentTimeMillis().toString(),
        includeDInfo: Boolean = true
    ): String {
        val inner = JSONObject().apply {
            put("infoType", infoType)
            put("data", data)
            if (includeDInfo) {
                put("dInfo", JSONObject().apply {
                    put("ts", ts)
                    put("userId", userId)
                })
            }
        }
        val outer = JSONObject().apply {
            put("encrypt", 0)
            put("data", inner)
        }
        return outer.toString() + FRAME_DELIMITER
    }

    /**
     * Mandatory keepalive Pong (`infoType: 21006`) with `"isExistConnect": true`.
     * Without `"isExistConnect": true`, the robot never uploads `20002` maps or live status (`FUNC_MAP.md` §2.2).
     */
    fun buildKeepalivePong(announceAppOnline: Boolean = true): String {
        val data = JSONObject()
        if (announceAppOnline) {
            data.put("isExistConnect", true)
        }
        return buildChannelBFrame(infoType = 21006, data = data, includeDInfo = false)
    }

    // =========================================================================
    // Cleaning & Navigation Commands (FUNC_COMMANDS.md §1)
    // =========================================================================

    /**
     * Whole-home smart clean (`infoType: 21005`, `mode: "smartClean"`).
     * Note: Overwrites CleanArea shared memory with the whole-map total region.
     */
    fun buildStartSmartClean(): String {
        val data = JSONObject().put("mode", "smartClean")
        return buildChannelBFrame(infoType = 21005, data = data)
    }

    /**
     * Deep whole-home clean (2x grid pattern, `infoType: 21005`, `mode: "depthTotalClean"`).
     */
    fun buildStartDeepClean(): String {
        val data = JSONObject().put("mode", "depthTotalClean")
        return buildChannelBFrame(infoType = 21005, data = data)
    }

    /**
     * Smart auto-segmented room clean (`infoType: 21005`, `mode: "smartAreaClean"`).
     */
    fun buildStartSmartAreaClean(): String {
        val data = JSONObject().put("mode", "smartAreaClean")
        return buildChannelBFrame(infoType = 21005, data = data)
    }

    /**
     * Starts cleaning the regions currently loaded in CleanArea shared memory by `21023` (`ZONE_CLEAN.md`).
     */
    fun buildStartAppointClean(): String {
        val data = JSONObject().put("mode", "appointClean")
        return buildChannelBFrame(infoType = 21005, data = data)
    }

    /**
     * Pause, resume, or stop the current cleaning job (`infoType: 21017`, `cmd: "pause"|"continue"|"stop"`).
     */
    fun buildPauseContinueStop(cmd: String): String {
        require(cmd in setOf("pause", "continue", "stop")) { "Invalid 21017 cmd: $cmd" }
        val data = JSONObject().put("cmd", cmd)
        return buildChannelBFrame(infoType = 21017, data = data)
    }

    /**
     * Return to charging dock (`infoType: 21012`, `cmd: "start"|"pause"|"stop"`).
     */
    fun buildReturnToDock(cmd: String = "start"): String {
        val data = JSONObject().put("cmd", cmd)
        return buildChannelBFrame(infoType = 21012, data = data)
    }

    // =========================================================================
    // Zones, Rooms & Placed Spot Cleaning (FUNC_MAP.md §4-§7, ZONE_CLEAN.md)
    // =========================================================================

    fun serializeRegion(region: MapRegion): JSONObject {
        val vertsArray = JSONArray()
        for (pt in region.snappedVerticesMm) {
            vertsArray.put(JSONArray().put(pt.xMm).put(pt.yMm))
        }
        return JSONObject().apply {
            put("vertexs", vertsArray)
            put("active", region.kind.activeWire)
            put("forbidType", region.kind.forbidTypeWire)
            put("cleanType", region.cleanType.wireValue)
            put("workNoisy", region.suction.wireValue)
            put("name", region.safeName)
            put("tag", region.safeTag)
            put("id", region.id)
            put("mode", region.safeMode)
        }
    }

    fun parseRegion(obj: JSONObject, fallbackId: Int = 1): MapRegion {
        val vertsJson = obj.optJSONArray("vertexs") ?: JSONArray()
        val points = mutableListOf<PointMm>()
        for (i in 0 until vertsJson.length()) {
            val pair = vertsJson.optJSONArray(i)
            if (pair != null && pair.length() >= 2) {
                points.add(PointMm(pair.optInt(0), pair.optInt(1)))
            }
        }
        val active = obj.optString("active", "normal")
        val forbidType = obj.optString("forbidType", "all")
        val mode = obj.optString("mode", "area")
        val kind = ZoneKind.fromWire(active, forbidType, mode)
        val cleanType = CleanModeType.fromWire(obj.optString("cleanType", "sweepOnly"))
        val suction = SuctionLevel.fromWire(obj.optString("workNoisy", "auto"))
        val id = obj.optInt("id", fallbackId)
        val name = obj.optString("name", "Zone $id")
        val tag = obj.optString("tag", name.lowercase())
        return MapRegion(
            id = id,
            name = name,
            tag = tag,
            kind = kind,
            verticesMm = points,
            cleanType = cleanType,
            suction = suction
        )
    }

    /**
     * Replaces the robot's stored zone list (`infoType: 21003` SetAreaTactics, `FUNC_MAP.md` §5.1).
     */
    fun buildSetAreaTactics(mapId: Long, regions: List<MapRegion>): String {
        val arr = JSONArray()
        regions.forEach { arr.put(serializeRegion(it)) }
        val data = JSONObject().apply {
            put("mapId", mapId)
            put("value", arr)
        }
        return buildChannelBFrame(infoType = 21003, data = data)
    }

    /**
     * Queries the robot's stored zone list (`infoType: 21004` GetAreaTactics, `FUNC_MAP.md` §5.2).
     */
    fun buildGetAreaTactics(): String =
        buildChannelBFrame(infoType = 21004, data = JSONObject())

    /**
     * Builds the `21023` (`ActiveRegions`) + `21005` (`appointClean`) two-step command sequence
     * required to start cleaning specific stored zones, ad-hoc polygons, placed spots, or segmented rooms
     * (`ZONE_CLEAN.md`).
     *
     * Note: `-3` in `cleanId` keeps stored no-go / no-mop zones active during the job.
     */
    fun buildZoneOrRoomCleanSequence(
        storedZoneIds: List<Int> = emptyList(),
        extraAreas: List<MapRegion> = emptyList(),
        segmentRoomLabels: List<Int> = emptyList(),
        includeStoredForbiddenZones: Boolean = true
    ): List<String> {
        val cleanIds = JSONArray()
        if (includeStoredForbiddenZones) {
            cleanIds.put(-3)
        }
        storedZoneIds.forEach { cleanIds.put(it) }

        val data21023 = JSONObject().apply {
            put("cleanId", cleanIds)
            if (extraAreas.isNotEmpty()) {
                val extras = JSONArray()
                extraAreas.forEach { extras.put(serializeRegion(it)) }
                put("extraAreas", extras)
            }
            if (segmentRoomLabels.isNotEmpty()) {
                val segs = JSONArray()
                segmentRoomLabels.forEach { segs.put(it) }
                put("segmentId", segs)
            }
        }

        val step1Select = buildChannelBFrame(infoType = 21023, data = data21023)
        val step2Start = buildStartAppointClean()
        return listOf(step1Select, step2Start)
    }

    /**
     * Builds a room segmentation edit command (`infoType: 21030`, `FUNC_MAP.md` §7):
     * - `merge`: merges `pixel1` and `pixel2`
     * - `split`: splits room `pixel` along line `p1..p2` (in world mm)
     * - `reset`: re-runs watershed room segmentation on the current map
     */
    fun buildRoomMerge(autoAreaId: Int, roomLabel1: Int, roomLabel2: Int): String {
        val data = JSONObject().apply {
            put("autoAreaId", autoAreaId)
            put("operate", "merge")
            put("extra", JSONObject().apply {
                put("pixel1", roomLabel1)
                put("pixel2", roomLabel2)
            })
        }
        return buildChannelBFrame(infoType = 21030, data = data)
    }

    fun buildRoomSplit(autoAreaId: Int, roomLabel: Int, p1Mm: PointMm, p2Mm: PointMm): String {
        val data = JSONObject().apply {
            put("autoAreaId", autoAreaId)
            put("operate", "split")
            put("extra", JSONObject().apply {
                put("pixel", roomLabel)
                put("p1", JSONArray().put(p1Mm.xMm).put(p1Mm.yMm))
                put("p2", JSONArray().put(p2Mm.xMm).put(p2Mm.yMm))
            })
        }
        return buildChannelBFrame(infoType = 21030, data = data)
    }

    fun buildRoomResetSegmentation(autoAreaId: Int): String {
        val data = JSONObject().apply {
            put("autoAreaId", autoAreaId)
            put("operate", "reset")
        }
        return buildChannelBFrame(infoType = 21030, data = data)
    }

    // =========================================================================
    // Schedules & Quiet Hours (FUNC_COMMANDS.md §7)
    // =========================================================================

    /**
     * Builds `infoType: 21001` (`SetTimeTactics`) replacing the robot's schedule & quiet-hours table.
     * Automatically splits overnight quiet-hour windows (e.g. 23:00 -> 07:00) into two same-day entries
     * (`23:00->23:59:59` and `00:00->07:00`) as required by `time_server_tactics` (`FUNC_COMMANDS.md` §7.5).
     */
    fun buildSetSchedules(
        schedules: List<ScheduleEntry>,
        quietHours: QuietHoursConfig? = null,
        timeZoneHours: Int = defaultTimeZoneOffsetHours()
    ): String {
        val valueArray = JSONArray()

        for (entry in schedules) {
            val periodArr = JSONArray()
            entry.daysOfWeek.sorted().forEach { periodArr.put(it.coerceIn(0, 6)) }

            val cleanIdArr = JSONArray()
            val ids = entry.cleanIds.ifEmpty { listOf(-1, -3) }
            ids.forEach { cleanIdArr.put(it) }

            val obj = JSONObject().apply {
                put("active", true)
                put("unlock", entry.enabled)
                put("startTime", entry.startTimeSecFromMidnight)
                put("endTime", 0)
                put("period", periodArr)
                put("workNoisy", entry.suction.wireValue)
                put("cleanNum", entry.passes.coerceIn(1, 3))
                put("cleanId", cleanIdArr)
            }
            valueArray.put(obj)
        }

        if (quietHours != null) {
            val baseEpochDaySec = 1704067200L - (timeZoneHours * 3600L) // Reference local midnight in UTC
            val startSec = quietHours.startHour * 3600L + quietHours.startMinute * 60L
            val endSec = quietHours.endHour * 3600L + quietHours.endMinute * 60L

            if (startSec <= endSec) {
                valueArray.put(JSONObject().apply {
                    put("active", false)
                    put("unlock", quietHours.enabled)
                    put("startTime", baseEpochDaySec + startSec)
                    put("endTime", baseEpochDaySec + endSec)
                    put("period", JSONArray())
                })
            } else {
                // Overnight window split across midnight into two same-day entries
                valueArray.put(JSONObject().apply {
                    put("active", false)
                    put("unlock", quietHours.enabled)
                    put("startTime", baseEpochDaySec + startSec)
                    put("endTime", baseEpochDaySec + 86399L)
                    put("period", JSONArray())
                })
                valueArray.put(JSONObject().apply {
                    put("active", false)
                    put("unlock", quietHours.enabled)
                    put("startTime", baseEpochDaySec)
                    put("endTime", baseEpochDaySec + endSec)
                    put("period", JSONArray())
                })
            }
        }

        val data = JSONObject().apply {
            put("timeZone", timeZoneHours)
            put("value", valueArray)
        }
        return buildChannelBFrame(infoType = 21001, data = data)
    }

    fun buildGetSchedules(): String =
        buildChannelBFrame(infoType = 21002, data = JSONObject())

    fun parseSchedulesFrom21002(dataObj: JSONObject): List<ScheduleEntry> {
        val arr = dataObj.optJSONArray("value") ?: return emptyList()
        val result = mutableListOf<ScheduleEntry>()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            if (!item.optBoolean("active", true)) continue // Skip quiet-hours windows
            val startTime = item.optInt("startTime", 32400)
            val hour = (startTime / 3600) % 24
            val minute = (startTime % 3600) / 60
            val periodJson = item.optJSONArray("period") ?: JSONArray()
            val days = mutableSetOf<Int>()
            for (j in 0 until periodJson.length()) {
                days.add(periodJson.optInt(j))
            }
            val cleanIdJson = item.optJSONArray("cleanId") ?: JSONArray()
            val cleanIds = mutableListOf<Int>()
            for (j in 0 until cleanIdJson.length()) {
                cleanIds.add(cleanIdJson.optInt(j))
            }
            result.add(
                ScheduleEntry(
                    id = "sched_$i",
                    hour = hour,
                    minute = minute,
                    daysOfWeek = days,
                    enabled = item.optBoolean("unlock", true),
                    suction = SuctionLevel.fromWire(item.optString("workNoisy", "auto")),
                    passes = item.optInt("cleanNum", 1),
                    cleanIds = cleanIds.ifEmpty { listOf(-1, -3) }
                )
            )
        }
        return result
    }

    // =========================================================================
    // Suction, Water, Volume, Dust Station, Remote Steering & Map Queries
    // =========================================================================

    fun buildSetSuction(level: SuctionLevel): String {
        val data = JSONObject().put("cmd", level.wireValue)
        return buildChannelBFrame(infoType = 21022, data = data)
    }

    fun buildSetVolume(volume0To10: Int): String {
        val clamped = volume0To10.coerceIn(0, 10)
        val data = JSONObject().put("cmd", "setVolume").put("value", clamped)
        return buildChannelBFrame(infoType = 21024, data = data)
    }

    fun buildSetLedSwitch(enabled: Boolean): String {
        val data = JSONObject().put("cmd", "setledswitch").put("value", if (enabled) 1 else 0)
        return buildChannelBFrame(infoType = 21024, data = data)
    }

    fun buildSetLidarCollision(enabled: Boolean): String {
        val data = JSONObject().put("cmd", "setLidarCollision").put("value", if (enabled) 1 else 0)
        return buildChannelBFrame(infoType = 21024, data = data)
    }

    fun buildStartDustCollection(): String {
        val data = JSONObject().put("cmd", "startDustCenter").put("value", 0)
        return buildChannelBFrame(infoType = 21024, data = data)
    }

    fun buildDeleteCurrentMap(): String {
        val data = JSONObject().put("cmd", "delCurMap")
        return buildChannelBFrame(infoType = 21024, data = data)
    }

    /**
     * `infoType: 21020` Remote control / spot clean / locate / mute toggle (`FUNC_COMMANDS.md` §2-§3).
     * Note: `21020` sends no reply on Channel B.
     * - `3001`: Spot clean at current position
     * - `3005`: Forward (+0.3 m/s)
     * - `3006`: Backward (-0.1 m/s)
     * - `3007`: Rotate Left (+1.0 rad/s)
     * - `3008`: Rotate Right (-1.0 rad/s)
     * - `4001`: Stop moving (stay in manual mode)
     * - `4000`: Exit manual control mode
     * - `3010`: Locate robot (start beep), `3011`: Stop beep
     * - `3022`: Toggle mute
     */
    fun buildRemoteControl(ctrlCode: Int): String {
        val data = JSONObject().put("ctrlCode", ctrlCode)
        return buildChannelBFrame(infoType = 21020, data = data, includeDInfo = false)
    }

    fun buildRequestMapUploadNow(): String =
        buildChannelBFrame(infoType = 20002, data = JSONObject(), includeDInfo = false)

    fun buildRequestCleanPath(startPos: Int = 0): String {
        val data = JSONObject().put("startPos", startPos).put("mask", 0)
        return buildChannelBFrame(infoType = 21011, data = data)
    }

    fun buildRequestStatus(): String =
        buildChannelBFrame(infoType = 20001, data = JSONObject())

    fun buildRequestConsumables(): String =
        buildChannelBFrame(infoType = 21015, data = JSONObject())

    // =========================================================================
    // Robot -> Server Payload Decoders (20001, 20002, 21011)
    // =========================================================================

    fun parseStatus20001(dataObj: JSONObject, previous: VacuumStatus = VacuumStatus()): VacuumStatus {
        val posArr = dataObj.optJSONArray("pos")
        val posMm = if (posArr != null && posArr.length() >= 2) {
            PointMm(posArr.optInt(0), posArr.optInt(1))
        } else {
            previous.posMm
        }
        val errArr = dataObj.optJSONArray("errorState")
        val errors = mutableListOf<Int>()
        if (errArr != null) {
            for (i in 0 until errArr.length()) {
                val code = errArr.optInt(i)
                if (code != 0) errors.add(code)
            }
        }
        val battery = when {
            dataObj.has("elecReal") -> dataObj.optInt("elecReal", previous.battery)
            dataObj.has("elec") -> dataObj.optInt("elec", previous.battery)
            else -> previous.battery
        }
        return previous.copy(
            mode = dataObj.optString("mode", previous.mode),
            subMode = dataObj.optString("subMode", previous.subMode),
            battery = battery.coerceIn(0, 100),
            cleanAreaM2 = dataObj.optDouble("cleanArea", previous.cleanAreaM2.toDouble()).toFloat(),
            cleanTimeSec = dataObj.optInt("cleanTime", previous.cleanTimeSec),
            suction = SuctionLevel.fromWire(dataObj.optString("workNoisy", previous.suction.wireValue)),
            volume = dataObj.optInt("vol", previous.volume).coerceIn(0, 10),
            isMuted = dataObj.optInt("mute", if (previous.isMuted) 1 else 0) == 1,
            ledSwitch = dataObj.optInt("led", if (previous.ledSwitch) 1 else 0) == 1,
            lidarCollisionAvoid = dataObj.optInt("ldAvoidColli", if (previous.lidarCollisionAvoid) 1 else 0) == 1,
            dustCenterFreq = dataObj.optInt("dustCenterFreq", previous.dustCenterFreq),
            posMm = posMm,
            phiMrad = dataObj.optInt("phi", previous.phiMrad),
            errorState = errors,
            isOnline = true,
            lastUpdatedEpochMs = System.currentTimeMillis()
        )
    }

    /**
     * Parses `infoType: 20002` map upload JSON (`MAP.md` & `FUNC_MAP.md` §2).
     */
    fun parseMap20002(dataObj: JSONObject): VacuumMap {
        val sn = dataObj.optString("SN", "LSLDSM7PRO")
        val mapId = dataObj.optLong("mapId", 0L)
        val autoAreaId = dataObj.optInt("autoAreaId", 0)
        val pathId = dataObj.optLong("pathId", 0L)
        val width = dataObj.getInt("width")
        val height = dataObj.getInt("height")
        val resolution = dataObj.optDouble("resolution", 0.05).toFloat()
        val xMin = dataObj.getDouble("x_min").toFloat()
        val yMin = dataObj.getDouble("y_min").toFloat()
        val base64Map = dataObj.getString("map")

        val cells = Lz4Codec.decodeMapBase64Lz4(base64Map, width, height)

        val areasJson = dataObj.optJSONArray("area") ?: JSONArray()
        val regions = mutableListOf<MapRegion>()
        for (i in 0 until areasJson.length()) {
            val regObj = areasJson.optJSONObject(i) ?: continue
            regions.add(parseRegion(regObj, fallbackId = i + 1))
        }

        val chargeState = dataObj.optString("chargeHandleState", "notFind")
        val chargePosArr = dataObj.optJSONArray("chargeHandlePos")
        val chargePos = if (chargePosArr != null && chargePosArr.length() >= 2) {
            PointMm(chargePosArr.optInt(0), chargePosArr.optInt(1))
        } else null
        val chargePhi = if (dataObj.has("chargeHandlePhi")) dataObj.optInt("chargeHandlePhi") else null

        return VacuumMap(
            sn = sn,
            mapId = mapId,
            autoAreaId = autoAreaId,
            pathId = pathId,
            width = width,
            height = height,
            resolution = resolution,
            xMin = xMin,
            yMin = yMin,
            cells = cells,
            areas = regions,
            chargeHandlePos = chargePos,
            chargeHandlePhi = chargePhi,
            chargeHandleState = chargeState
        )
    }

    /**
     * Parses `infoType: 21011` clean path (`posArray` in `FUNC_MAP.md` §3).
     * Supports both `[[x, y], ...]` pairs (firmware `FUN_0045e104`) and flat `[x0, y0, x1, y1, ...]` arrays.
     */
    fun parsePath21011(dataObj: JSONObject): CleanPath {
        val pathId = dataObj.optLong("pathID", dataObj.optLong("pathId", 0L))
        val startPos = dataObj.optInt("startPos", 0)
        val totalPoints = dataObj.optInt("totalPoints", 0)
        val posArray = dataObj.optJSONArray("posArray") ?: JSONArray()
        val points = mutableListOf<PathPoint>()

        if (posArray.length() > 0) {
            val first = posArray.opt(0)
            if (first is JSONArray) {
                for (i in 0 until posArray.length()) {
                    val pair = posArray.optJSONArray(i) ?: continue
                    if (pair.length() >= 2) {
                        val rawX = pair.optInt(0)
                        val rawY = pair.optInt(1)
                        points.add(
                            PathPoint(
                                xMm = MapMath.stripPathTag(rawX),
                                yMm = MapMath.stripPathTag(rawY),
                                tagX = rawX and 3,
                                tagY = rawY and 3
                            )
                        )
                    }
                }
            } else {
                var i = 0
                while (i + 1 < posArray.length()) {
                    val rawX = posArray.optInt(i)
                    val rawY = posArray.optInt(i + 1)
                    points.add(
                        PathPoint(
                            xMm = MapMath.stripPathTag(rawX),
                            yMm = MapMath.stripPathTag(rawY),
                            tagX = rawX and 3,
                            tagY = rawY and 3
                        )
                    )
                    i += 2
                }
            }
        }

        return CleanPath(
            pathId = pathId,
            startPos = startPos,
            totalPoints = totalPoints.coerceAtLeast(points.size),
            points = points
        )
    }

    private fun defaultTimeZoneOffsetHours(): Int {
        val offsetMs = TimeZone.getDefault().getOffset(System.currentTimeMillis())
        return (offsetMs / 3600_000.0).roundToInt()
    }
}
