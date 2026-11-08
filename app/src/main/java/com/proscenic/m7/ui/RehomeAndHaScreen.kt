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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
 * ColorOS 17 Living Flow SoftAP Re-homing Wizard, Home Assistant Bridge & Accessibility Screen.
 *
 * Includes:
 * 1. Connection Mode Segmented Glass Pill (`LOCAL_SERVER`, `LAN_BRIDGE`, `SIMULATOR`).
 * 2. SoftAP UDP `7913` Re-homing Wizard (`RehomeClient`) with live datagram progress steps.
 * 3. Copyable Home Assistant `configuration.yaml` REST integration snippet.
 * 4. ColorOS 17 Living Flow Accessibility & Visual Guardrails Card (`Reduce Transparency`,
 *    `Reduce Motion`, `High Contrast Contour`, and `Dark / Light Mode`).
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
    var serverHostIp by remember(uiState.localLanIp) { mutableStateOf(uiState.localLanIp) }
    var wifiSsid by remember { mutableStateOf("") }
    var wifiPassword by remember { mutableStateOf("") }
    var userId by remember { mutableStateOf("local_user") }
    var copiedToast by remember { mutableStateOf(false) }

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
                bottom = 112.dp
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            // 1. Large Header
            item {
                Column(modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)) {
                    Text(
                        text = "Pairing, HA & Flow UI",
                        style = MaterialTheme.typography.titleLarge,
                        color = colors.textPrimary
                    )
                    Text(
                        text = "SoftAP UDP 7913 Re-homing • Local HTTP/TCP Server • Living Flow Settings",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textSecondary
                    )
                }
            }

            // 2. Connection Architecture Card
            item {
                InsetGroupedCalmCard(
                    title = "Local Architecture Mode",
                    subtitle = "Embedded server listens on :8080 (Channel A HTTP) and :8081 (Channel B TCP Push Gateway)",
                    isActive = uiState.serverRunning
                ) {
                    LivingFlowSegmentedControl(
                        items = ConnectionMode.entries,
                        selectedItem = uiState.connectionMode,
                        labelProvider = {
                            when (it) {
                                ConnectionMode.LOCAL_SERVER -> "On-Phone"
                                ConnectionMode.LAN_BRIDGE -> "LAN Bridge"
                                ConnectionMode.SIMULATOR -> "Simulator"
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

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Phone LAN IP: ${uiState.localLanIp} • HTTP :8080 • TCP :8081",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.accentCyan
                        )
                        Text(
                            text = if (uiState.robotSocketConnected) "ROBOT ONLINE" else "SIMULATOR READY",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (uiState.robotSocketConnected) colors.success else colors.warning
                        )
                    }
                }
            }

            // 3. SoftAP UDP 7913 Re-Homing Wizard (`rehome.py` port)
            item {
                InsetGroupedCalmCard(
                    title = "SoftAP UDP 7913 Re-Homing Wizard",
                    subtitle = "Redirects the vacuum from Proscenic Cloud (`47.91.67.181`) to your local server without rooting. Hold Power + Home for 5s until 'Connecting to network', join the `Proscenic-M7-*` AP, then run below."
                ) {
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
                            label = { Text("Local Server IP") },
                            singleLine = true,
                            shape = RoundedCornerShape(tokens.radii.md),
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = wifiSsid,
                            onValueChange = { wifiSsid = it },
                            label = { Text("Home 2.4GHz Wi-Fi SSID") },
                            singleLine = true,
                            shape = RoundedCornerShape(tokens.radii.md),
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = wifiPassword,
                            onValueChange = { wifiPassword = it },
                            label = { Text("Wi-Fi Password") },
                            singleLine = true,
                            shape = RoundedCornerShape(tokens.radii.md),
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        LivingFlowPillButton(
                            text = "Dry-Run 6 Steps",
                            icon = Icons.Default.Science,
                            variant = FluidButtonVariant.SECONDARY_GLASS,
                            enabled = !uiState.rehomeInProgress,
                            onClick = {
                                viewModel.runRehomeWizard(
                                    robotApIp = robotApIp,
                                    serverHostIp = serverHostIp,
                                    wifiSsid = wifiSsid.ifBlank { "MyHomeWiFi" },
                                    wifiPassword = wifiPassword.ifBlank { "secret123" },
                                    userId = userId,
                                    dryRun = true
                                )
                            },
                            modifier = Modifier.weight(1f)
                        )
                        LivingFlowPillButton(
                            text = "Re-Home Vacuum",
                            icon = Icons.Default.Wifi,
                            variant = FluidButtonVariant.PRIMARY,
                            enabled = !uiState.rehomeInProgress,
                            onClick = {
                                viewModel.runRehomeWizard(
                                    robotApIp = robotApIp,
                                    serverHostIp = serverHostIp,
                                    wifiSsid = wifiSsid,
                                    wifiPassword = wifiPassword,
                                    userId = userId,
                                    dryRun = false
                                )
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }

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
                                        tint = if (step.succeeded) colors.success else colors.danger,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Column {
                                        Text(
                                            text = "Step ${step.stepNumber}: ${step.title}",
                                            style = MaterialTheme.typography.labelLarge,
                                            color = colors.textPrimary
                                        )
                                        Text(
                                            text = "TX: ${step.sentJson}",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontFamily = FontFamily.Monospace,
                                            color = colors.accentCyan
                                        )
                                        Text(
                                            text = "RX: ${step.receivedReply}",
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
                    subtitle = "Recent Channel-A HTTP (:8080) & Channel-B TCP (:8081 #\\t#) frames"
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
                                text = "Trigger any vacuum command or schedule to inspect live `#\\t#` frames here.",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = colors.textSecondary
                            )
                        } else {
                            uiState.wireTraces.take(12).forEach { trace ->
                                Text(
                                    text = "[${trace.channel} ${trace.direction}] (${trace.infoType}) ${trace.summary}",
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
                    text = "Pairing, HA & Flow UI",
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.textPrimary
                )
                Text(
                    text = "${uiState.localLanIp}:8080",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.accentCyan
                )
            }
        }
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
