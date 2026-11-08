package com.proscenic.m7.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RotateLeft
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.proscenic.m7.protocol.SuctionLevel
import com.proscenic.m7.protocol.ZoneKind
import com.proscenic.m7.ui.components.CircularGlassIconButton
import com.proscenic.m7.ui.components.FluidButtonVariant
import com.proscenic.m7.ui.components.LivingFlowPillButton
import com.proscenic.m7.ui.components.LivingFlowSegmentedControl
import com.proscenic.m7.ui.components.LivingFlowSwitch
import com.proscenic.m7.ui.components.LuminousCometSlider
import com.proscenic.m7.ui.components.MorphingStatusCapsule
import com.proscenic.m7.ui.theme.GlassMaterialLevel
import com.proscenic.m7.ui.theme.LivingFlowHaptic
import com.proscenic.m7.ui.theme.LivingFlowTheme
import com.proscenic.m7.ui.theme.livingFlowGlass
import com.proscenic.m7.ui.theme.livingFlowPress
import com.proscenic.m7.ui.theme.performLivingFlowHaptic
import com.proscenic.m7.ui.theme.progressiveEdgeScrim

/**
 * ColorOS 17 Living Flow 2D Map & Robot Control Screen.
 *
 * Implements the "Floating Chrome, Calm Content" principle:
 * - Full-bleed interactive 2D LZ4 occupancy grid (`FloorMapCanvas`) fills the entire viewport.
 * - Top and bottom edges feature progressive blur / scrim gradients (`progressiveEdgeScrim`).
 * - Top telemetry capsule (`MorphingStatusCapsule`) + mode segmented control (`LivingFlowSegmentedControl`)
 *   float as `glass.regular` islands with 1dp contour glow.
 * - Bottom control sheet (`glass.thick`, `radius.xl = 32.dp`) floats above the navigation island,
 *   featuring a drag grabber (`36×4dp` pill) that expands/collapses with `spring.morph`.
 */
