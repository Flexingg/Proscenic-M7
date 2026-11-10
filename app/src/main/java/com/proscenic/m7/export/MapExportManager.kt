package com.proscenic.m7.export

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.proscenic.m7.ai.AiMapArtEngine
import com.proscenic.m7.ai.AiMapArtItem
import com.proscenic.m7.protocol.CleanPath
import com.proscenic.m7.protocol.GridCellF
import com.proscenic.m7.protocol.Lz4Codec
import com.proscenic.m7.protocol.MapRegion
import com.proscenic.m7.protocol.PointMm
import com.proscenic.m7.protocol.ProscenicProtocol
import com.proscenic.m7.protocol.VacuumMap
import com.proscenic.m7.protocol.VacuumStatus
import com.proscenic.m7.protocol.ZoneKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Renders and downloads high-resolution 2D Floor Map PNG images (including AI Map Art furniture/objects,
 * segmented rooms, zones, dock, and robot pose) and JSON map backups to the user's Android device.
 */
object MapExportManager {

    private val roomColorsArgb = intArrayOf(
        0xFF4DB6AC.toInt(), // Room 1: Teal
        0xFF64B5F6.toInt(), // Room 2: Sky Blue
        0xFFFFB74D.toInt(), // Room 3: Warm Amber
        0xFFBA68C8.toInt(), // Room 4: Lavender
        0xFF81C784.toInt(), // Room 5: Sage Green
        0xFFE57373.toInt(), // Room 6: Coral
        0xFF7986CB.toInt(), // Room 7: Indigo
        0xFFFFD54F.toInt()  // Room 8: Gold
    )

    suspend fun downloadMapPngAndJson(
        context: Context,
        map: VacuumMap,
        status: VacuumStatus,
        path: CleanPath,
        regions: List<MapRegion>,
        roomNames: Map<Int, String>,
        aiArtItems: List<AiMapArtItem>,
        includeJsonBackup: Boolean = true
    ): Result<String> = withContext(Dispatchers.IO) {
        return@withContext try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val pngFileName = "Proscenic_M7_Map_$timestamp.png"
            val bitmap = renderHighResMapBitmap(
                map = map,
                status = status,
                path = path,
                regions = regions,
                roomNames = roomNames,
                aiArtItems = aiArtItems
            )

            // 1. Save PNG to MediaStore Pictures/ProscenicM7 (visible in Gallery & Photos)
            val resolver = context.contentResolver
            val imageValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, pngFileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ProscenicM7")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            val imgUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, imageValues)
                ?: throw IllegalStateException("Could not create MediaStore image entry")

            resolver.openOutputStream(imgUri)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                out.flush()
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                imageValues.clear()
                imageValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(imgUri, imageValues, null, null)
            }

