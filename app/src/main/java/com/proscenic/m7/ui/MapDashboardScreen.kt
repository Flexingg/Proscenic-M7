package com.proscenic.m7.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.RotateLeft
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.proscenic.m7.ai.ByokProvider
import com.proscenic.m7.protocol.SuctionLevel
import com.proscenic.m7.protocol.ZoneKind
import com.proscenic.m7.ui.components.CircularGlassIconButton
import com.proscenic.m7.ui.components.FluidButtonVariant
import com.proscenic.m7.ui.components.InsetGroupedCalmCard
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
 * ColorOS 17 Living Flow 2D Map & Real Robot Control Screen (Zero Demo / Zero Simulator).
 *
 * - Displays ONLY real telemetry and real `infoType: 20002` LZ4 floor maps uploaded by the physical
 *   Proscenic M7 vacuum over TCP `:8081` and HTTP `:8080`.
 * - Supports full multi-touch pinch-zoom & pan, 1-tap high-res Map Download (PNG + JSON), and
 *   BYOK AI Map Art (Gemini / Claude / OpenAI) that turns a real-world photo into a scaled top-down
 *   orthographic floor-plan sprite.
 */
@Composable
fun MapDashboardScreen(
    uiState: VacuumUiState,
    viewModel: VacuumViewModel,
    showRemoteModal: Boolean,
    onDismissRemoteModal: () -> Unit,
    onOpenRemoteModal: () -> Unit,
    onNavigateToPairing: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val tokens = LivingFlowTheme.tokens
    val colors = tokens.colors
    val view = LocalView.current
    val context = LocalContext.current

    var sheetExpanded by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bgCanvas)
    ) {
        // 1. Real 2D Floor Map Canvas (only rendered when the physical robot has uploaded a 20002 map)
        val realMap = uiState.map
        if (realMap != null) {
            FloorMapCanvas(
                map = realMap,
                status = uiState.status,
                path = uiState.path,
                regions = uiState.regions,
                roomNames = uiState.roomNames,
                selectedRoomLabels = uiState.selectedRoomLabels,
                selectedZoneIds = uiState.selectedZoneIds,
                aiArtItems = uiState.aiArtItems,
                selectedAiArtId = uiState.selectedAiArtId,
                interactionMode = uiState.mapInteractionMode,
                activeZoneDrawKind = uiState.activeZoneDrawKind,
                placedSpotTargetMm = uiState.placedSpotTargetMm,
                mergeFirstRoomLabel = uiState.mergeFirstRoomLabel,
                onRoomTapped = { viewModel.toggleRoomSelection(it) },
                onZoneTapped = { viewModel.toggleZoneSelection(it) },
                onZoneDrawn = { p1, p2 -> viewModel.addDrawnZone(p1, p2) },
                onSpotMoved = { viewModel.setPlacedSpotTarget(it) },
                onRoomSplitLineDrawn = { p1, p2 -> viewModel.splitSelectedRoom(p1, p2) },
                onAiArtTapped = { viewModel.selectAiArtItem(it) },
                onAiArtMoved = { id, pos -> viewModel.moveAiArtItem(id, pos) },
                onAiBoxDrawn = { p1, p2 -> viewModel.onAiFootprintBoxDrawn(p1, p2) },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            // Real Connection Diagnostic State (when waiting for robot connection or first 20002 map upload)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.Center
            ) {
                InsetGroupedCalmCard(
                    title = if (uiState.robotSocketConnected) {
                        "Robot Connected — Waiting for 2D Map Upload"
                    } else {
                        "No Robot Connected Yet"
                    },
                    subtitle = if (uiState.robotSocketConnected) {
                        "Connected to ${uiState.connectedRobotSn ?: "Proscenic M7"} at ${uiState.connectedRobotAddress ?: "TCP :8081"} (Heartbeats: ${uiState.heartbeatCount})"
                    } else {
                        "Listening on ${uiState.localLanIp}:8080 (HTTP) & :8081 (TCP Push Gateway)"
                    },
                    isActive = uiState.robotSocketConnected
                ) {
                    if (uiState.robotSocketConnected) {
                        Text(
                            text = "Your vacuum is connected to this phone over TCP :8081! Tap 'Beep Robot (Test)' to verify instant physical control, or 'Request Map Now' / start a clean so the vacuum uploads its 2D Lidar map (infoType 20002).",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.textSecondary
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            LivingFlowPillButton(
                                text = "Beep Robot (Test)",
                                icon = Icons.Default.VolumeUp,
                                variant = FluidButtonVariant.TONAL_ACCENT,
                                onClick = { viewModel.locateRobotBeep() },
                                modifier = Modifier.weight(1f)
                            )
                            LivingFlowPillButton(
                                text = "Request Map Now",
                                icon = Icons.Default.Refresh,
                                variant = FluidButtonVariant.PRIMARY,
                                onClick = { viewModel.refreshRobotTelemetryNow() },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    } else {
                        Text(
                            text = "Your phone is listening for your Proscenic M7 on ${uiState.localLanIp}:8081.\n\n" +
                                "• If you already paired the robot to your home Wi-Fi ('${uiState.savedWifiSsid}') and your phone's IP changed, tap 'Scan & Reconnect on Home Wi-Fi' below!\n" +
                                "• For initial setup, open 'Pairing & Diagnostics'.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.textSecondary
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            LivingFlowPillButton(
                                text = if (uiState.rehomeInProgress) "Scanning LAN..." else "Scan & Reconnect on Home Wi-Fi",
                                icon = Icons.Default.Refresh,
                                variant = FluidButtonVariant.PRIMARY,
                                enabled = !uiState.rehomeInProgress,
                                onClick = { viewModel.reconnectRobotOnLanNow() },
                                modifier = Modifier.weight(1.2f)
                            )
                            LivingFlowPillButton(
                                text = "Pairing Tab",
                                variant = FluidButtonVariant.SECONDARY_GLASS,
                                onClick = onNavigateToPairing,
                                modifier = Modifier.weight(0.8f)
                            )
                        }
                    }
                }
            }
        }

        // 2. Top Progressive Scrim + Floating Glass Header Islands
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .progressiveEdgeScrim(fromTop = true)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Top Row: Real Connection & Telemetry Card + Quick Circular Glass Toggles
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .livingFlowGlass(
                            level = GlassMaterialLevel.REGULAR,
                            cornerRadius = tokens.radii.lg,
                            isActive = uiState.robotSocketConnected
                        )
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            val dotColor = when {
                                !uiState.robotSocketConnected -> colors.danger
                                uiState.status.isSleepOrShutdown -> colors.warning
                                uiState.status.errorCode != 0 -> colors.warning
                                uiState.status.mode == "sweep" -> colors.accentCyan
                                else -> colors.success
                            }
                            Box(
                                modifier = Modifier
                                    .size(9.dp)
                                    .clip(CircleShape)
                                    .background(dotColor)
                            )
                            Text(
                                text = if (uiState.robotSocketConnected) {
                                    uiState.status.modeDisplayLabel
                                } else {
                                    "DISCONNECTED"
                                },
                                style = MaterialTheme.typography.titleSmall,
                                color = colors.textPrimary
                            )
                        }
                        Text(
                            text = if (uiState.robotSocketConnected) {
                                "ONLINE • SN ${uiState.connectedRobotSn ?: uiState.status.sn.ifBlank { "Connected" }} • Pings: ${uiState.heartbeatCount}"
                            } else {
                                "Waiting on ${uiState.localLanIp}:8081 (TCP)"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (uiState.robotSocketConnected) colors.success else colors.textSecondary
                        )
                    }

                    // Tabular Metrics (shown as '--' when offline)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TelemetryStatItem(
                            value = if (uiState.robotSocketConnected) "${uiState.status.battery}%" else "--%",
                            label = "BATT",
                            accent = if (uiState.robotSocketConnected) colors.success else colors.textTertiary
                        )
                        TelemetryStatItem(
                            value = if (uiState.robotSocketConnected) "${uiState.status.cleanAreaM2}" else "--",
                            label = "m²",
                            accent = if (uiState.robotSocketConnected) colors.accentCyan else colors.textTertiary
                        )
                        TelemetryStatItem(
                            value = if (uiState.robotSocketConnected) "${uiState.status.cleanTimeSec / 60}" else "--",
                            label = "MIN",
                            accent = if (uiState.robotSocketConnected) colors.textPrimary else colors.textTertiary
                        )
                    }
                }

                // Quick Circular Glass Buttons (Download Map, Locate Beep & Manual D-Pad)
                CircularGlassIconButton(
                    icon = Icons.Default.Download,
                    contentDescription = "Download 2D floor map (PNG & JSON)",
                    isActive = realMap != null,
                    accentOverride = colors.accentCyan,
                    size = 46.dp,
                    onClick = { viewModel.downloadMapToDevice(context) }
                )
                CircularGlassIconButton(
                    icon = Icons.Default.VolumeUp,
                    contentDescription = "Locate vacuum beep (test control)",
                    isActive = uiState.robotSocketConnected,
                    size = 46.dp,
                    onClick = { viewModel.locateRobotBeep() }
                )
                CircularGlassIconButton(
                    icon = Icons.Default.Gamepad,
                    contentDescription = "Open manual D-pad remote",
                    isActive = showRemoteModal,
                    size = 46.dp,
                    onClick = onOpenRemoteModal
                )
            }

            // Live-Activity Morphing Capsule Banner for real protocol feedback
            MorphingStatusCapsule(
                title = uiState.lastActionBanner,
                subtitle = if (uiState.robotSocketConnected) {
                    "Robot IP: ${uiState.connectedRobotAddress ?: "connected"} • Pose: (${uiState.status.posMm.xMm}, ${uiState.status.posMm.yMm}) mm • Suction: ${uiState.status.suction.label} • Water: ${uiState.status.waterLevel}/4"
                } else {
                    "No TCP client on ${uiState.localLanIp}:8081 yet. Tap 'Scan & Reconnect' or open 'Pairing & HA'."
                },
                accentDotColor = when {
                    !uiState.robotSocketConnected -> colors.danger
                    uiState.status.isSleepOrShutdown -> colors.warning
                    else -> colors.success
                },
                progressFraction = if (uiState.robotSocketConnected) (uiState.status.battery / 100f).coerceIn(0.05f, 1f) else null,
                modifier = Modifier.fillMaxWidth()
            )

            // Prominent Wake Bar when the robot is in 'shutdown' / 'dormant' sleep mode (like Screenshot 2 at 7:38)
            if (uiState.robotSocketConnected && uiState.status.isSleepOrShutdown) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .livingFlowGlass(
                            level = GlassMaterialLevel.REGULAR,
                            cornerRadius = tokens.radii.lg,
                            isActive = true,
                            accentOverride = colors.warning
                        )
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Robot is in '${uiState.status.mode}' Sleep State",
                            style = MaterialTheme.typography.labelLarge,
                            color = colors.warning,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Tap 'Wake Robot' below, or press the physical Power button on the vacuum (or place it on its charging dock) so it wakes to 'Ready' and accepts motor commands.",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.textPrimary
                        )
                    }
                    LivingFlowPillButton(
                        text = "Wake Robot",
                        icon = Icons.Default.PlayArrow,
                        variant = FluidButtonVariant.PRIMARY,
                        onClick = { viewModel.wakeRobotFromStandby() }
                    )
                }
            }

            // Only show map interaction toolbar when a real map is present
            if (realMap != null) {
                LivingFlowSegmentedControl(
                    items = MapInteractionMode.entries,
                    selectedItem = uiState.mapInteractionMode,
                    labelProvider = { it.label },
                    onSelect = { viewModel.setMapInteractionMode(it) },
                    modifier = Modifier.fillMaxWidth()
                )

                MapContextualSubIsland(
                    uiState = uiState,
                    viewModel = viewModel
                )
            }
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
                        isActive = uiState.robotSocketConnected
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

                    LivingFlowPillButton(
                        text = "Deep 2x",
                        icon = Icons.Default.CleaningServices,
                        variant = FluidButtonVariant.TONAL_ACCENT,
                        onClick = { viewModel.startWholeHomeClean(deepClean = true) },
                        modifier = Modifier.weight(0.95f)
                    )

                    CircularGlassIconButton(
                        icon = if (isCleaning) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isCleaning) "Pause cleaning" else "Resume cleaning",
                        isActive = isCleaning || isPaused,
                        accentOverride = colors.warning,
                        onClick = { viewModel.pauseOrResumeCleaning() }
                    )

                    CircularGlassIconButton(
                        icon = Icons.Default.Home,
                        contentDescription = "Return to charging dock",
                        isActive = uiState.status.mode == "backcharge",
                        accentOverride = colors.success,
                        onClick = { viewModel.returnToDock() }
                    )
                }

                AnimatedVisibility(visible = sheetExpanded) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                                        text = "21005 pathType=y_word",
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

    if (showRemoteModal) {
        RemoteControlGlassDialog(
            uiState = uiState,
            onStep = { viewModel.sendRemoteControlStep(it) },
            onSpotHere = { viewModel.spotCleanHere() },
            onBeep = { viewModel.locateRobotBeep() },
            onDismiss = onDismissRemoteModal
        )
    }

    if (uiState.showAiArtStudioModal) {
        AiMapArtStudioDialog(
            uiState = uiState,
            viewModel = viewModel
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
    val context = LocalContext.current

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
                        text = if (uiState.roomNames.isEmpty()) "No segmented rooms in map yet:" else "Tap rooms:",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textSecondary
                    )
                    if (uiState.roomNames.isEmpty()) {
                        LivingFlowPillButton(
                            text = "Segment Rooms Now (21030)",
                            icon = Icons.Default.Refresh,
                            variant = FluidButtonVariant.TONAL_ACCENT,
                            onClick = { viewModel.resetRoomSegmentation() }
                        )
                    }
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

            MapInteractionMode.AI_MAP_ART -> {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        LivingFlowPillButton(
                            text = "Snap Object Photo (AI)",
                            icon = Icons.Default.CameraAlt,
                            variant = FluidButtonVariant.PRIMARY,
                            onClick = { viewModel.openAiArtStudioModal() }
                        )
                        LivingFlowPillButton(
                            text = "Download Map",
                            icon = Icons.Default.Download,
                            variant = FluidButtonVariant.TONAL_ACCENT,
                            onClick = { viewModel.downloadMapToDevice(context) }
                        )
                        Text(
                            text = "Drag a box on the map where the object sits, or tap an object to move/rotate",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.textSecondary
                        )
                    }

                    val selectedItem = uiState.aiArtItems.firstOrNull { it.id == uiState.selectedAiArtId }
                    if (selectedItem != null) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Selected: ${selectedItem.label} (${selectedItem.widthCm}×${selectedItem.heightCm}cm)",
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.accentCyan,
                                fontWeight = FontWeight.SemiBold
                            )
                            LivingFlowPillButton(
                                text = "↺ -15°",
                                variant = FluidButtonVariant.SECONDARY_GLASS,
                                onClick = { viewModel.rotateSelectedAiArt(-15f) }
                            )
                            LivingFlowPillButton(
                                text = "↻ +15°",
                                variant = FluidButtonVariant.SECONDARY_GLASS,
                                onClick = { viewModel.rotateSelectedAiArt(15f) }
                            )
                            LivingFlowPillButton(
                                text = "90°",
                                variant = FluidButtonVariant.SECONDARY_GLASS,
                                onClick = { viewModel.rotateSelectedAiArt(90f) }
                            )
                            LivingFlowPillButton(
                                text = "Scale -",
                                icon = Icons.Default.Remove,
                                variant = FluidButtonVariant.SECONDARY_GLASS,
                                onClick = { viewModel.resizeSelectedAiArt(0.9f) }
                            )
                            LivingFlowPillButton(
                                text = "Scale +",
                                icon = Icons.Default.Add,
                                variant = FluidButtonVariant.SECONDARY_GLASS,
                                onClick = { viewModel.resizeSelectedAiArt(1.1f) }
                            )
                            LivingFlowPillButton(
                                text = "Delete",
                                icon = Icons.Default.Delete,
                                variant = FluidButtonVariant.DANGER,
                                onClick = { viewModel.deleteSelectedAiArt() }
                            )
                        }
                    } else if (uiState.aiArtItems.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Placed Objects (${uiState.aiArtItems.size}):",
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.textSecondary
                            )
                            uiState.aiArtItems.forEach { item ->
                                Box(
                                    modifier = Modifier
                                        .livingFlowGlass(
                                            level = GlassMaterialLevel.THIN,
                                            cornerRadius = tokens.radii.full,
                                            isActive = false
                                        )
                                        .livingFlowPress(
                                            pressScale = 0.94f,
                                            onClick = { viewModel.selectAiArtItem(item.id) }
                                        )
                                        .padding(horizontal = 12.dp, vertical = 5.dp)
                                ) {
                                    Text(
                                        text = "${item.label} (${item.widthCm}×${item.heightCm}cm)",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = colors.textPrimary
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
                            text = "Watershed auto-segmentation autoAreaId=${uiState.map?.autoAreaId ?: 0}",
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

@Composable
private fun RemoteControlGlassDialog(
    uiState: VacuumUiState,
    onStep: (Int) -> Unit,
    onSpotHere: () -> Unit,
    onBeep: () -> Unit,
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
                    isActive = uiState.robotSocketConnected
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
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Manual D-Pad Remote",
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.textPrimary
                    )
                    Text(
                        text = if (uiState.robotSocketConnected) {
                            "LIVE (${uiState.connectedRobotAddress}) • infoType 21020"
                        } else {
                            "OFFLINE — Robot not connected to TCP :8081"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (uiState.robotSocketConnected) colors.success else colors.danger
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

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CircularGlassIconButton(
                    icon = Icons.Default.ArrowUpward,
                    contentDescription = "Forward (+0.3 m/s, ctrlCode 3005)",
                    isActive = uiState.robotSocketConnected,
                    size = 64.dp,
                    onClick = { onStep(3005) }
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularGlassIconButton(
                        icon = Icons.Default.RotateLeft,
                        contentDescription = "Turn Left (CCW, ctrlCode 3008)",
                        size = 58.dp,
                        onClick = { onStep(3008) }
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
                        contentDescription = "Turn Right (CW, ctrlCode 3007)",
                        size = 58.dp,
                        onClick = { onStep(3007) }
                    )
                }
                CircularGlassIconButton(
                    icon = Icons.Default.ArrowDownward,
                    contentDescription = "Backward (-0.1 m/s, ctrlCode 3006)",
                    size = 64.dp,
                    onClick = { onStep(3006) }
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                LivingFlowPillButton(
                    text = "Beep Test (3010)",
                    icon = Icons.Default.VolumeUp,
                    variant = FluidButtonVariant.TONAL_ACCENT,
                    onClick = onBeep,
                    modifier = Modifier.weight(1f)
                )
                LivingFlowPillButton(
                    text = "Exit (4000)",
                    variant = FluidButtonVariant.SECONDARY_GLASS,
                    onClick = {
                        onStep(4000)
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

private data class ObjectPreset(
    val label: String,
    val widthCm: Int,
    val depthCm: Int
)

private val QUICK_OBJECT_PRESETS = listOf(
    ObjectPreset("Sofa", 210, 90),
    ObjectPreset("Queen Bed", 200, 150),
    ObjectPreset("Dining Table", 180, 90),
    ObjectPreset("Coffee Table", 120, 60),
    ObjectPreset("Area Rug", 240, 170),
    ObjectPreset("Office Desk", 140, 75),
    ObjectPreset("Plant Pot", 45, 45),
    ObjectPreset("Pet Bed", 75, 60),
    ObjectPreset("TV Stand", 160, 45)
)

@Composable
private fun AiMapArtStudioDialog(
    uiState: VacuumUiState,
    viewModel: VacuumViewModel
) {
    val tokens = LivingFlowTheme.tokens
    val colors = tokens.colors
    val context = LocalContext.current

    var capturedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var objectLabel by remember { mutableStateOf("Sofa") }
    var widthCmText by remember(uiState.pendingAiBoxWidthCm) {
        mutableStateOf(uiState.pendingAiBoxWidthCm.toString())
    }
    var depthCmText by remember(uiState.pendingAiBoxDepthCm) {
        mutableStateOf(uiState.pendingAiBoxDepthCm.toString())
    }
    var rotationDeg by remember { mutableStateOf(0f) }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { bmp ->
        if (bmp != null) {
            capturedBitmap = bmp
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream)
                }
            }.getOrNull()?.let { bmp ->
                capturedBitmap = bmp
            }
        }
    }

    val activeProvider = uiState.byokProvider
    val activeKey = when (activeProvider) {
        ByokProvider.GEMINI -> uiState.geminiApiKey
        ByokProvider.CLAUDE -> uiState.claudeApiKey
        ByokProvider.OPENAI -> uiState.openAiApiKey
    }

    val textFieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = colors.textPrimary,
        unfocusedTextColor = colors.textPrimary,
        focusedBorderColor = colors.accentCyan,
        unfocusedBorderColor = colors.divider,
        focusedLabelColor = colors.accentCyan,
        unfocusedLabelColor = colors.textSecondary
    )

    Dialog(onDismissRequest = {
        if (!uiState.aiGenerationInProgress) {
            viewModel.dismissAiArtStudioModal()
        }
    }) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .livingFlowGlass(
                    level = GlassMaterialLevel.THICK,
                    cornerRadius = tokens.radii.xl,
                    isActive = true
                )
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "AI Map Art Studio (BYOK)",
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.textPrimary
                    )
                    Text(
                        text = "Snap a real-world object → AI creates a scaled top-down sprite on your floor map",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textSecondary
                    )
                }
                CircularGlassIconButton(
                    icon = Icons.Default.Close,
                    contentDescription = "Close AI Map Art Studio",
                    size = 38.dp,
                    onClick = {
                        if (!uiState.aiGenerationInProgress) {
                            viewModel.dismissAiArtStudioModal()
                        }
                    }
                )
            }

            // 1. Real-World Object Photo Capture / Gallery Picker
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(145.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(colors.surfaceRaised),
                contentAlignment = Alignment.Center
            ) {
                val bmp = capturedBitmap
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "Captured real-world object",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.padding(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CameraAlt,
                            contentDescription = null,
                            tint = colors.accentCyan,
                            modifier = Modifier.size(32.dp)
                        )
                        Text(
                            text = "Take a photo of your real-world furniture/object so AI matches its exact colors, texture & shape!",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.textSecondary,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                LivingFlowPillButton(
                    text = if (capturedBitmap == null) "Take Photo" else "Retake Photo",
                    icon = Icons.Default.CameraAlt,
                    variant = FluidButtonVariant.PRIMARY,
                    onClick = { cameraLauncher.launch(null) },
                    modifier = Modifier.weight(1f)
                )
                LivingFlowPillButton(
                    text = "Pick Photo",
                    icon = Icons.Default.Image,
                    variant = FluidButtonVariant.SECONDARY_GLASS,
                    onClick = { galleryLauncher.launch("image/*") },
                    modifier = Modifier.weight(1f)
                )
            }

            // 2. Quick Object Presets
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                QUICK_OBJECT_PRESETS.forEach { preset ->
                    val selected = objectLabel.equals(preset.label, ignoreCase = true)
                    Box(
                        modifier = Modifier
                            .livingFlowGlass(
                                level = GlassMaterialLevel.THIN,
                                cornerRadius = tokens.radii.full,
                                isActive = selected
                            )
                            .livingFlowPress(
                                pressScale = 0.94f,
                                onClick = {
                                    objectLabel = preset.label
                                    widthCmText = preset.widthCm.toString()
                                    depthCmText = preset.depthCm.toString()
                                }
                            )
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Text(
                            text = "${preset.label} (${preset.widthCm}×${preset.depthCm}cm)",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (selected) colors.accentCyan else colors.textPrimary
                        )
                    }
                }
            }

            // 3. Object Label & Real-World Footprint Dimensions (cm)
            OutlinedTextField(
                value = objectLabel,
                onValueChange = { objectLabel = it },
                label = { Text("Object Name / Description (e.g. Navy L-Sofa, Oak Dining Table)") },
                singleLine = true,
                colors = textFieldColors,
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = widthCmText,
                    onValueChange = { widthCmText = it.filter { c -> c.isDigit() } },
                    label = { Text("Width (cm)") },
                    singleLine = true,
                    colors = textFieldColors,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = depthCmText,
                    onValueChange = { depthCmText = it.filter { c -> c.isDigit() } },
                    label = { Text("Depth (cm)") },
                    singleLine = true,
                    colors = textFieldColors,
                    modifier = Modifier.weight(1f)
                )
            }

            // Rotation selector
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Map Orientation: ${rotationDeg.toInt()}°",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.textPrimary
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0f, 90f, 180f, 270f).forEach { deg ->
                        LivingFlowPillButton(
                            text = "${deg.toInt()}°",
                            variant = if (rotationDeg == deg) FluidButtonVariant.TONAL_ACCENT else FluidButtonVariant.SECONDARY_GLASS,
                            onClick = { rotationDeg = deg }
                        )
                    }
                }
            }

            // 4. BYOK Provider & API Key
            Text(
                text = "BYOK AI Provider (Gemini / Claude / OpenAI)",
                style = MaterialTheme.typography.labelLarge,
                color = colors.textPrimary
            )
            LivingFlowSegmentedControl(
                items = ByokProvider.entries,
                selectedItem = activeProvider,
                labelProvider = { it.displayName.substringBefore(" ") },
                onSelect = { viewModel.setByokProvider(it) },
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = activeKey,
                onValueChange = { viewModel.setByokApiKey(activeProvider, it) },
                label = { Text("${activeProvider.displayName} API Key (${activeProvider.modelHint})") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                colors = textFieldColors,
                modifier = Modifier.fillMaxWidth()
            )

            if (!uiState.aiGenerationStatus.isNullOrBlank()) {
                Text(
                    text = uiState.aiGenerationStatus,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (uiState.aiGenerationStatus.startsWith("Error")) colors.danger else colors.accentCyan
                )
            }

            // 5. Generate Action Button
            LivingFlowPillButton(
                text = if (uiState.aiGenerationInProgress) {
                    "Generating Top-Down Art..."
                } else {
                    "Generate Top-Down Map Art"
                },
                icon = Icons.Default.AutoAwesome,
                variant = FluidButtonVariant.PRIMARY,
                enabled = !uiState.aiGenerationInProgress,
                onClick = {
                    val wCm = widthCmText.toIntOrNull() ?: uiState.pendingAiBoxWidthCm
                    val dCm = depthCmText.toIntOrNull() ?: uiState.pendingAiBoxDepthCm
                    viewModel.generateAiMapArt(
                        photoBitmap = capturedBitmap,
                        objectLabel = objectLabel,
                        widthCm = wCm,
                        depthCm = dCm,
                        rotationDeg = rotationDeg
                    )
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