@Composable
fun MapDashboardScreen(
    uiState: VacuumUiState,
    viewModel: VacuumViewModel,
    showRemoteModal: Boolean,
    onDismissRemoteModal: () -> Unit,
    onOpenRemoteModal: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = LivingFlowTheme.tokens
    val colors = tokens.colors
    val view = LocalView.current

    var sheetExpanded by remember { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxSize()) {
        // 1. Full-bleed 2D Floor Map Canvas underneath floating chrome
        FloorMapCanvas(
            map = uiState.map,
            status = uiState.status,
            path = uiState.path,
            regions = uiState.regions,
            roomNames = uiState.roomNames,
            selectedRoomLabels = uiState.selectedRoomLabels,
            selectedZoneIds = uiState.selectedZoneIds,
            interactionMode = uiState.mapInteractionMode,
            activeZoneDrawKind = uiState.activeZoneDrawKind,
            placedSpotTargetMm = uiState.placedSpotTargetMm,
            mergeFirstRoomLabel = uiState.mergeFirstRoomLabel,
            onRoomTapped = { viewModel.toggleRoomSelection(it) },
            onZoneTapped = { viewModel.toggleZoneSelection(it) },
            onZoneDrawn = { p1, p2 -> viewModel.addDrawnZone(p1, p2) },
            onSpotMoved = { viewModel.setPlacedSpotTarget(it) },
            onRoomSplitLineDrawn = { p1, p2 -> viewModel.splitSelectedRoom(p1, p2) },
            modifier = Modifier.fillMaxSize()
        )

        // 2. Top Progressive Scrim + Floating Glass Header Islands
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .progressiveEdgeScrim(fromTop = true)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Top Row: Expressive Tabular Telemetry Card + Quick Circular Glass Toggles
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Floating Glass Telemetry Card
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .livingFlowGlass(
                            level = GlassMaterialLevel.REGULAR,
                            cornerRadius = tokens.radii.lg,
                            isActive = uiState.status.mode == "sweep"
                        )
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            val dotColor = when {
                                uiState.status.errorCode != 0 -> colors.danger
                                uiState.status.mode == "sweep" -> colors.accentCyan
                                uiState.robotSocketConnected -> colors.success
                                else -> colors.warning
                            }
                            Box(
                                modifier = Modifier
                                    .size(9.dp)
                                    .clip(CircleShape)
                                    .background(dotColor)
                            )
                            Text(
                                text = uiState.status.modeDisplayLabel,
                                style = MaterialTheme.typography.titleSmall,
                                color = colors.textPrimary
                            )
                        }
                        Text(
                            text = if (uiState.robotSocketConnected) {
                                "Robot TCP :8081 Live"
                            } else {
                                "Local Sim • ${uiState.localLanIp}:8080"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.textSecondary
                        )
                    }

                    // Tabular Metrics (Battery %, Area m², Time min)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TelemetryStatItem(
                            value = "${uiState.status.battery}%",
                            label = "BATT",
                            accent = if (uiState.status.battery > 25) colors.success else colors.warning
                        )
                        TelemetryStatItem(
                            value = "${uiState.status.cleanAreaM2}",
                            label = "m²",
                            accent = colors.accentCyan
                        )
                        TelemetryStatItem(
                            value = "${uiState.status.cleanTimeSec / 60}",
                            label = "MIN",
                            accent = colors.textPrimary
                        )
                    }
                }

                // Quick Circular Glass Buttons (Locate Beep & Manual D-Pad)
                CircularGlassIconButton(
                    icon = Icons.Default.VolumeUp,
                    contentDescription = "Locate vacuum beep",
                    size = 48.dp,
                    onClick = { viewModel.locateRobotBeep() }
                )
                CircularGlassIconButton(
                    icon = Icons.Default.Gamepad,
                    contentDescription = "Open manual D-pad remote",
                    isActive = showRemoteModal,
                    size = 48.dp,
                    onClick = onOpenRemoteModal
                )
            }

            // Live-Activity Morphing Capsule Banner for protocol feedback
            MorphingStatusCapsule(
                title = uiState.lastActionBanner,
                subtitle = "Pose: (${uiState.status.posMm.xMm}, ${uiState.status.posMm.yMm}) mm • Suction: ${uiState.status.suction.label} • Water: ${uiState.status.waterLevel}/4 • Path pts: ${uiState.path.points.size}",
                accentDotColor = if (uiState.status.mode == "sweep") colors.accentCyan else colors.accent,
                progressFraction = (uiState.status.battery / 100f).coerceIn(0.05f, 1f),
                modifier = Modifier.fillMaxWidth()
            )

            // Morphing Segmented Glass Pill for Map Interaction Mode
            LivingFlowSegmentedControl(
                items = MapInteractionMode.entries,
                selectedItem = uiState.mapInteractionMode,
                labelProvider = { it.label },
                onSelect = { viewModel.setMapInteractionMode(it) },
                modifier = Modifier.fillMaxWidth()
            )

            // Contextual Floating Glass Sub-Island based on active MapInteractionMode
            MapContextualSubIsland(
                uiState = uiState,
                viewModel = viewModel
            )
        }

        // 3. Floating Glass.Thick Control Sheet above the Bottom Navigation Island
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .progressiveEdgeScrim(fromTop = false)
                .padding(start = 16.dp, end = 16.dp, bottom = 88.dp, top = 12.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .livingFlowGlass(
                        level = GlassMaterialLevel.THICK,
                        cornerRadius = tokens.radii.xl,
                        isActive = uiState.status.mode == "sweep"
                    )
                    .pointerInput(Unit) {
                        detectVerticalDragGestures { _, dragAmount ->
                            if (dragAmount < -12f && !sheetExpanded) {
                                view.performLivingFlowHaptic(LivingFlowHaptic.DETENT_TICK)
                                sheetExpanded = true
                            } else if (dragAmount > 12f && sheetExpanded) {
                                view.performLivingFlowHaptic(LivingFlowHaptic.DETENT_TICK)
                                sheetExpanded = false
                            }
                        }
                    }
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Sheet Grabber Pill (36×4dp per Living Flow spec) + Expand Toggle
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .livingFlowPress(
                            pressScale = 0.98f,
                            onClick = { sheetExpanded = !sheetExpanded }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier
                                .size(width = 36.dp, height = 4.dp)
                                .clip(CircleShape)
                                .background(colors.textTertiary)
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = if (sheetExpanded) "Swipe down to compact controls" else "Swipe up for Suction, Mop Water & Y-Mop",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.textSecondary
                        )
                    }
                }

                // Primary Action Pill Row
                val isCleaning = uiState.status.mode == "sweep"
                val isPaused = uiState.status.mode == "pause"

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Main Smart Clean / Selected Clean Pill
                    val hasSelection = uiState.selectedRoomLabels.isNotEmpty() || uiState.selectedZoneIds.isNotEmpty()
                    LivingFlowPillButton(
                        text = when {
                            hasSelection -> "Clean Selected (${uiState.selectedRoomLabels.size + uiState.selectedZoneIds.size})"
                            else -> "Smart Clean"
                        },
                        icon = Icons.Default.PlayArrow,
                        variant = FluidButtonVariant.PRIMARY,
                        onClick = {
                            if (hasSelection) viewModel.startSelectedRoomsOrZonesClean()
                            else viewModel.startWholeHomeClean(deepClean = false)
                        },
                        modifier = Modifier.weight(1.3f)
                    )

                    // Deep 2x Clean Pill
                    LivingFlowPillButton(
                        text = "Deep 2x",
                        icon = Icons.Default.CleaningServices,
                        variant = FluidButtonVariant.TONAL_ACCENT,
                        onClick = { viewModel.startWholeHomeClean(deepClean = true) },
                        modifier = Modifier.weight(0.95f)
                    )

                    // Pause / Resume Circular Glass Button
                    CircularGlassIconButton(
                        icon = if (isCleaning) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isCleaning) "Pause cleaning" else "Resume cleaning",
                        isActive = isCleaning || isPaused,
                        accentOverride = colors.warning,
                        onClick = { viewModel.pauseOrResumeCleaning() }
                    )

                    // Return to Dock Circular Glass Button
                    CircularGlassIconButton(
                        icon = Icons.Default.Home,
                        contentDescription = "Return to charging dock",
                        isActive = uiState.status.mode == "backcharge",
                        accentOverride = colors.success,
                        onClick = { viewModel.returnToDock() }
                    )
                }

                // Expandable Suction, Mop Water Level (Comet Seek Bar), Y-Mop & Auto-Empty Controls
                AnimatedVisibility(visible = sheetExpanded) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        // Suction Level Segmented Pill (`21022`)
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Suction Power (infoType 21022)",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = colors.textPrimary
                                )
                                Text(
                                    text = uiState.status.suction.label,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = colors.accentCyan
                                )
                            }
                            LivingFlowSegmentedControl(
                                items = SuctionLevel.entries,
                                selectedItem = uiState.status.suction,
                                labelProvider = { it.label },
                                onSelect = { viewModel.setSuctionLevel(it) },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        // Luminous Comet Seek Bar for Mop Water Level (`21024 setWaterPump`)
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.WaterDrop,
                                        contentDescription = null,
                                        tint = colors.accentCyan,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = "Mop Water Flow (21024)",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = colors.textPrimary
                                    )
                                }
                                Text(
                                    text = "Level ${uiState.status.waterLevel} / 4",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = colors.accentCyan
                                )
                            }
                            LuminousCometSlider(
                                value = uiState.status.waterLevel,
                                valueRange = 1..4,
                                onValueChange = { viewModel.setWaterLevel(it) }
                            )
                        }

                        // Y-Shaped Mop Switch + Stop + Dust Station Empty
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                LivingFlowSwitch(
                                    checked = uiState.yShapedMopEnabled,
                                    onCheckedChange = { viewModel.toggleYShapedMop(it) }
                                )
                                Column {
                                    Text(
                                        text = "Y-Shaped Scrubbing",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = colors.textPrimary
                                    )
                                    Text(
                                        text = "21005 cleanType=2",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = colors.textSecondary
                                    )
                                }
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                LivingFlowPillButton(
                                    text = "Empty Bin",
                                    variant = FluidButtonVariant.SECONDARY_GLASS,
                                    onClick = { viewModel.collectDustNow() }
                                )
                                LivingFlowPillButton(
                                    text = "Stop",
                                    icon = Icons.Default.Stop,
                                    variant = FluidButtonVariant.DANGER,
                                    onClick = { viewModel.stopCleaning() }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // 4. Glass.Thick Modal Dialog for Manual D-Pad Remote Control (`21020`)
    if (showRemoteModal) {
        RemoteControlGlassDialog(
            uiState = uiState,
            onStep = { viewModel.sendRemoteControlStep(it) },
            onSpotHere = { viewModel.spotCleanHere() },
            onDismiss = onDismissRemoteModal
        )
    }
}

