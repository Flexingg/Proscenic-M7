package com.proscenic.m7.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.proscenic.m7.ui.components.FluidButtonVariant
import com.proscenic.m7.ui.components.InsetGroupedCalmCard
import com.proscenic.m7.ui.components.LivingFlowPillButton
import com.proscenic.m7.ui.components.LivingFlowSegmentedControl
import com.proscenic.m7.ui.components.LivingFlowSwitch
import com.proscenic.m7.ui.theme.GlassMaterialLevel
import com.proscenic.m7.ui.theme.LivingFlowTheme
import com.proscenic.m7.ui.theme.livingFlowGlass
import com.proscenic.m7.ui.theme.progressiveEdgeScrim

/**
 * ColorOS 17 Living Flow Real-Hardware Connection Verification, SoftAP Re-homing Wizard,
 * Home Assistant Bridge & Accessibility Screen.
 *
 * Includes:
 * 1. Live Robot Connection Verification & Physical Control Test Card (`ONLINE` vs `DISCONNECTED`,
 *    robot SN, remote IP:port, heartbeat ping counter, and instant **Beep Robot (3010)** / **Request Map** buttons).
 * 2. SoftAP UDP `7913` Re-homing Wizard (`RehomeClient`) with automatic preservation of the phone's
 *    Home Wi-Fi IP (`192.168.1.x`) when switching to the `Proscenic-*` SoftAP (`192.168.78.x`),
 *    and clear guardrails against entering `Proscenic-*` into the Home 2.4GHz Wi-Fi SSID field.
 * 3. Copyable Home Assistant `configuration.yaml` REST integration snippet.
 * 4. ColorOS 17 Living Flow Accessibility & Visual Guardrails Card.
 * 5. Live Channel-A HTTP (`:8080`) & Channel-B TCP (`:8081`) Wire Trace Console.
 */
