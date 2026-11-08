package com.proscenic.m7.protocol

import kotlin.math.PI
import kotlin.math.roundToInt

/**
 * Domain & wire models for the Proscenic M7 Pro (`LS_S6` / `network_proxy` / `libcpc.so`).
 * Derived directly from `PROTOCOL.md`, `MAP.md`, `FUNC_MAP.md`, `FUNC_COMMANDS.md`, and `ZONE_CLEAN.md`.
 */

enum class SuctionLevel(val wireValue: String, val label: String) {
    QUIET("quiet", "Quiet"),
    AUTO("auto", "Standard"),
    STRONG("strong", "Strong"),
    MAX("max", "Max"),
    MOP("mop", "Mop Only");

    companion object {
        fun fromWire(value: String?): SuctionLevel =
            entries.firstOrNull { it.wireValue.equals(value, ignoreCase = true) } ?: AUTO
    }
}

enum class CleanModeType(val wireValue: String, val label: String) {
    SWEEP_ONLY("sweepOnly", "Vacuum Only"),
    MOP_ONLY("mopOnly", "Mop Only"),
    SWEEP_MOP("sweepMop", "Vacuum + Mop");

    companion object {
        fun fromWire(value: String?): CleanModeType =
            entries.firstOrNull { it.wireValue.equals(value, ignoreCase = true) } ?: SWEEP_ONLY
    }
}

/**
 * Live status telemetry reported by the vacuum (`infoType: 20001` / `event_send.cpp`).
 */
data class VacuumStatus(
    val sn: String = "LSLDSM7PRO20403551",
    val mode: String = "charge",          // idle, sweep, pause, backcharge, charge, fullcharge, rfctrl, DustCenterWorking
    val subMode: String = "normal",
    val battery: Int = 100,               // elecReal / elec (0..100)
    val cleanAreaM2: Float = 0f,          // cleanArea
    val cleanTimeSec: Int = 0,            // cleanTime
    val suction: SuctionLevel = SuctionLevel.AUTO, // workNoisy
    val waterLevel: Int = 2,              // water (1..4)
    val volume: Int = 8,                  // vol (0..10)
    val isMuted: Boolean = false,         // mute
    val ledSwitch: Boolean = true,        // led
    val lidarCollisionAvoid: Boolean = true, // ldAvoidColli
    val dustCenterFreq: Int = 1,          // dustCenterFreq
    val posMm: PointMm = PointMm(0, 0),   // pos: [x_mm, y_mm]
    val phiMrad: Int = 0,                 // phi: heading in thousandths of a radian
    val errorState: List<Int> = emptyList(),
    val isOnline: Boolean = false,
    val lastUpdatedEpochMs: Long = System.currentTimeMillis()
) {
    val headingRad: Float
        get() = phiMrad / 1000f

    val errorCode: Int
        get() = errorState.firstOrNull { it != 0 } ?: 0

    val modeDisplayLabel: String
        get() = when {
            !isOnline || mode.equals("offline", ignoreCase = true) -> "Offline (Waiting for Robot)"
            mode.equals("sweep", ignoreCase = true) -> when (subMode.lowercase()) {
                "depth", "depthtotalclean" -> "Deep Cleaning (2x)"
                "appointclean" -> "Room / Zone Clean"
                "point", "curpoint" -> "Spot Cleaning"
                else -> "Smart Cleaning"
            }
            mode.equals("pause", ignoreCase = true) -> "Paused"
            mode.equals("backcharge", ignoreCase = true) -> "Returning to Dock"
            mode.equals("charge", ignoreCase = true) -> "Charging"
            mode.equals("fullcharge", ignoreCase = true) -> "Fully Charged"
            mode.equals("rfctrl", ignoreCase = true) -> "Manual Remote"
            mode.equals("dustcenterworking", ignoreCase = true) -> "Emptying Dustbin"
            else -> "Standby (${mode})"
        }
}

/**
 * World coordinate in millimetres (same frame as map origin, dock, regions, and path).
 */
data class PointMm(val xMm: Int, val yMm: Int)

/**
 * Fractional or integer grid coordinate on the `width × height` occupancy raster.
 */
data class GridCellF(val col: Float, val row: Float)

/**
 * Region / Zone category on the M7 Pro (`FUNC_MAP.md` §4).
 */
