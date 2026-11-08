package com.proscenic.m7.ui

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Remove
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.unit.dp
import com.proscenic.m7.ai.AiMapArtEngine
import com.proscenic.m7.ai.AiMapArtItem
import com.proscenic.m7.protocol.CleanPath
import com.proscenic.m7.protocol.GridCellF
import com.proscenic.m7.protocol.MapRegion
import com.proscenic.m7.protocol.PointMm
import com.proscenic.m7.protocol.VacuumMap
import com.proscenic.m7.protocol.VacuumStatus
import com.proscenic.m7.protocol.ZoneKind
import com.proscenic.m7.ui.components.CircularGlassIconButton
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Interactive 2D Floor Map Canvas for the Proscenic M7 Pro (`MAP.md` & `FUNC_MAP.md` §2.4).
 *
 * Features:
 * 1. Smooth multi-touch Pinch-to-Zoom (`0.5x..8.0x` focal-point zoom) and Pan in ALL modes,
 *    plus 1-finger pan in `SELECT_ROOMS` mode and floating glass `+` / `-` / `Reset` controls.
 * 2. Decoded `width × height` LZ4 SLAM occupancy grid (`0x00` walls, `0x7F` unknown, `0xFF` free floor,
 *    `0x01..0xFE` auto-segmented room labels with distinct pastel colors).
 * 3. **AI Map Art** top-down orthographic object sprites scaled to fit their exact real-world millimeter
 *    footprints (`widthMm × heightMm`) and rotated by `rotationDeg`.
 * 4. Stored & live-drawn polygon regions (`CLEAN_NORMAL`, `CLEAN_DEEP`, `NO_GO`, `NO_MOP`, `PLACED_SPOT`).
 * 5. Real-time cleaning trail (`infoType: 21011`), Charging Station, and Vacuum Robot pose.
 */
