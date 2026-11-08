package com.proscenic.m7.simulator

import com.proscenic.m7.protocol.CleanModeType
import com.proscenic.m7.protocol.CleanPath
import com.proscenic.m7.protocol.Lz4Codec
import com.proscenic.m7.protocol.MapMath
import com.proscenic.m7.protocol.MapRegion
import com.proscenic.m7.protocol.PathPoint
import com.proscenic.m7.protocol.PointMm
import com.proscenic.m7.protocol.ProscenicProtocol
import com.proscenic.m7.protocol.ScheduleEntry
import com.proscenic.m7.protocol.SuctionLevel
import com.proscenic.m7.protocol.VacuumMap
import com.proscenic.m7.protocol.VacuumStatus
import com.proscenic.m7.protocol.ZoneKind
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * High-fidelity local floorplan & SLAM path simulator for the Proscenic M7 Pro.
 *
 * Generates real `infoType: 20002` LZ4-compressed occupancy grids (with 4 segmented rooms,
 * interior doors, furniture obstacles, and charging dock) so the 2D floor map, room selector,
 * zone editor, placed spot cleaning, room merge/split, and schedules can be interactively
 * tested and verified end-to-end.
 */
object SimulatedVacuumEngine {

    const val GRID_WIDTH = 120   // 6.0 m wide at 0.05 m/cell
    const val GRID_HEIGHT = 100  // 5.0 m tall at 0.05 m/cell
    const val RESOLUTION = 0.05f
    const val X_MIN = -3.05f
    const val Y_MIN = -2.55f

    val defaultRoomNames = mapOf(
        1 to "Living Room",
        2 to "Kitchen",
        3 to "Bedroom",
        4 to "Hallway & Study",
        5 to "Dining Nook"
    )

    /**
     * Builds a realistic 4-room house occupancy grid (`120 × 100` cells) and runs it through
     * the actual `infoType: 20002` Base64 + LZ4 encode/decode pipeline.
     */
    fun createInitialHouseMap(customRegions: List<MapRegion> = defaultRegions()): VacuumMap {
        val raw = ByteArray(GRID_WIDTH * GRID_HEIGHT) { 0x7F.toByte() } // Unknown outside house

        fun setCell(c: Int, r: Int, v: Int) {
            if (c in 0 until GRID_WIDTH && r in 0 until GRID_HEIGHT) {
                raw[r * GRID_WIDTH + c] = v.toByte()
            }
        }

        fun fillRect(c0: Int, r0: Int, c1: Int, r1: Int, v: Int) {
            for (r in r0..r1) {
                for (c in c0..c1) {
                    setCell(c, r, v)
                }
            }
        }

        // Room 1: Living Room (bottom-left: cols 8..62, rows 8..52)
        fillRect(8, 8, 62, 52, 1)
        // Room 2: Kitchen (bottom-right: cols 64..110, rows 8..52)
        fillRect(64, 8, 110, 52, 2)
        // Room 3: Master Bedroom (top-left: cols 8..58, rows 54..90)
        fillRect(8, 54, 58, 90, 3)
        // Room 4: Hallway & Study (top-right: cols 60..110, rows 54..90)
        fillRect(60, 54, 110, 90, 4)

        // Outer perimeter walls (0x00)
        fillRect(7, 7, 111, 7, 0x00)
        fillRect(7, 91, 111, 91, 0x00)
        fillRect(7, 7, 7, 91, 0x00)
        fillRect(111, 7, 111, 91, 0x00)

        // Horizontal divider wall (row 53) with two doorways
        fillRect(8, 53, 110, 53, 0x00)
        fillRect(28, 53, 38, 53, 1) // Doorway between Living Room (1) and Bedroom (3)
        fillRect(76, 53, 88, 53, 4) // Doorway between Kitchen (2) and Study (4)

        // Vertical divider wall bottom (col 63, rows 8..52) with doorway
        fillRect(63, 8, 63, 52, 0x00)
        fillRect(63, 24, 63, 36, 1) // Wide opening between Living Room (1) and Kitchen (2)

        // Vertical divider wall top (col 59, rows 54..90) with doorway
        fillRect(59, 54, 59, 90, 0x00)
        fillRect(59, 64, 59, 74, 3) // Doorway between Bedroom (3) and Hallway/Study (4)

        // Realistic furniture obstacles (0x00 = Lidar obstacle walls)
        fillRect(18, 22, 36, 30, 0x00) // Sofa in Living Room
        fillRect(78, 22, 94, 34, 0x00) // Kitchen Island
        fillRect(14, 68, 34, 84, 0x00) // Bed in Bedroom
        fillRect(88, 72, 104, 82, 0x00) // Desk in Study

        // Round-trip through Base64 + LZ4 (`infoType 20002`) so our parser is exercised continuously
        val (b64Map, lz4Len) = Lz4Codec.encodeMapBase64Lz4(raw)
        val dockMm = MapMath.cellToWorldMm(12f, 12f, X_MIN, Y_MIN, RESOLUTION)

        val areasJson = JSONArray()
        customRegions.forEach { areasJson.put(ProscenicProtocol.serializeRegion(it)) }

        val payload20002 = JSONObject().apply {
            put("SN", "LSLDSM7PRO20403551")
            put("mapId", 1700001001L)
            put("autoAreaId", 1)
            put("pathId", 1700002001L)
            put("width", GRID_WIDTH)
            put("height", GRID_HEIGHT)
            put("resolution", RESOLUTION.toDouble())
            put("x_min", X_MIN.toDouble())
            put("y_min", Y_MIN.toDouble())
            put("lz4_len", lz4Len)
            put("map", b64Map)
            put("base64_len", b64Map.length)
            put("area", areasJson)
            put("chargeHandlePos", JSONArray().put(dockMm.xMm).put(dockMm.yMm))
            put("chargeHandlePhi", (PI * 1000).roundToInt()) // Facing +X (0 rad)
            put("chargeHandleState", "find")
        }

        return ProscenicProtocol.parseMap20002(payload20002)
    }