enum class ZoneKind(
    val label: String,
    val activeWire: String,
    val forbidTypeWire: String,
    val modeWire: String
) {
    CLEAN_NORMAL("Clean Zone", "normal", "all", "area"),
    CLEAN_DEEP("Deep Clean (2x)", "depth", "all", "area"),
    NO_GO("No-Go Zone", "forbid", "all", "area"),
    NO_MOP("No-Mop Zone", "forbid", "mop", "area"),
    NO_SWEEP("No-Vacuum Zone", "forbid", "sweep", "area"),
    PLACED_SPOT("Spot Target (1.5m)", "normal", "all", "point");

    val isForbidden: Boolean
        get() = activeWire == "forbid"

    companion object {
        fun fromWire(active: String?, forbidType: String?, mode: String?): ZoneKind {
            if (mode == "point" || mode == "curpoint") return PLACED_SPOT
            return when (active) {
                "forbid" -> when (forbidType) {
                    "mop" -> NO_MOP
                    "sweep" -> NO_SWEEP
                    else -> NO_GO
                }
                "depth" -> CLEAN_DEEP
                else -> CLEAN_NORMAL
            }
        }
    }
}

/**
 * A polygon region stored in `/tmp/Run/LastRecord/AreaSetting` (`21003`/`21004`)
 * or sent inline via `21023 extraAreas` (`FUNC_MAP.md` §4).
 */
data class MapRegion(
    val id: Int,
    val name: String,
    val tag: String = name.lowercase().take(16),
    val kind: ZoneKind = ZoneKind.CLEAN_NORMAL,
    val verticesMm: List<PointMm>,
    val cleanType: CleanModeType = CleanModeType.SWEEP_ONLY,
    val suction: SuctionLevel = SuctionLevel.AUTO
) {
    /**
     * Firmware safety (`FUNC_MAP.md` §4): `CreateRegionPolygonFromJson` copies `name` and `tag`
     * with an unbounded `strcpy` into 32-byte stack buffers, and `mode` into a 31-byte field.
     */
    val safeName: String
        get() = truncateUtf8(name.ifBlank { "Zone_$id" }, maxBytes = 31)

    val safeTag: String
        get() = truncateUtf8(tag.ifBlank { "zone_$id" }, maxBytes = 31)

    val safeMode: String
        get() = truncateUtf8(kind.modeWire, maxBytes = 30)

    val snappedVerticesMm: List<PointMm>
        get() = verticesMm.map { PointMm(MapMath.snapToLatticeMm(it.xMm), MapMath.snapToLatticeMm(it.yMm)) }

    companion object {
        fun truncateUtf8(input: String, maxBytes: Int): String {
            val bytes = input.toByteArray(Charsets.UTF_8)
            if (bytes.size <= maxBytes) return input
            var end = maxBytes
            while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) {
                end--
            }
            return String(bytes, 0, end, Charsets.UTF_8)
        }

        /**
         * Creates an axis-aligned rectangular zone from two corners in world mm.
         */
        fun createRectangle(
            id: Int,
            name: String,
            kind: ZoneKind,
            p1: PointMm,
            p2: PointMm,
            suction: SuctionLevel = SuctionLevel.AUTO,
            cleanType: CleanModeType = CleanModeType.SWEEP_ONLY
        ): MapRegion {
            // Ensure minimum 100mm (2 cells) thickness so snapping never collapses the polygon
            val minX = minOf(p1.xMm, p2.xMm)
            val maxX = maxOf(p1.xMm, p2.xMm).coerceAtLeast(minX + 100)
            val minY = minOf(p1.yMm, p2.yMm)
            val maxY = maxOf(p1.yMm, p2.yMm).coerceAtLeast(minY + 100)
            return MapRegion(
                id = id,
                name = name,
                kind = kind,
                verticesMm = listOf(
                    PointMm(minX, minY),
                    PointMm(maxX, minY),
                    PointMm(maxX, maxY),
                    PointMm(minX, maxY)
                ),
                cleanType = cleanType,
                suction = suction
            )
        }

        /**
         * Creates a 1.5m × 1.5m (±750 mm) spot-clean square around [centerMm] (`GLOSSARY.md` Placed spot cleaning).
         */
        fun createPlacedSpotSquare(centerMm: PointMm, deepClean: Boolean = false): MapRegion {
            val half = 750
            return MapRegion(
                id = -9,
                name = "Point",
                tag = "point",
                kind = if (deepClean) ZoneKind.CLEAN_DEEP else ZoneKind.PLACED_SPOT,
                verticesMm = listOf(
                    PointMm(centerMm.xMm - half, centerMm.yMm - half),
                    PointMm(centerMm.xMm + half, centerMm.yMm - half),
                    PointMm(centerMm.xMm + half, centerMm.yMm + half),
                    PointMm(centerMm.xMm - half, centerMm.yMm + half)
                )
            )
        }
    }
}