@Composable
private fun TelemetryStatItem(
    value: String,
    label: String,
    accent: Color
) {
    val colors = LivingFlowTheme.colors
    Column(horizontalAlignment = Alignment.End) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = accent
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 11.sp,
            color = colors.textSecondary
        )
    }
}

@Composable
private fun MapContextualSubIsland(
    uiState: VacuumUiState,
    viewModel: VacuumViewModel
) {
    val tokens = LivingFlowTheme.tokens
    val colors = tokens.colors

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .livingFlowGlass(
                level = GlassMaterialLevel.THIN,
                cornerRadius = tokens.radii.lg
            )
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        when (uiState.mapInteractionMode) {
            MapInteractionMode.SELECT_ROOMS -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Tap rooms:",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textSecondary
                    )
                    uiState.roomNames.forEach { (roomId, name) ->
                        val selected = roomId in uiState.selectedRoomLabels
                        Box(
                            modifier = Modifier
                                .livingFlowGlass(
                                    level = GlassMaterialLevel.THIN,
                                    cornerRadius = tokens.radii.full,
                                    isActive = selected
                                )
                                .background(
                                    if (selected) colors.accent.copy(alpha = 0.28f) else Color.Transparent,
                                    CircleShape
                                )
                                .livingFlowPress(
                                    pressScale = 0.94f,
                                    onClick = { viewModel.toggleRoomSelection(roomId) }
                                )
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = if (selected) "✓ $name" else name,
                                style = MaterialTheme.typography.labelLarge,
                                fontSize = 13.sp,
                                color = if (selected) Color.White else colors.textPrimary
                            )
                        }
                    }
                }
            }

            MapInteractionMode.EDIT_ZONES -> {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Zone Kind Selector
                    val drawKinds = listOf(
                        ZoneKind.CLEAN_NORMAL,
                        ZoneKind.CLEAN_DEEP,
                        ZoneKind.NO_GO,
                        ZoneKind.NO_MOP
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Drag on map:",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.textSecondary
                        )
                        drawKinds.forEach { kind ->
                            val active = uiState.activeZoneDrawKind == kind
                            val tint = if (kind.isForbidden) colors.danger else colors.success
                            Box(
                                modifier = Modifier
                                    .livingFlowGlass(
                                        level = GlassMaterialLevel.THIN,
                                        cornerRadius = tokens.radii.full,
                                        isActive = active,
                                        accentOverride = tint
                                    )
                                    .background(
                                        if (active) tint.copy(alpha = 0.25f) else Color.Transparent,
                                        CircleShape
                                    )
                                    .livingFlowPress(
                                        pressScale = 0.94f,
                                        onClick = { viewModel.setActiveZoneDrawKind(kind) }
                                    )
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = kind.label,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (active) Color.White else colors.textPrimary
                                )
                            }
                        }
                    }

                    // Existing saved zones chips with delete action
                    if (uiState.regions.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            uiState.regions.forEach { reg ->
                                val selected = reg.id in uiState.selectedZoneIds
                                val chipAccent = if (reg.kind.isForbidden) colors.danger else colors.accentCyan
                                Row(
                                    modifier = Modifier
                                        .livingFlowGlass(
                                            level = GlassMaterialLevel.THIN,
                                            cornerRadius = tokens.radii.full,
                                            isActive = selected,
                                            accentOverride = chipAccent
                                        )
                                        .livingFlowPress(
                                            pressScale = 0.95f,
                                            onClick = { viewModel.toggleZoneSelection(reg.id) }
                                        )
                                        .padding(start = 12.dp, end = 8.dp, top = 5.dp, bottom = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = if (selected) "✓ ${reg.safeName}" else reg.safeName,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = colors.textPrimary
                                    )
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Delete zone ${reg.safeName}",
                                        tint = colors.textSecondary,
                                        modifier = Modifier
                                            .size(16.dp)
                                            .livingFlowPress(
                                                pressScale = 0.88f,
                                                onClick = { viewModel.deleteZone(reg.id) }
                                            )
                                    )
                                }
                            }
                        }
                    }
                }
            }

            MapInteractionMode.PLACED_SPOT -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "1.5m Spot Target: (${uiState.placedSpotTargetMm.xMm}, ${uiState.placedSpotTargetMm.yMm}) mm",
                            style = MaterialTheme.typography.labelLarge,
                            color = colors.textPrimary
                        )
                        Text(
                            text = "Tap or drag purple box on map • Sends 21023 + 21005 appointClean",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.textSecondary
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        LivingFlowSwitch(
                            checked = uiState.placedSpotDeepClean,
                            onCheckedChange = { viewModel.togglePlacedSpotDeepClean(it) }
                        )
                        Text(
                            text = "2x",
                            style = MaterialTheme.typography.labelLarge,
                            color = colors.textPrimary
                        )
                        LivingFlowPillButton(
                            text = "Clean Spot",
                            icon = Icons.Default.MyLocation,
                            variant = FluidButtonVariant.PRIMARY,
                            onClick = { viewModel.startPlacedSpotClean() }
                        )
                    }
                }
            }

            MapInteractionMode.SPLIT_MERGE -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (uiState.mergeFirstRoomLabel != null) {
                                "Room #${uiState.mergeFirstRoomLabel} active — tap neighbor to Merge, or drag line to Split"
                            } else {
                                "Tap 2 rooms to Merge (21030), or drag across a room to Split"
                            },
                            style = MaterialTheme.typography.labelLarge,
                            color = colors.textPrimary
                        )
                        Text(
                            text = "Watershed auto-segmentation autoAreaId=${uiState.map.autoAreaId}",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.textSecondary
                        )
                    }
                    LivingFlowPillButton(
                        text = "Reset Rooms",
                        icon = Icons.Default.Refresh,
                        variant = FluidButtonVariant.SECONDARY_GLASS,
                        onClick = { viewModel.resetRoomSegmentation() }
                    )
                }
            }
        }
    }
}