    fun defaultRegions(): List<MapRegion> {
        return listOf(
            // Saved Clean Zone over Kitchen dining area
            MapRegion.createRectangle(
                id = 1,
                name = "Kitchen Rug",
                kind = ZoneKind.CLEAN_NORMAL,
                p1 = PointMm(400, -1800),
                p2 = PointMm(2100, -400),
                suction = SuctionLevel.STRONG,
                cleanType = CleanModeType.SWEEP_ONLY
            ),
            // Saved No-Go Zone around pet bowls / cables
            MapRegion.createRectangle(
                id = 2,
                name = "Pet Bowls No-Go",
                kind = ZoneKind.NO_GO,
                p1 = PointMm(1500, -2100),
                p2 = PointMm(2350, -1450)
            ),
            // Saved No-Mop Zone over Living Room carpet
            MapRegion.createRectangle(
                id = 3,
                name = "Living Room Carpet",
                kind = ZoneKind.NO_MOP,
                p1 = PointMm(-2050, -1850),
                p2 = PointMm(-450, -650)
            )
        )
    }

    fun defaultSchedules(): List<ScheduleEntry> {
        return listOf(
            ScheduleEntry(
                id = "sched_weekday_morning",
                hour = 9,
                minute = 0,
                daysOfWeek = setOf(1, 2, 3, 4, 5), // Mon-Fri
                enabled = true,
                suction = SuctionLevel.AUTO,
                passes = 1,
                cleanIds = listOf(-1, -3),
                label = "Weekday Morning Whole-Home"
            ),
            ScheduleEntry(
                id = "sched_weekend_deep",
                hour = 10,
                minute = 30,
                daysOfWeek = setOf(0, 6), // Sun & Sat
                enabled = true,
                suction = SuctionLevel.STRONG,
                passes = 2,
                cleanIds = listOf(1, -3),
                label = "Weekend Kitchen Zone Deep Clean"
            )
        )
    }

    /**
     * Generates a boustrophedon (lawnmower) S-curve cleaning path inside a bounding box in world mm,
     * skipping any forbidden (`NO_GO`) polygons.
     */
    fun generateZigZagWaypoints(
        minXMm: Int,
        minYMm: Int,
        maxXMm: Int,
        maxYMm: Int,
        forbiddenRegions: List<MapRegion>,
        spacingMm: Int = 250
    ): List<PointMm> {
        val waypoints = mutableListOf<PointMm>()
        var y = minYMm + 150
        var leftToRight = true
        while (y <= maxYMm - 150) {
            val xs = if (leftToRight) {
                (minXMm + 150..maxXMm - 150 step 200).toList()
            } else {
                (minXMm + 150..maxXMm - 150 step 200).reversed()
            }
            for (x in xs) {
                val pt = PointMm(x, y)
                if (!isInsideForbidden(pt, forbiddenRegions)) {
                    waypoints.add(pt)
                }
            }
            y += spacingMm
            leftToRight = !leftToRight
        }
        return waypoints
    }