/**
 * Decoded 2D SLAM occupancy grid (`infoType: 20002`, `MAP.md` & `FUNC_MAP.md` §2).
 *
 * Cell values (`cells` row-major `width * height`):
 * - `0x00` (0)   = Wall / obstacle
 * - `0x7F` (127) = Unknown / unexplored
 * - `0xFF` (255) = Free floor (unsegmented)
 * - `0x01..0xFE` (except 0x7F) = Free floor in auto-segmented room `label`
 */
data class VacuumMap(
    val sn: String,
    val mapId: Long,
    val autoAreaId: Int,
    val pathId: Long,
    val width: Int,
    val height: Int,
    val resolution: Float = 0.05f,
    val xMin: Float,
    val yMin: Float,
    val cells: ByteArray,
    val areas: List<MapRegion> = emptyList(),
    val chargeHandlePos: PointMm? = null,
    val chargeHandlePhi: Int? = null,
    val chargeHandleState: String = "find"
) {
    /**
     * Dock heading in radians: `chargeHandlePhi / 1000.0 - π` (`FUNC_MAP.md` §2.5).
     */
    val dockHeadingRad: Float
        get() = ((chargeHandlePhi ?: 3142) / 1000.0 - PI).toFloat()

    /**
     * Distinct auto-segmented room labels (`1..254`, excluding `0x7F` unknown) present in the grid.
     */
    val roomLabels: Set<Int> by lazy {
        val set = sortedSetOf<Int>()
        for (b in cells) {
            val u = b.toInt() and 0xFF
            if (u != 0x00 && u != 0x7F && u != 0xFF) {
                set.add(u)
            }
        }
        set
    }

    /**
     * Computes the centroid in world mm for each segmented room label so room badges/names can be drawn.
     */
    val roomCentroidsMm: Map<Int, PointMm> by lazy {
        val sumCol = LongArray(256)
        val sumRow = LongArray(256)
        val counts = IntArray(256)
        for (row in 0 until height) {
            val rowOffset = row * width
            for (col in 0 until width) {
                val u = cells[rowOffset + col].toInt() and 0xFF
                if (u != 0x00 && u != 0x7F && u != 0xFF) {
                    sumCol[u] += col.toLong()
                    sumRow[u] += row.toLong()
                    counts[u]++
                }
            }
        }
        val result = mutableMapOf<Int, PointMm>()
        for (label in 1..254) {
            if (label != 0x7F && counts[label] > 0) {
                val avgCol = sumCol[label].toFloat() / counts[label]
                val avgRow = sumRow[label].toFloat() / counts[label]
                result[label] = cellToWorldMm(avgCol, avgRow)
            }
        }
        result
    }

    fun cellAt(col: Int, row: Int): Int {
        if (col !in 0 until width || row !in 0 until height) return 0x7F
        return cells[row * width + col].toInt() and 0xFF
    }

    fun cellToWorldMm(col: Float, row: Float): PointMm =
        MapMath.cellToWorldMm(col, row, xMin, yMin, resolution)

    fun worldMmToCell(pointMm: PointMm): GridCellF =
        MapMath.worldMmToCell(pointMm.xMm, pointMm.yMm, xMin, yMin, resolution)
}

/**
 * Coordinate math proven from `FUN_0045a430` (`FUNC_MAP.md` §2.4).
 */
object MapMath {
    private const val ORIGIN_SHIFT_M = 0.05f

    /**
     * Converts grid `(col, row)` to world millimetres (cell center).
     * `x_mm = 1000 * (x_min + 0.05 + col * resolution)`
     */
    fun cellToWorldMm(col: Float, row: Float, xMin: Float, yMin: Float, resolution: Float = 0.05f): PointMm {
        val xMm = (1000f * (xMin + ORIGIN_SHIFT_M + col * resolution)).roundToInt()
        val yMm = (1000f * (yMin + ORIGIN_SHIFT_M + row * resolution)).roundToInt()
        return PointMm(xMm, yMm)
    }