@Composable
fun RehomeAndHaScreen(
    uiState: VacuumUiState,
    viewModel: VacuumViewModel,
    modifier: Modifier = Modifier
) {
    val tokens = LivingFlowTheme.tokens
    val colors = tokens.colors
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val isScrolled by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 36 }
    }

    var robotApIp by remember { mutableStateOf("192.168.78.1") }
    var serverHostIp by remember {
        mutableStateOf(
            if (uiState.localLanIp.startsWith("192.168.78.")) "192.168.1.110" else uiState.localLanIp
        )
    }
    // Preserve the phone's Home LAN IP whenever the phone is on home Wi-Fi (not 192.168.78.x SoftAP)
    LaunchedEffect(uiState.localLanIp) {
        if (!uiState.localLanIp.startsWith("192.168.78.") && uiState.localLanIp != "0.0.0.0") {
            serverHostIp = uiState.localLanIp
        }
    }

    var wifiSsid by remember { mutableStateOf("") }
    var wifiPassword by remember { mutableStateOf("") }
    var userId by remember { mutableStateOf("local_user") }
    var copiedToast by remember { mutableStateOf(false) }

    val onRobotSoftAp = uiState.localLanIp.startsWith("192.168.78.")

    val haYamlSnippet = remember(serverHostIp) {
        """
        # Proscenic M7 / M7 Pro 100% Local Home Assistant Bridge
        rest:
          - resource: http://$serverHostIp:8080/api/status
            scan_interval: 5
            sensor:
              - name: "Proscenic M7 Status"
                value_template: "{{ value_json.modeDisplay }}"
                json_attributes:
                  - battery
                  - suction
                  - waterLevel
                  - cleanAreaM2
                  - cleanTimeSec
                  - posXMm
                  - posYMm
                  - connected

        rest_command:
          proscenic_m7_start:
            url: "http://$serverHostIp:8080/api/command"
            method: POST
            content_type: "application/json"
            payload: '{"action": "start"}'
          proscenic_m7_dock:
            url: "http://$serverHostIp:8080/api/command"
            method: POST
            content_type: "application/json"
            payload: '{"action": "return_to_base"}'
          proscenic_m7_clean_rooms:
            url: "http://$serverHostIp:8080/api/command"
            method: POST
            content_type: "application/json"
            payload: '{"action": "clean_rooms", "rooms": {{ rooms }}}'
        """.trimIndent()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bgBase)
    ) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 24.dp,
                bottom = 128.dp
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            // 1. Large Header
            item {
                Column(modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)) {
                    Text(
                        text = "Pairing & Live Diagnostics",
                        style = MaterialTheme.typography.titleLarge,
                        color = colors.textPrimary
                    )
                    Text(
                        text = "Verify Physical Robot Connection • SoftAP UDP 7913 Re-homing • Home Assistant",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textSecondary
                    )
                }
            }

            // 2. Live Physical Robot Connection Verification Card
            item {
                InsetGroupedCalmCard(
                    title = if (uiState.robotSocketConnected) {
                        "ROBOT CONNECTED & ONLINE"
                    } else {
                        "ROBOT DISCONNECTED (WAITING ON TCP :8081)"
                    },
                    subtitle = if (uiState.robotSocketConnected) {
                        "Your physical Proscenic vacuum is connected to this phone's TCP :8081 Push Gateway."
                    } else {
                        "Embedded server is running on HTTP :8080 and TCP :8081, waiting for the vacuum to join your home Wi-Fi and connect."
                    },
                    isActive = uiState.robotSocketConnected
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(tokens.radii.md))
                            .background(colors.bgCanvas)
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        DiagnosticRow(
                            label = "TCP :8081 Socket State",
                            value = if (uiState.robotSocketConnected) "CONNECTED (ONLINE)" else "DISCONNECTED",
                            valueColor = if (uiState.robotSocketConnected) colors.success else colors.danger
                        )
                        DiagnosticRow(
                            label = "Robot Serial Number (10001)",
                            value = uiState.connectedRobotSn ?: "None yet",
                            valueColor = colors.textPrimary
                        )
                        DiagnosticRow(
                            label = "Robot LAN IP : Port",
                            value = uiState.connectedRobotAddress ?: "Not connected",
                            valueColor = colors.accentCyan
                        )
                        DiagnosticRow(
                            label = "Live Heartbeats (21006)",
                            value = "${uiState.heartbeatCount} pings received",
                            valueColor = if (uiState.heartbeatCount > 0) colors.success else colors.textSecondary
                        )
                        DiagnosticRow(
                            label = "Current Phone Wi-Fi IP",
                            value = if (onRobotSoftAp) {
                                "${uiState.localLanIp} (Connected to Robot SoftAP)"
                            } else {
                                "${uiState.localLanIp} (Home Wi-Fi)"
                            },
                            valueColor = if (onRobotSoftAp) colors.warning else colors.accentCyan
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        LivingFlowPillButton(
                            text = "Beep Robot (Test 3010)",
                            icon = Icons.Default.Campaign,
                            variant = if (uiState.robotSocketConnected) {
                                FluidButtonVariant.PRIMARY
                            } else {
                                FluidButtonVariant.SECONDARY_GLASS
                            },
                            onClick = { viewModel.locateRobot() },
                            modifier = Modifier.weight(1f)
                        )
                        LivingFlowPillButton(
                            text = "Request Status & Map",
                            icon = Icons.Default.Refresh,
                            variant = FluidButtonVariant.SECONDARY_GLASS,
                            onClick = { viewModel.refreshRobotTelemetryNow() },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    LivingFlowSegmentedControl(
                        items = ConnectionMode.entries,
                        selectedItem = uiState.connectionMode,
                        labelProvider = {
                            when (it) {
                                ConnectionMode.LOCAL_SERVER -> "On-Phone Server (:8080/:8081)"
                                ConnectionMode.LAN_BRIDGE -> "External LAN Bridge"
                            }
                        },
                        onSelect = { viewModel.setConnectionMode(it) },
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (uiState.connectionMode == ConnectionMode.LAN_BRIDGE) {
                        OutlinedTextField(
                            value = uiState.externalServerUrl,
                            onValueChange = { viewModel.setExternalServerUrl(it) },
                            label = { Text("External LAN Server / HA Bridge URL") },
                            singleLine = true,
                            shape = RoundedCornerShape(tokens.radii.md),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            // 3. SoftAP UDP 7913 Re-Homing Wizard
            item {
                InsetGroupedCalmCard(
                    title = "SoftAP UDP 7913 Re-Homing Wizard",
                    subtitle = "Step 1: On Home Wi-Fi, verify 'Phone Home Wi-Fi IP' below matches your phone's IP (e.g. 192.168.1.110).\n" +
                        "Step 2: Hold Power + Home on the robot for 5s until it says 'Connecting to network', then connect your phone's Wi-Fi to 'Proscenic-7190...'.\n" +
                        "Step 3: Enter your HOME 2.4GHz router Wi-Fi name & password below and tap 'Re-Home Vacuum'."
                ) {
                    // Explicit guardrail banner so user never enters Proscenic-* as the home router SSID
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(tokens.radii.md))
                            .background(colors.warning.copy(alpha = 0.16f))
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = colors.warning,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = "IMPORTANT: In 'Your Home 2.4GHz Wi-Fi SSID', type your home router's Wi-Fi name — do NOT type 'Proscenic-7190...'!",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.textPrimary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = robotApIp,
                            onValueChange = { robotApIp = it },
                            label = { Text("Robot SoftAP IP") },
                            singleLine = true,
                            shape = RoundedCornerShape(tokens.radii.md),
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = serverHostIp,
                            onValueChange = { serverHostIp = it },
                            label = { Text("Phone Home Wi-Fi IP") },
                            singleLine = true,
                            shape = RoundedCornerShape(tokens.radii.md),
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = wifiSsid,
                            onValueChange = { wifiSsid = it },
                            label = { Text("Your Home 2.4GHz Wi-Fi SSID") },
                            placeholder = { Text("e.g. MyHomeRouter_2.4G") },
                            singleLine = true,
                            shape = RoundedCornerShape(tokens.radii.md),
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = wifiPassword,
                            onValueChange = { wifiPassword = it },
                            label = { Text("Home Wi-Fi Password") },
                            singleLine = true,
                            shape = RoundedCornerShape(tokens.radii.md),
                            modifier = Modifier.weight(1f)
                        )
                    }

                    LivingFlowPillButton(
                        text = if (uiState.rehomeInProgress) {
                            "Sending UDP 7913 Commands..."
                        } else {
                            "Re-Home Vacuum Over UDP 7913"
                        },
                        icon = Icons.Default.Wifi,
                        variant = FluidButtonVariant.PRIMARY,
                        enabled = !uiState.rehomeInProgress,
                        onClick = {
                            viewModel.runRehomeWizard(
                                robotApIp = robotApIp,
                                serverHostIp = serverHostIp,
                                wifiSsid = wifiSsid,
                                wifiPassword = wifiPassword,
                                userId = userId
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (uiState.rehomeStatusBanner != null) {
                        Text(
                            text = uiState.rehomeStatusBanner,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (uiState.rehomeStatusBanner.startsWith("Error")) colors.danger else colors.success
                        )
                    }

                    if (uiState.rehomeSteps.isNotEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(tokens.radii.md))
                                .background(colors.bgCanvas)
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            uiState.rehomeSteps.forEach { step ->
                                Row(
                                    verticalAlignment = Alignment.Top,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = if (step.success) colors.success else colors.danger,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Column {
                                        Text(
                                            text = "Step ${step.step}: ${step.title}",
                                            style = MaterialTheme.typography.labelLarge,
                                            color = colors.textPrimary
                                        )
                                        Text(
                                            text = "TX: ${step.txJson}",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontFamily = FontFamily.Monospace,
                                            color = colors.accentCyan
                                        )
                                        Text(
                                            text = "RX: ${step.rxJson}",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontFamily = FontFamily.Monospace,
                                            color = colors.textSecondary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 4. Home Assistant Local Bridge Snippet
            item {
                InsetGroupedCalmCard(
                    title = "Home Assistant Local REST Integration",
                    subtitle = "Exposes `/api/status`, `/api/map.json`, and `/api/command` on port 8080 for zero-cloud Home Assistant control",
                    trailingHeader = {
                        LivingFlowPillButton(
                            text = if (copiedToast) "Copied!" else "Copy YAML",
                            icon = Icons.Default.ContentCopy,
                            variant = FluidButtonVariant.TONAL_ACCENT,
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                clipboard?.setPrimaryClip(ClipData.newPlainText("HA Proscenic YAML", haYamlSnippet))
                                copiedToast = true
                            }
                        )
                    }
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(tokens.radii.md))
                            .background(colors.bgCanvas)
                            .horizontalScroll(rememberScrollState())
                            .padding(12.dp)
                    ) {
                        Text(
                            text = haYamlSnippet,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            color = colors.textPrimary
                        )
                    }
                }
            }

            // 5. ColorOS 17 Living Flow Accessibility & Visual Guardrails Card
            item {
                InsetGroupedCalmCard(
                    title = "Living Flow Design & Accessibility",
                    subtitle = "ColorOS 17 Luminous Effect, Fluid Motion & Flexible Feedback guardrails"
                ) {
                    AccessibilityToggleRow(
                        title = "Dark Mode (OLED True Black Base)",
                        subtitle = "Uses #000000 base with lifted #1C1C1E/#2A2A2D surfaces & contour glow",
                        checked = uiState.darkThemeOverride ?: true,
                        onCheckedChange = { viewModel.setDarkThemeOverride(it) }
                    )
                    AccessibilityToggleRow(
                        title = "Reduce Transparency (Opaque Fallback)",
                        subtitle = "Replaces frosted glass fills with solid surface tokens & 1px dividers",
                        checked = uiState.reduceTransparency,
                        onCheckedChange = { viewModel.setReduceTransparency(it) }
                    )
                    AccessibilityToggleRow(
                        title = "Reduce Motion",
                        subtitle = "Disables spring bounce/stretch overshoot; uses fast 150ms transitions",
                        checked = uiState.reduceMotion,
                        onCheckedChange = { viewModel.setReduceMotion(it) }
                    )
                    AccessibilityToggleRow(
                        title = "High-Contrast Contour Glow",
                        subtitle = "Thickens contour edges to 1.5dp and raises glass fill opacity >= 92%",
                        checked = uiState.highContrast,
                        onCheckedChange = { viewModel.setHighContrast(it) }
                    )
                }
            }

            // 6. Live Wire Trace Console (`A-HTTP` :8080 & `B-TCP` :8081)
            item {
                InsetGroupedCalmCard(
                    title = "Live Wire Trace Console",
                    subtitle = "Real-time Channel-A HTTP (:8080) & Channel-B TCP (:8081 #\\t#) packets from your robot"
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(tokens.radii.md))
                            .background(colors.bgCanvas)
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (uiState.wireTraces.isEmpty()) {
                            Text(
                                text = "No packets received yet. Once your robot connects to ${serverHostIp}:8080 / :8081, every HTTP request and TCP `#\\t#` frame will stream here live.",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = colors.textSecondary
                            )
                        } else {
                            uiState.wireTraces.take(18).forEach { trace ->
                                Text(
                                    text = "[${trace.channel} ${trace.direction}] ${trace.summary}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = if (trace.direction == "TX") colors.accentCyan else colors.success
                                )
                            }
                        }
                    }
                }
            }
        }

        // Collapsing Floating Glass Top Bar on Scroll
        if (isScrolled) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .progressiveEdgeScrim(fromTop = true)
                    .padding(horizontal = 16.dp, vertical = 10.dp)
                    .livingFlowGlass(
                        level = GlassMaterialLevel.REGULAR,
                        cornerRadius = tokens.radii.full
                    )
                    .padding(horizontal = 18.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Pairing & Live Diagnostics",
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.textPrimary
                )
                Text(
                    text = if (uiState.robotSocketConnected) {
                        "ONLINE • ${uiState.connectedRobotSn ?: "ROBOT"}"
                    } else {
                        "WAITING • ${serverHostIp}:8081"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (uiState.robotSocketConnected) colors.success else colors.warning
                )
            }
        }
    }
}

@Composable
private fun DiagnosticRow(
    label: String,
    value: String,
    valueColor: androidx.compose.ui.graphics.Color
) {
    val colors = LivingFlowTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = colors.textSecondary
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            color = valueColor
        )
    }
}

@Composable
private fun AccessibilityToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val colors = LivingFlowTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = colors.textPrimary
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = colors.textSecondary
            )
        }
        LivingFlowSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}