@Composable
fun FloorMapCanvas(
    map: VacuumMap,
    status: VacuumStatus,
    path: CleanPath,
    regions: List<MapRegion>,
    roomNames: Map<Int, String>,
    selectedRoomLabels: Set<Int>,
    selectedZoneIds: Set<Int>,
    aiArtItems: List<AiMapArtItem> = emptyList(),
    selectedAiArtId: String? = null,
    interactionMode: MapInteractionMode,
    activeZoneDrawKind: ZoneKind,
    placedSpotTargetMm: PointMm,
    mergeFirstRoomLabel: Int?,
    onRoomTapped: (Int) -> Unit,
    onZoneTapped: (Int) -> Unit,
    onZoneDrawn: (PointMm, PointMm) -> Unit,
    onSpotMoved: (PointMm) -> Unit,
    onRoomSplitLineDrawn: (PointMm, PointMm) -> Unit,
    onAiArtTapped: (String?) -> Unit = {},
    onAiArtMoved: (String, PointMm) -> Unit = { _, _ -> },
    onAiBoxDrawn: (PointMm, PointMm) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    var zoom by remember { mutableFloatStateOf(1f) }
    var panOffset by remember { mutableStateOf(Offset.Zero) }

    var dragStartScreen by remember { mutableStateOf<Offset?>(null) }
    var dragCurrentScreen by remember { mutableStateOf<Offset?>(null) }

    // Decode & cache AI Map Art Bitmaps so 60/120fps map pan & zoom never re-decodes Base64 PNGs
    val decodedArtBitmaps: Map<String, Bitmap> = remember(aiArtItems) {
        val out = mutableMapOf<String, Bitmap>()
        for (item in aiArtItems) {
            AiMapArtEngine.decodeBase64ToBitmap(item.topDownPngBase64)?.let { bmp ->
                out[item.id] = bmp
            }
        }
        out
    }

    val roomColorPalette = remember {
        listOf(
            Color(0xFF4DB6AC), // Room 1: Teal
            Color(0xFF64B5F6), // Room 2: Sky Blue
            Color(0xFFFFB74D), // Room 3: Warm Amber
            Color(0xFFBA68C8), // Room 4: Lavender
            Color(0xFF81C784), // Room 5: Sage Green
            Color(0xFFE57373), // Room 6: Coral
            Color(0xFF7986CB), // Room 7: Indigo
            Color(0xFFFFD54F)  // Room 8: Gold
        )
    }

    val labelPaint = remember {
        Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.WHITE
            textSize = 28f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
            setShadowLayer(4f, 0f, 2f, android.graphics.Color.BLACK)
        }
    }

    val bitmapPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0F172A))
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(interactionMode, map, regions, aiArtItems, selectedAiArtId) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val startPos = down.position
                        var currentPos = startPos
                        var maxPointers = 1
                        var totalMovedPx = 0f
                        var draggingExistingArtId: String? = null

                        // Check if 1-finger down started inside the currently selected AI Art item to move it
                        if (interactionMode == MapInteractionMode.AI_MAP_ART && selectedAiArtId != null) {
                            val selectedItem = aiArtItems.firstOrNull { it.id == selectedAiArtId }
                            if (selectedItem != null) {
                                val tf = MapScreenTransform(size.width.toFloat(), size.height.toFloat(), map, zoom, panOffset)
                                val downMm = tf.screenToWorldMm(startPos)
                                val halfW = selectedItem.widthMm / 2 + 150
                                val halfH = selectedItem.heightMm / 2 + 150
                                if (abs(downMm.xMm - selectedItem.centerMm.xMm) <= halfW &&
                                    abs(downMm.yMm - selectedItem.centerMm.yMm) <= halfH
                                ) {
                                    draggingExistingArtId = selectedItem.id
                                }
                            }
                        }

                        val isBoxOrLineDrawMode = interactionMode == MapInteractionMode.EDIT_ZONES ||
                            interactionMode == MapInteractionMode.SPLIT_MERGE ||
                            (interactionMode == MapInteractionMode.AI_MAP_ART && draggingExistingArtId == null)

                        if (isBoxOrLineDrawMode) {
                            dragStartScreen = startPos
                            dragCurrentScreen = startPos
                        } else if (interactionMode == MapInteractionMode.PLACED_SPOT) {
                            val tf = MapScreenTransform(size.width.toFloat(), size.height.toFloat(), map, zoom, panOffset)
                            onSpotMoved(tf.screenToWorldMm(startPos))
                        }

                        do {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.size > maxPointers) {
                                maxPointers = pressed.size
                            }

                            if (pressed.size >= 2) {
                                // Two-finger pinch-to-zoom & pan works in ALL modes!
                                dragStartScreen = null
                                dragCurrentScreen = null
                                val zoomChange = event.calculateZoom()
                                val panChange = event.calculatePan()
                                val centroid = event.calculateCentroid(useCurrent = false)
                                val canvasCenter = Offset(size.width / 2f, size.height / 2f)

                                val oldZoom = zoom
                                val newZoom = (oldZoom * zoomChange).coerceIn(0.5f, 8.0f)
                                val effectiveZoomRatio = newZoom / oldZoom

                                // Zoom around finger centroid
                                val relCentroid = centroid - canvasCenter - panOffset
                                panOffset = panOffset + panChange - relCentroid * (effectiveZoomRatio - 1f)
                                zoom = newZoom

                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            } else if (pressed.size == 1 && maxPointers == 1) {
                                val change = pressed.first()
                                val delta = change.position - currentPos
                                currentPos = change.position
                                totalMovedPx += hypot(delta.x, delta.y)

                                when (interactionMode) {
                                    MapInteractionMode.SELECT_ROOMS -> {
                                        // 1-finger drag smoothly pans the map!
                                        if (totalMovedPx > 8f) {
                                            panOffset += delta
                                            change.consume()
                                        }
                                    }
                                    MapInteractionMode.PLACED_SPOT -> {
                                        val tf = MapScreenTransform(size.width.toFloat(), size.height.toFloat(), map, zoom, panOffset)
                                        onSpotMoved(tf.screenToWorldMm(currentPos))
                                        change.consume()
                                    }
                                    MapInteractionMode.AI_MAP_ART -> {
                                        if (draggingExistingArtId != null) {
                                            val tf = MapScreenTransform(size.width.toFloat(), size.height.toFloat(), map, zoom, panOffset)
                                            onAiArtMoved(draggingExistingArtId, tf.screenToWorldMm(currentPos))
                                            change.consume()
                                        } else {
                                            dragCurrentScreen = currentPos
                                            change.consume()
                                        }
                                    }
                                    MapInteractionMode.EDIT_ZONES, MapInteractionMode.SPLIT_MERGE -> {
                                        dragCurrentScreen = currentPos
                                        change.consume()
                                    }
                                }
                            }
                        } while (event.changes.any { it.pressed })

                        // Gesture ended: handle either a tap or a finished 1-finger box/line drag
                        if (maxPointers == 1) {
                            val tf = MapScreenTransform(size.width.toFloat(), size.height.toFloat(), map, zoom, panOffset)
                            if (totalMovedPx <= 18f) {
                                // Single tap!
                                dragStartScreen = null
                                dragCurrentScreen = null
                                val tappedMm = tf.screenToWorldMm(currentPos)
                                val tappedCell = map.worldMmToCell(tappedMm)
                                val col = tappedCell.col.roundToInt()
                                val row = tappedCell.row.roundToInt()

                                when (interactionMode) {
                                    MapInteractionMode.PLACED_SPOT -> {
                                        onSpotMoved(tappedMm)
                                    }
                                    MapInteractionMode.EDIT_ZONES -> {
                                        val hitZone = regions.lastOrNull { reg ->
                                            if (reg.verticesMm.size < 3) false
                                            else {
                                                val minX = reg.verticesMm.minOf { it.xMm }
                                                val maxX = reg.verticesMm.maxOf { it.xMm }
                                                val minY = reg.verticesMm.minOf { it.yMm }
                                                val maxY = reg.verticesMm.maxOf { it.yMm }
                                                tappedMm.xMm in minX..maxX && tappedMm.yMm in minY..maxY
                                            }
                                        }
                                        if (hitZone != null) {
                                            onZoneTapped(hitZone.id)
                                        }
                                    }
                                    MapInteractionMode.AI_MAP_ART -> {
                                        val hitArt = aiArtItems.lastOrNull { item ->
                                            val halfW = item.widthMm / 2 + 120
                                            val halfH = item.heightMm / 2 + 120
                                            abs(tappedMm.xMm - item.centerMm.xMm) <= halfW &&
                                                abs(tappedMm.yMm - item.centerMm.yMm) <= halfH
                                        }
                                        onAiArtTapped(hitArt?.id)
                                    }
                                    MapInteractionMode.SELECT_ROOMS, MapInteractionMode.SPLIT_MERGE -> {
                                        // First check if user tapped an AI Art object to inspect/select
                                        val cellVal = map.cellAt(col, row)
                                        if (cellVal != 0x00 && cellVal != 0x7F && cellVal != 0xFF) {
                                            onRoomTapped(cellVal)
                                        }
                                    }
                                }
                            } else if (isBoxOrLineDrawMode) {
                                val start = dragStartScreen
                                val end = dragCurrentScreen
                                if (start != null && end != null) {
                                    val p1Mm = tf.screenToWorldMm(start)
                                    val p2Mm = tf.screenToWorldMm(end)
                                    if (abs(p1Mm.xMm - p2Mm.xMm) > 150 || abs(p1Mm.yMm - p2Mm.yMm) > 150) {
                                        when (interactionMode) {
                                            MapInteractionMode.EDIT_ZONES -> onZoneDrawn(p1Mm, p2Mm)
                                            MapInteractionMode.SPLIT_MERGE -> onRoomSplitLineDrawn(p1Mm, p2Mm)
                                            MapInteractionMode.AI_MAP_ART -> onAiBoxDrawn(p1Mm, p2Mm)
                                            else -> {}
                                        }
                                    }
                                }
                                dragStartScreen = null
                                dragCurrentScreen = null
                            }
                        } else {
                            dragStartScreen = null
                            dragCurrentScreen = null
                        }
                    }
                }
        ) {
            val transform = MapScreenTransform(size.width, size.height, map, zoom, panOffset)
            val cellPx = transform.cellPx

            // 1. Draw occupancy grid cells (walls, free floor, segmented rooms)
            for (row in 0 until map.height) {
                for (col in 0 until map.width) {
                    val v = map.cellAt(col, row)
                    if (v == 0x7F) continue // Unknown outside walls -> keep dark background

                    val color = when (v) {
                        0x00 -> Color(0xFF1E293B) // Wall / obstacle
                        0xFF -> Color(0xFF475569) // Unsegmented free floor
                        else -> {
                            val base = roomColorPalette[(v - 1).mod(roomColorPalette.size)]
                            val isSelected = v in selectedRoomLabels || v == mergeFirstRoomLabel
                            if (isSelected) base.copy(alpha = 0.95f) else base.copy(alpha = 0.65f)
                        }
                    }

                    val topLeft = transform.cellTopLeftScreen(col, row)
                    drawRect(
                        color = color,
                        topLeft = topLeft,
                        size = Size(cellPx + 0.7f, cellPx + 0.7f)
                    )
                }
            }

            // 2. Draw AI Map Art top-down sprites scaled to their real-world millimeter footprints
            for (item in aiArtItems) {
                val centerScreen = transform.worldMmToScreen(item.centerMm)
                val wPx = (item.widthMm / (map.resolution * 1000f)) * cellPx
                val hPx = (item.heightMm / (map.resolution * 1000f)) * cellPx
                val sprite = decodedArtBitmaps[item.id]

                drawContext.canvas.nativeCanvas.apply {
                    save()
                    translate(centerScreen.x, centerScreen.y)
                    rotate(item.rotationDeg)
                    val dstRect = RectF(-wPx / 2f, -hPx / 2f, wPx / 2f, hPx / 2f)
                    if (sprite != null) {
                        drawBitmap(sprite, null, dstRect, bitmapPaint)
                    }
                    restore()
                }

                val isSelected = item.id == selectedAiArtId
                if (isSelected || interactionMode == MapInteractionMode.AI_MAP_ART) {
                    val boxTopLeft = Offset(centerScreen.x - wPx / 2f, centerScreen.y - hPx / 2f)
                    drawRect(
                        color = if (isSelected) Color(0xFF38BDF8) else Color(0xFF38BDF8).copy(alpha = 0.45f),
                        topLeft = boxTopLeft,
                        size = Size(wPx, hPx),
                        style = Stroke(
                            width = if (isSelected) 4f else 2f,
                            pathEffect = if (isSelected) null else PathEffect.dashPathEffect(floatArrayOf(8f, 6f))
                        )
                    )
                }
                val dimText = "${item.label} (${item.widthMm / 10}×${item.heightMm / 10}cm)"
                drawContext.canvas.nativeCanvas.drawText(
                    if (isSelected) dimText else item.label,
                    centerScreen.x,
                    centerScreen.y + hPx / 2f + 24f,
                    labelPaint
                )
            }

            // 3. Draw stored regions (Clean Zones, Deep Clean Zones, No-Go Zones, No-Mop Zones)
            for (region in regions) {
                if (region.verticesMm.size < 3) continue
                val strokeColor = when (region.kind) {
                    ZoneKind.NO_GO -> Color(0xFFEF4444)
                    ZoneKind.NO_MOP -> Color(0xFFF59E0B)
                    ZoneKind.NO_SWEEP -> Color(0xFFEC4899)
                    ZoneKind.CLEAN_DEEP -> Color(0xFF06B6D4)
                    ZoneKind.CLEAN_NORMAL -> Color(0xFF22C55E)
                    ZoneKind.PLACED_SPOT -> Color(0xFFA855F7)
                }
                val isSelected = region.id in selectedZoneIds
                val fillAlpha = if (isSelected) 0.38f else 0.22f

                val polyPath = Path()
                region.verticesMm.forEachIndexed { idx, ptMm ->
                    val screenPt = transform.worldMmToScreen(ptMm)
                    if (idx == 0) polyPath.moveTo(screenPt.x, screenPt.y)
                    else polyPath.lineTo(screenPt.x, screenPt.y)
                }
                polyPath.close()

                drawPath(path = polyPath, color = strokeColor.copy(alpha = fillAlpha))
                drawPath(
                    path = polyPath,
                    color = strokeColor,
                    style = Stroke(
                        width = if (isSelected) 5f else 3f,
                        pathEffect = if (region.kind.isForbidden) PathEffect.dashPathEffect(floatArrayOf(12f, 8f)) else null
                    )
                )

                val firstPt = transform.worldMmToScreen(region.verticesMm.first())
                drawContext.canvas.nativeCanvas.drawText(
                    region.safeName,
                    firstPt.x + 48f,
                    firstPt.y + 28f,
                    labelPaint
                )
            }

            // 4. Draw Placed Spot Target (1.5m x 1.5m box) when in PLACED_SPOT mode
            if (interactionMode == MapInteractionMode.PLACED_SPOT) {
                val pMin = transform.worldMmToScreen(PointMm(placedSpotTargetMm.xMm - 750, placedSpotTargetMm.yMm + 750))
                val pMax = transform.worldMmToScreen(PointMm(placedSpotTargetMm.xMm + 750, placedSpotTargetMm.yMm - 750))
                val center = transform.worldMmToScreen(placedSpotTargetMm)
                drawRect(
                    color = Color(0xFFA855F7).copy(alpha = 0.28f),
                    topLeft = pMin,
                    size = Size(abs(pMax.x - pMin.x), abs(pMax.y - pMin.y))
                )
                drawRect(
                    color = Color(0xFFD8B4FE),
                    topLeft = pMin,
                    size = Size(abs(pMax.x - pMin.x), abs(pMax.y - pMin.y)),
                    style = Stroke(width = 4f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
                )
                drawCircle(color = Color(0xFFD8B4FE), radius = 10f, center = center)
            }

            // 5. Draw Live Clean Path (`infoType: 21011`)
            if (path.points.size >= 2) {
                val trailPath = Path()
                path.points.forEachIndexed { idx, pt ->
                    val sPt = transform.worldMmToScreen(PointMm(pt.xMm, pt.yMm))
                    if (idx == 0) trailPath.moveTo(sPt.x, sPt.y)
                    else trailPath.lineTo(sPt.x, sPt.y)
                }
                drawPath(
                    path = trailPath,
                    color = Color.White.copy(alpha = 0.88f),
                    style = Stroke(width = 3.5f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
            }

            // 6. Draw Room Labels at each Room's Centroid
            for ((roomLabel, centroidMm) in map.roomCentroidsMm) {
                val centerScreen = transform.worldMmToScreen(centroidMm)
                val name = roomNames[roomLabel] ?: "Room $roomLabel"
                val selectedPrefix = if (roomLabel in selectedRoomLabels || roomLabel == mergeFirstRoomLabel) "✓ " else ""
                drawContext.canvas.nativeCanvas.drawText(
                    "$selectedPrefix$name",
                    centerScreen.x,
                    centerScreen.y,
                    labelPaint
                )
            }

            // 7. Draw Charging Station (`chargeHandlePos`)
            map.chargeHandlePos?.let { dockMm ->
                val dockScreen = transform.worldMmToScreen(dockMm)
                drawCircle(color = Color(0xFF10B981), radius = 16f, center = dockScreen)
                drawCircle(color = Color.White, radius = 16f, center = dockScreen, style = Stroke(width = 3f))
                drawCircle(color = Color.White, radius = 5f, center = dockScreen)
            }

            // 8. Draw Vacuum Robot (`status.posMm` + heading `status.headingRad`)
            val robotScreen = transform.worldMmToScreen(status.posMm)
            val robotRadius = (cellPx * 3.2f).coerceIn(14f, 28f)
            drawCircle(color = Color(0xFF38BDF8), radius = robotRadius, center = robotScreen)
            drawCircle(color = Color.White, radius = robotRadius, center = robotScreen, style = Stroke(width = 3.5f))
            val headX = robotScreen.x + cos(status.headingRad) * robotRadius * 0.85f
            val headY = robotScreen.y - sin(status.headingRad) * robotRadius * 0.85f
            drawLine(
                color = Color.White,
                start = robotScreen,
                end = Offset(headX, headY),
                strokeWidth = 4f,
                cap = StrokeCap.Round
            )

            // 9. Draw active drag preview (new Zone rectangle, AI Map Art footprint box, or Room Split line)
            val dStart = dragStartScreen
            val dEnd = dragCurrentScreen
            if (dStart != null && dEnd != null) {
                if (interactionMode == MapInteractionMode.EDIT_ZONES || interactionMode == MapInteractionMode.AI_MAP_ART) {
                    val topLeft = Offset(min(dStart.x, dEnd.x), min(dStart.y, dEnd.y))
                    val rectSize = Size(abs(dEnd.x - dStart.x), abs(dEnd.y - dStart.y))
                    val previewColor = when {
                        interactionMode == MapInteractionMode.AI_MAP_ART -> Color(0xFF38BDF8)
                        activeZoneDrawKind.isForbidden -> Color(0xFFEF4444)
                        else -> Color(0xFF22C55E)
                    }
                    drawRect(color = previewColor.copy(alpha = 0.25f), topLeft = topLeft, size = rectSize)
                    drawRect(color = previewColor, topLeft = topLeft, size = rectSize, style = Stroke(width = 4f))

                    // Show real-world cm dimensions while dragging!
                    val p1Mm = transform.screenToWorldMm(dStart)
                    val p2Mm = transform.screenToWorldMm(dEnd)
                    val wCm = (abs(p2Mm.xMm - p1Mm.xMm) / 10).coerceAtLeast(1)
                    val hCm = (abs(p2Mm.yMm - p1Mm.yMm) / 10).coerceAtLeast(1)
                    drawContext.canvas.nativeCanvas.drawText(
                        "${wCm}cm × ${hCm}cm",
                        topLeft.x + rectSize.width / 2f,
                        max(36f, topLeft.y - 12f),
                        labelPaint
                    )
                } else if (interactionMode == MapInteractionMode.SPLIT_MERGE) {
                    drawLine(
                        color = Color(0xFFF43F5E),
                        start = dStart,
                        end = dEnd,
                        strokeWidth = 5f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))
                    )
                }
            }
        }

        // Floating Glass Zoom & Recenter Controls on the right edge of the map canvas
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            CircularGlassIconButton(
                icon = Icons.Default.Add,
                contentDescription = "Zoom in map",
                size = 44.dp,
                onClick = { zoom = (zoom * 1.35f).coerceIn(0.5f, 8.0f) }
            )
            CircularGlassIconButton(
                icon = Icons.Default.Remove,
                contentDescription = "Zoom out map",
                size = 44.dp,
                onClick = { zoom = (zoom / 1.35f).coerceIn(0.5f, 8.0f) }
            )
            CircularGlassIconButton(
                icon = Icons.Default.CenterFocusStrong,
                contentDescription = "Reset map zoom and pan",
                isActive = zoom != 1f || panOffset != Offset.Zero,
                size = 44.dp,
                onClick = {
                    zoom = 1f
                    panOffset = Offset.Zero
                }
            )
        }
    }
}