    /**
     * Converts world millimetres `(xMm, yMm)` to fractional grid `(col, row)`.
     * `col = (x_mm / 1000 - x_min - 0.05) / resolution`
     */
    fun worldMmToCell(xMm: Int, yMm: Int, xMin: Float, yMin: Float, resolution: Float = 0.05f): GridCellF {
        val col = ((xMm / 1000f) - xMin - ORIGIN_SHIFT_M) / resolution
        val row = ((yMm / 1000f) - yMin - ORIGIN_SHIFT_M) / resolution
        return GridCellF(col, row)
    }

    /**
     * Snaps a world coordinate (mm) onto the firmware's 50 mm cell-center lattice (`(v / 50) * 50 ± 25`).
     */
    fun snapToLatticeMm(valueMm: Int): Int {
        val base = (valueMm / 50) * 50
        return if (valueMm >= 0) base + 25 else base - 25
    }

    /**
     * Strips the low 2-bit point-type tag from a `21011` path coordinate (`FUNC_MAP.md` §3).
     */
    fun stripPathTag(coordMm: Int): Int = coordMm and 3.inv()
}

/**
 * A single point from a `21011` clean path (`posArray`), with its stripped world mm coordinates
 * and the 2-bit `(x & 3, y & 3)` pose/status tag.
 */
data class PathPoint(
    val xMm: Int,
    val yMm: Int,
    val tagX: Int = 0,
    val tagY: Int = 0
) {
    val isReturnToDock: Boolean
        get() = tagX == 0 && tagY == 1
    val isManualControl: Boolean
        get() = tagX == 0 && tagY == 2
}

data class CleanPath(
    val pathId: Long = 0L,
    val startPos: Int = 0,
    val totalPoints: Int = 0,
    val points: List<PathPoint> = emptyList()
)

/**
 * Weekly or one-shot cleaning schedule (`infoType: 21001` / `21002`, `FUNC_COMMANDS.md` §7).
 *
 * - `active = true` indicates a cleaning schedule (`false` is reserved for quiet hours).
 * - `unlock = true` means the schedule is enabled (`false` = disabled/paused).
 * - `startTimeSec`: For weekly schedules (`daysOfWeek` non-empty), seconds since local midnight (`hour * 3600 + minute * 60`).
 * - `daysOfWeek`: `0 = Sunday .. 6 = Saturday` (`tm_wday`).
 */
data class ScheduleEntry(
    val id: String,
    val hour: Int,
    val minute: Int,
    val daysOfWeek: Set<Int>,             // 0 = Sun, 1 = Mon, ..., 6 = Sat
    val enabled: Boolean = true,          // wire `unlock`
    val suction: SuctionLevel = SuctionLevel.AUTO,
    val passes: Int = 1,                  // wire `cleanNum` (1 or 2)
    val cleanIds: List<Int> = listOf(-1, -3), // [-1, -3] = whole home + stored no-go zones
    val label: String = "Scheduled Clean"
) {
    val startTimeSecFromMidnight: Int
        get() = (hour.coerceIn(0, 23) * 3600) + (minute.coerceIn(0, 59) * 60)

    val formattedTime: String
        get() = "%02d:%02d".format(hour, minute)
}

/**
 * Quiet Hours / Do-Not-Disturb configuration (`infoType: 21001` with `active: false`, `FUNC_COMMANDS.md` §7.3 & §7.5).
 */
data class QuietHoursConfig(
    val enabled: Boolean = false,
    val startHour: Int = 23,
    val startMinute: Int = 0,
    val endHour: Int = 7,
    val endMinute: Int = 0
)

/**
 * Consumables usage counters (`infoType: 21015` / reset `21016`, `GLOSSARY.md` Consumables).
 * Firmware stores cumulative cleaning seconds for each part.
 */
data class ConsumablesStatus(
    val sideBrushSec: Int = 14_400,
    val rollBrushSec: Int = 28_800,
    val filterSec: Int = 18_000,
    val mopSec: Int = 7_200,
    val sensorSec: Int = 10_800
)