    private fun isInsideForbidden(pt: PointMm, forbidden: List<MapRegion>): Boolean {
        for (reg in forbidden) {
            if (!reg.kind.isForbidden || reg.verticesMm.size < 3) continue
            val minX = reg.verticesMm.minOf { it.xMm }
            val maxX = reg.verticesMm.maxOf { it.xMm }
            val minY = reg.verticesMm.minOf { it.yMm }
            val maxY = reg.verticesMm.maxOf { it.yMm }
            if (pt.xMm in minX..maxX && pt.yMm in minY..maxY) {
                return true
            }
        }
        return false
    }

    /**
     * Simulates merging two room labels (`21030 merge`) on a `VacuumMap`.
     */
    fun mergeRooms(map: VacuumMap, label1: Int, label2: Int): VacuumMap {
        val targetLabel = minOf(label1, label2)
        val sourceLabel = maxOf(label1, label2)
        val newCells = map.cells.copyOf()
        for (i in newCells.indices) {
            if ((newCells[i].toInt() and 0xFF) == sourceLabel) {
                newCells[i] = targetLabel.toByte()
            }
        }
        return map.copy(
            autoAreaId = map.autoAreaId + 1,
            cells = newCells
        )
    }

    /**
     * Simulates splitting a room label (`21030 split`) along the line `p1Mm -> p2Mm`.
     */
    fun splitRoom(map: VacuumMap, roomLabel: Int, p1Mm: PointMm, p2Mm: PointMm): VacuumMap {
        val usedLabels = map.roomLabels
        val newLabel = (1..250).firstOrNull { it != 0x7F && it !in usedLabels } ?: (roomLabel + 1)
        val newCells = map.cells.copyOf()
        val dx = (p2Mm.xMm - p1Mm.xMm).toFloat()
        val dy = (p2Mm.yMm - p1Mm.yMm).toFloat()

        for (r in 0 until map.height) {
            for (c in 0 until map.width) {
                val idx = r * map.width + c
                if ((newCells[idx].toInt() and 0xFF) == roomLabel) {
                    val cellMm = map.cellToWorldMm(c.toFloat(), r.toFloat())
                    val cross = dx * (cellMm.yMm - p1Mm.yMm) - dy * (cellMm.xMm - p1Mm.xMm)
                    if (cross < 0f) {
                        newCells[idx] = newLabel.toByte()
                    }
                }
            }
        }
        return map.copy(
            autoAreaId = map.autoAreaId + 1,
            cells = newCells
        )
    }

    /**
     * Steps the simulated robot toward the next waypoint or manual steering velocity.
     */
    fun stepRobotAlongWaypoints(
        status: VacuumStatus,
        path: CleanPath,
        waypoints: MutableList<PointMm>,
        dockMm: PointMm
    ): Pair<VacuumStatus, CleanPath> {
        if (waypoints.isEmpty()) {
            return if (status.mode == "backcharge") {
                status.copy(mode = "charge", posMm = dockMm, phiMrad = 0) to path
            } else {
                // Finished cleaning -> head back to dock
                waypoints.add(dockMm)
                status.copy(mode = "backcharge") to path
            }
        }

        val target = waypoints.first()
        val dx = (target.xMm - status.posMm.xMm).toDouble()
        val dy = (target.yMm - status.posMm.yMm).toDouble()
        val dist = hypot(dx, dy)
        val stepMm = 180.0

        val nextPos: PointMm
        val headingRad = atan2(dy, dx)
        if (dist <= stepMm) {
            nextPos = target
            waypoints.removeAt(0)
        } else {
            nextPos = PointMm(
                xMm = (status.posMm.xMm + cos(headingRad) * stepMm).roundToInt(),
                yMm = (status.posMm.yMm + sin(headingRad) * stepMm).roundToInt()
            )
        }

        val tagY = if (status.mode == "backcharge") 1 else 0
        val newPoint = PathPoint(
            xMm = MapMath.stripPathTag(nextPos.xMm),
            yMm = MapMath.stripPathTag(nextPos.yMm),
            tagX = 0,
            tagY = tagY
        )
        val updatedPoints = (path.points + newPoint).takeLast(600)
        val newArea = if (status.mode == "sweep") status.cleanAreaM2 + 0.12f else status.cleanAreaM2
        val newTime = if (status.mode == "sweep") status.cleanTimeSec + 1 else status.cleanTimeSec

        return status.copy(
            posMm = nextPos,
            phiMrad = (headingRad * 1000.0).roundToInt(),
            cleanAreaM2 = newArea,
            cleanTimeSec = newTime,
            isOnline = true,
            lastUpdatedEpochMs = System.currentTimeMillis()
        ) to path.copy(
            totalPoints = updatedPoints.size,
            points = updatedPoints
        )
    }
}