/**
 * **Glass.Thick D-Pad Manual Remote Control Modal (`COMPONENT RULES` §10 & `infoType: 21020`):**
 * - Centered `glass.thick` card with `radius.xl = 32.dp` and contour glow.
 * - Sends `ctrlCode` `3005` (Forward), `3006` (Backward), `3007` (Rotate Left), `3008` (Rotate Right),
 *   `3001` (Spot Clean Here), and `4000` (Exit Remote Mode).
 */
@Composable
private fun RemoteControlGlassDialog(
    uiState: VacuumUiState,
    onStep: (Int) -> Unit,
    onSpotHere: () -> Unit,
    onDismiss: () -> Unit
) {
    val tokens = LivingFlowTheme.tokens
    val colors = tokens.colors

    Dialog(onDismissRequest = {
        onStep(4000)
        onDismiss()
    }) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .livingFlowGlass(
                    level = GlassMaterialLevel.THICK,
                    cornerRadius = tokens.radii.xl,
                    isActive = true
                )
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Manual D-Pad Remote",
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.textPrimary
                    )
                    Text(
                        text = "Channel-B infoType 21020 • Pose (${uiState.status.posMm.xMm}, ${uiState.status.posMm.yMm}) mm",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textSecondary
                    )
                }
                CircularGlassIconButton(
                    icon = Icons.Default.Close,
                    contentDescription = "Close remote",
                    size = 40.dp,
                    onClick = {
                        onStep(4000)
                        onDismiss()
                    }
                )
            }

            // D-Pad Cluster with elastic press & rebound
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CircularGlassIconButton(
                    icon = Icons.Default.ArrowUpward,
                    contentDescription = "Forward (+0.3 m/s, ctrlCode 3005)",
                    isActive = true,
                    size = 64.dp,
                    onClick = { onStep(3005) }
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularGlassIconButton(
                        icon = Icons.Default.RotateLeft,
                        contentDescription = "Turn Left (ctrlCode 3007)",
                        size = 58.dp,
                        onClick = { onStep(3007) }
                    )
                    CircularGlassIconButton(
                        icon = Icons.Default.MyLocation,
                        contentDescription = "Spot clean 1.5m here (ctrlCode 3001)",
                        isActive = true,
                        accentOverride = colors.accentCyan,
                        size = 58.dp,
                        onClick = onSpotHere
                    )
                    CircularGlassIconButton(
                        icon = Icons.Default.RotateRight,
                        contentDescription = "Turn Right (ctrlCode 3008)",
                        size = 58.dp,
                        onClick = { onStep(3008) }
                    )
                }
                CircularGlassIconButton(
                    icon = Icons.Default.ArrowDownward,
                    contentDescription = "Backward (-0.1 m/s, ctrlCode 3006)",
                    size = 64.dp,
                    onClick = { onStep(3006) }
                )
            }

            LivingFlowPillButton(
                text = "Exit Remote Mode (ctrlCode 4000)",
                variant = FluidButtonVariant.SECONDARY_GLASS,
                onClick = {
                    onStep(4000)
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