private class MapScreenTransform(
    val canvasW: Float,
    val canvasH: Float,
    val map: VacuumMap,
    val zoom: Float,
    val pan: Offset
) {
    val baseCellPx: Float = min(
        (canvasW * 0.90f) / map.width.coerceAtLeast(1),
        (canvasH * 0.78f) / map.height.coerceAtLeast(1)
    )
    val cellPx: Float = baseCellPx * zoom
    val gridPixelW: Float = map.width * cellPx
    val gridPixelH: Float = map.height * cellPx
    val originX: Float = (canvasW - gridPixelW) / 2f + pan.x
    val originY: Float = (canvasH - gridPixelH) / 2f + pan.y

    /**
     * Note (`FUNC_MAP.md` §2.4): Row 0 is the smallest world Y, while screen Y grows downward,
     * so we flip row vertically (`map.height - 1 - row`).
     */
    fun cellTopLeftScreen(col: Int, row: Int): Offset {
        val sx = originX + col * cellPx
        val sy = originY + (map.height - 1 - row) * cellPx
        return Offset(sx, sy)
    }

    fun worldMmToScreen(ptMm: PointMm): Offset {
        val cell: GridCellF = map.worldMmToCell(ptMm)
        val sx = originX + (cell.col + 0.5f) * cellPx
        val sy = originY + (map.height - 0.5f - cell.row) * cellPx
        return Offset(sx, sy)
    }

    fun screenToWorldMm(screenPt: Offset): PointMm {
        val col = ((screenPt.x - originX) / cellPx) - 0.5f
        val row = (map.height - 0.5f) - ((screenPt.y - originY) / cellPx)
        return map.cellToWorldMm(col, row)
    }
}