            // 2. Also save PNG + JSON to Downloads on Android 10+ (API 29+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                runCatching {
                    val dlValues = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, pngFileName)
                        put(MediaStore.Downloads.MIME_TYPE, "image/png")
                        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    }
                    resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, dlValues)?.let { dlUri ->
                        resolver.openOutputStream(dlUri)?.use { out ->
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                        }
                    }

                    if (includeJsonBackup) {
                        val jsonFileName = "Proscenic_M7_Map_$timestamp.json"
                        val jsonString = serializeFullMapBundleJson(map, regions, roomNames, aiArtItems).toString(2)
                        val jsonValues = ContentValues().apply {
                            put(MediaStore.Downloads.DISPLAY_NAME, jsonFileName)
                            put(MediaStore.Downloads.MIME_TYPE, "application/json")
                            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                        }
                        resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, jsonValues)?.let { jUri ->
                            resolver.openOutputStream(jUri)?.use { out ->
                                out.write(jsonString.toByteArray(Charsets.UTF_8))
                            }
                        }
                    }
                }
            }

            Result.success("Saved '$pngFileName' to Pictures/ProscenicM7 & Downloads (${bitmap.width}×${bitmap.height} px)")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Renders a high-resolution (`1800px` scale) floor plan `Bitmap` with occupancy grid, rooms,
     * AI Map Art top-down furniture/object sprites, zones, path, charging dock, and robot pose.
     */
    fun renderHighResMapBitmap(
        map: VacuumMap,
        status: VacuumStatus,
        path: CleanPath,
        regions: List<MapRegion>,
        roomNames: Map<Int, String>,
        aiArtItems: List<AiMapArtItem>
    ): Bitmap {
        val targetMaxDim = 1800
        val marginPx = 80f
        val footerPx = 90f
        val cellPx = max(6f, min(
            (targetMaxDim - marginPx * 2f) / map.width.coerceAtLeast(1),
            (targetMaxDim - marginPx * 2f) / map.height.coerceAtLeast(1)
        ))
        val gridW = map.width * cellPx
        val gridH = map.height * cellPx
        val bmpW = (gridW + marginPx * 2f).roundToInt().coerceIn(600, 3200)
        val bmpH = (gridH + marginPx * 2f + footerPx).roundToInt().coerceIn(600, 3200)

        val bitmap = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.parseColor("#0F172A"))

        val originX = (bmpW - gridW) / 2f
        val originY = marginPx

        fun worldMmToPx(ptMm: PointMm): Pair<Float, Float> {
            val cell: GridCellF = map.worldMmToCell(ptMm)
            val sx = originX + (cell.col + 0.5f) * cellPx
            val sy = originY + (map.height - 0.5f - cell.row) * cellPx
            return sx to sy
        }

        // 1. Draw occupancy grid
        val cellPaint = Paint()
        for (row in 0 until map.height) {
            for (col in 0 until map.width) {
                val v = map.cellAt(col, row)
                if (v == 0x7F) continue
                cellPaint.color = when (v) {
                    0x00 -> 0xFF1E293B.toInt()
                    0xFF -> 0xFF475569.toInt()
                    else -> roomColorsArgb[(v - 1).mod(roomColorsArgb.size)]
                }
                val sx = originX + col * cellPx
                val sy = originY + (map.height - 1 - row) * cellPx
                canvas.drawRect(sx, sy, sx + cellPx + 0.8f, sy + cellPx + 0.8f, cellPaint)
            }
        }

        // 2. Draw AI Map Art top-down sprites scaled to their real-world millimeter footprints
        val artPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val artLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = 24f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
            setShadowLayer(4f, 0f, 2f, android.graphics.Color.BLACK)
        }
        for (item in aiArtItems) {
            val sprite = AiMapArtEngine.decodeBase64ToBitmap(item.topDownPngBase64) ?: continue
            val (cx, cy) = worldMmToPx(item.centerMm)
            val wPx = (item.widthMm / (map.resolution * 1000f)) * cellPx
            val hPx = (item.heightMm / (map.resolution * 1000f)) * cellPx
            canvas.save()
            canvas.translate(cx, cy)
            canvas.rotate(item.rotationDeg)
            val dstRect = RectF(-wPx / 2f, -hPx / 2f, wPx / 2f, hPx / 2f)
            canvas.drawBitmap(sprite, null, dstRect, artPaint)
            canvas.restore()
            canvas.drawText(item.label, cx, cy + hPx / 2f + 24f, artLabelPaint)
        }

        // 3. Draw regions (Clean Zones & No-Go Zones)
        for (reg in regions) {
            if (reg.verticesMm.size < 3) continue
            val strokeColor = when (reg.kind) {
                ZoneKind.NO_GO -> 0xFFEF4444.toInt()
                ZoneKind.NO_SWEEP -> 0xFFEC4899.toInt()
                ZoneKind.CLEAN_DEEP -> 0xFF06B6D4.toInt()
                ZoneKind.CLEAN_NORMAL -> 0xFF22C55E.toInt()
                ZoneKind.PLACED_SPOT -> 0xFFA855F7.toInt()
            }
            val poly = Path()
            reg.verticesMm.forEachIndexed { idx, pt ->
                val (px, py) = worldMmToPx(pt)
                if (idx == 0) poly.moveTo(px, py) else poly.lineTo(px, py)
            }
            poly.close()

            val fillP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = (strokeColor and 0x00FFFFFF) or (0x40 shl 24)
                style = Paint.Style.FILL
            }
            val strokeP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = strokeColor
                style = Paint.Style.STROKE
                strokeWidth = 4f
                if (reg.kind.isForbidden) {
                    pathEffect = DashPathEffect(floatArrayOf(14f, 10f), 0f)
                }
            }
            canvas.drawPath(poly, fillP)
            canvas.drawPath(poly, strokeP)
        }

        // 4. Draw Clean Path
        if (path.points.size >= 2) {
            val pPath = Path()
            path.points.forEachIndexed { idx, pt ->
                val (px, py) = worldMmToPx(PointMm(pt.xMm, pt.yMm))
                if (idx == 0) pPath.moveTo(px, py) else pPath.lineTo(px, py)
            }
            val trailP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xE0FFFFFF.toInt()
                style = Paint.Style.STROKE
                strokeWidth = 3.5f
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            canvas.drawPath(pPath, trailP)
        }

        // 5. Draw Room Labels
        val roomLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = 30f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
            setShadowLayer(5f, 0f, 2f, android.graphics.Color.BLACK)
        }
        for ((roomLabel, centroidMm) in map.roomCentroidsMm) {
            val (cx, cy) = worldMmToPx(centroidMm)
            val name = roomNames[roomLabel] ?: "Room $roomLabel"
            canvas.drawText(name, cx, cy, roomLabelPaint)
        }

        // 6. Draw Dock & Robot
        map.chargeHandlePos?.let { dockMm ->
            val (dx, dy) = worldMmToPx(dockMm)
            val dp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF10B981.toInt() }
            canvas.drawCircle(dx, dy, 18f, dp)
        }
        val (rx, ry) = worldMmToPx(status.posMm)
        val rRad = (cellPx * 3.2f).coerceIn(16f, 32f)
        val rp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF38BDF8.toInt() }
        val rStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }
        canvas.drawCircle(rx, ry, rRad, rp)
        canvas.drawCircle(rx, ry, rRad, rStroke)
        val hx = rx + cos(status.headingRad) * rRad * 0.85f
        val hy = ry - sin(status.headingRad) * rRad * 0.85f
        canvas.drawLine(rx, ry, hx, hy, rStroke)

        // 7. Draw Metadata Footer
        val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF94A3B8.toInt()
            textSize = 26f
            textAlign = Paint.Align.CENTER
        }
        val footerText = "Proscenic M7 2D LiDAR Map (${map.width}×${map.height} @ 5cm) • Rooms: ${map.roomLabels.size} • AI Art Objects: ${aiArtItems.size}"
        canvas.drawText(footerText, bmpW / 2f, bmpH - 32f, footerPaint)

        return bitmap
    }

    /**
     * Serializes the entire `VacuumMap` + `regions` + `roomNames` + `aiArtItems` to a JSON object
     * for local disk persistence and `.json` file export.
     */
    fun serializeFullMapBundleJson(
        map: VacuumMap,
        regions: List<MapRegion>,
        roomNames: Map<Int, String>,
        aiArtItems: List<AiMapArtItem>
    ): JSONObject {
        val (b64Cells, _) = Lz4Codec.encodeMapBase64Lz4(map.cells)
        val mapJson = JSONObject().apply {
            put("SN", map.sn)
            put("mapId", map.mapId)
            put("autoAreaId", map.autoAreaId)
            put("pathId", map.pathId)
            put("width", map.width)
            put("height", map.height)
            put("resolution", map.resolution.toDouble())
            put("x_min", map.xMin.toDouble())
            put("y_min", map.yMin.toDouble())
            put("map", b64Cells)
            map.chargeHandlePos?.let {
                put("chargeHandlePos", JSONArray().put(it.xMm).put(it.yMm))
            }
            map.chargeHandlePhi?.let { put("chargeHandlePhi", it) }
            put("chargeHandleState", map.chargeHandleState)
            val areaArr = JSONArray()
            regions.forEach { areaArr.put(ProscenicProtocol.serializeRegion(it)) }
            put("area", areaArr)
        }

        val namesJson = JSONObject()
        roomNames.forEach { (k, v) -> namesJson.put(k.toString(), v) }

        val artArr = JSONArray()
        aiArtItems.forEach { artArr.put(it.toJson()) }

        return JSONObject().apply {
            put("map20002", mapJson)
            put("roomNames", namesJson)
            put("aiMapArtItems", artArr)
        }
    }
}
