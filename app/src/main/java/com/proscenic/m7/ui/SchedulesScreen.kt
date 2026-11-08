package com.proscenic.m7.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.proscenic.m7.protocol.CleanModeType
import com.proscenic.m7.protocol.QuietHoursConfig
import com.proscenic.m7.protocol.ScheduleEntry
import com.proscenic.m7.protocol.SuctionLevel
import com.proscenic.m7.ui.components.CircularGlassIconButton
import com.proscenic.m7.ui.components.FluidButtonVariant
import com.proscenic.m7.ui.components.InsetGroupedCalmCard
import com.proscenic.m7.ui.components.LivingFlowPillButton
import com.proscenic.m7.ui.components.LivingFlowSegmentedControl
import com.proscenic.m7.ui.components.LivingFlowSwitch
import com.proscenic.m7.ui.theme.GlassMaterialLevel
import com.proscenic.m7.ui.theme.LivingFlowTheme
import com.proscenic.m7.ui.theme.livingFlowGlass
import com.proscenic.m7.ui.theme.livingFlowPress
import com.proscenic.m7.ui.theme.progressiveEdgeScrim
import java.util.UUID

/**
 * ColorOS 17 Living Flow Schedules & Do-Not-Disturb (`infoType: 21001` / `21002`) Screen.
 *
 * Implements:
 * - Collapsing large display title (`title.lg` -> compact `glass.regular` top bar on scroll).
 * - Inset grouped calm cards (`surface.raised`, `radius.lg = 24.dp`) with giant tabular numerals
 *   (`displayLarge = 40/48sp`) for scheduled times (`HH:mm`).
 * - Elastic pill switches (`LivingFlowSwitch`) and circular weekday chips (`Sun`..`Sat`).
 * - Quiet Hours (`active: false`) overnight midnight-split configuration card.
 */
@Composable
fun SchedulesScreen(
    uiState: VacuumUiState,
    viewModel: VacuumViewModel,
    modifier: Modifier = Modifier
) {
    val tokens = LivingFlowTheme.tokens
    val colors = tokens.colors
    val listState = rememberLazyListState()
    val isScrolled by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 36 }
    }

    var showAddScheduleModal by remember { mutableStateOf(false) }
    var showQuietHoursModal by remember { mutableStateOf(false) }

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
            // 1. Large Expressive Header
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Schedules & Quiet",
                            style = MaterialTheme.typography.titleLarge,
                            color = colors.textPrimary
                        )
                        Text(
                            text = "time_server_tactics • infoType 21001 & 21002",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.textSecondary
                        )
                    }
                    LivingFlowPillButton(
                        text = "New Schedule",
                        icon = Icons.Default.Add,
                        variant = FluidButtonVariant.PRIMARY,
                        onClick = { showAddScheduleModal = true }
                    )
                }
            }

            // 2. Quiet Hours / Do-Not-Disturb Card (`active: false` with overnight midnight split)
            item {
                val qh = uiState.quietHours
                InsetGroupedCalmCard(
                    title = "Quiet Hours (Do Not Disturb)",
                    subtitle = "Disables voice prompts, LEDs & scheduled jobs (`active: false`). Overnight windows auto-split across midnight.",
                    isActive = qh.enabled,
                    trailingHeader = {
                        LivingFlowSwitch(
                            checked = qh.enabled,
                            onCheckedChange = { enabled ->
                                viewModel.updateQuietHours(qh.copy(enabled = enabled))
                            }
                        )
                    }
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "%02d:%02d – %02d:%02d".format(
                                    qh.startHour,
                                    qh.startMinute,
                                    qh.endHour,
                                    qh.endMinute
                                ),
                                style = MaterialTheme.typography.displayLarge,
                                fontSize = 32.sp,
                                color = if (qh.enabled) colors.accentCyan else colors.textTertiary
                            )
                            Text(
                                text = if (qh.startHour > qh.endHour) {
                                    "Split on wire: ${"%02d:%02d".format(qh.startHour, qh.startMinute)}->23:59 & 00:00->${"%02d:%02d".format(qh.endHour, qh.endMinute)}"
                                } else {
                                    "Single same-day window on wire"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.textSecondary
                            )
                        }
                        LivingFlowPillButton(
                            text = "Edit Window",
                            icon = Icons.Default.Bedtime,
                            variant = FluidButtonVariant.SECONDARY_GLASS,
                            onClick = { showQuietHoursModal = true }
                        )
                    }
                }
            }

            // 3. Weekly Cleaning Schedules List
            items(uiState.schedules, key = { it.id }) { entry ->
                ScheduleCalmCard(
                    entry = entry,
                    onToggleEnabled = { viewModel.toggleScheduleEnabled(entry.id, it) },
                    onDelete = { viewModel.deleteSchedule(entry.id) }
                )
            }
        }

        // Floating Collapsing Top Glass Header on Scroll
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
                    .padding(horizontal = 18.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Schedules & Quiet Hours",
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.textPrimary
                )
                CircularGlassIconButton(
                    icon = Icons.Default.Add,
                    contentDescription = "New schedule",
                    size = 38.dp,
                    isActive = true,
                    onClick = { showAddScheduleModal = true }
                )
            }
        }
    }

    if (showAddScheduleModal) {
        AddScheduleGlassDialog(
            onSave = { newEntry ->
                viewModel.saveOrUpdateSchedule(newEntry)
                showAddScheduleModal = false
            },
            onDismiss = { showAddScheduleModal = false }
        )
    }

    if (showQuietHoursModal) {
        EditQuietHoursGlassDialog(
            initial = uiState.quietHours,
            onSave = { updated ->
                viewModel.updateQuietHours(updated)
                showQuietHoursModal = false
            },
            onDismiss = { showQuietHoursModal = false }
        )
    }
}

@Composable
private fun ScheduleCalmCard(
    entry: ScheduleEntry,
    onToggleEnabled: (Boolean) -> Unit,
    onDelete: () -> Unit
) {
    val tokens = LivingFlowTheme.tokens
    val colors = tokens.colors
    val dayNames = listOf("S", "M", "T", "W", "T", "F", "S")

    InsetGroupedCalmCard(
        title = entry.label,
        subtitle = "${entry.fanLevel.label} Suction • ${entry.passes}x Pass • ${if (entry.cleanMode == CleanModeType.WHOLE_HOME) "Whole Home (cleanMode=1)" else "Saved Zones (cleanMode=2)"}",
        isActive = entry.enabled,
        trailingHeader = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                LivingFlowSwitch(
                    checked = entry.enabled,
                    onCheckedChange = onToggleEnabled
                )
                CircularGlassIconButton(
                    icon = Icons.Default.DeleteOutline,
                    contentDescription = "Delete schedule ${entry.label}",
                    size = 38.dp,
                    accentOverride = colors.danger,
                    onClick = onDelete
                )
            }
        }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Expressive Tabular Numeral Clock (`OPPO Sans 4.0` style)
            Text(
                text = entry.formattedTime,
                style = MaterialTheme.typography.displayLarge,
                color = if (entry.enabled) colors.textPrimary else colors.textTertiary
            )

            // Circular 7-day repeat badges (`Sun`..`Sat` bitmask)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                dayNames.forEachIndexed { idx, letter ->
                    val activeDay = idx in entry.repeatDays
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(
                                if (activeDay) {
                                    if (entry.enabled) colors.accent.copy(alpha = 0.28f) else colors.surfaceSolid
                                } else {
                                    Color.Transparent
                                }
                            )
                            .livingFlowGlass(
                                level = GlassMaterialLevel.THIN,
                                cornerRadius = tokens.radii.full,
                                isActive = activeDay && entry.enabled,
                                elevated = false
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = letter,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (activeDay) FontWeight.Bold else FontWeight.Normal,
                            color = if (activeDay) (if (entry.enabled) Color.White else colors.textSecondary) else colors.textTertiary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AddScheduleGlassDialog(
    onSave: (ScheduleEntry) -> Unit,
    onDismiss: () -> Unit
) {
    val tokens = LivingFlowTheme.tokens
    val colors = tokens.colors

    var label by remember { mutableStateOf("Morning Sweep") }
    var hourText by remember { mutableStateOf("09") }
    var minuteText by remember { mutableStateOf("00") }
    var selectedDays by remember { mutableStateOf(setOf(1, 2, 3, 4, 5)) }
    var suction by remember { mutableStateOf(SuctionLevel.AUTO) }
    var passes by remember { mutableIntStateOf(1) }
    var cleanMode by remember { mutableStateOf(CleanModeType.WHOLE_HOME) }

    val dayLabels = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .livingFlowGlass(
                    level = GlassMaterialLevel.THICK,
                    cornerRadius = tokens.radii.xl,
                    isActive = true
                )
                .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "New Cleaning Schedule",
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.textPrimary
                )
                CircularGlassIconButton(
                    icon = Icons.Default.Close,
                    contentDescription = "Dismiss",
                    size = 38.dp,
                    onClick = onDismiss
                )
            }

            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text("Label") },
                singleLine = true,
                shape = RoundedCornerShape(tokens.radii.md),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = colors.accent,
                    unfocusedBorderColor = colors.divider,
                    focusedTextColor = colors.textPrimary,
                    unfocusedTextColor = colors.textPrimary
                ),
                modifier = Modifier.fillMaxWidth()
            )

            // Time Inputs (HH : mm)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = hourText,
                    onValueChange = { hourText = it.filter(Char::isDigit).take(2) },
                    label = { Text("Hour (0-23)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    shape = RoundedCornerShape(tokens.radii.md),
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = minuteText,
                    onValueChange = { minuteText = it.filter(Char::isDigit).take(2) },
                    label = { Text("Minute (0-59)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    shape = RoundedCornerShape(tokens.radii.md),
                    modifier = Modifier.weight(1f)
                )
            }

            // Days of Week Selector
            Text(
                text = "Repeat Days (Sun..Sat bitmask)",
                style = MaterialTheme.typography.labelLarge,
                color = colors.textPrimary
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                dayLabels.forEachIndexed { idx, day ->
                    val selected = idx in selectedDays
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .livingFlowGlass(
                                level = GlassMaterialLevel.THIN,
                                cornerRadius = tokens.radii.full,
                                isActive = selected
                            )
                            .background(
                                if (selected) colors.accent.copy(alpha = 0.30f) else Color.Transparent,
                                CircleShape
                            )
                            .livingFlowPress(
                                pressScale = 0.92f,
                                onClick = {
                                    selectedDays = if (selected) selectedDays - idx else selectedDays + idx
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = day.take(2),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (selected) Color.White else colors.textSecondary
                        )
                    }
                }
            }

            // Suction Selector
            Text(
                text = "Suction Level",
                style = MaterialTheme.typography.labelLarge,
                color = colors.textPrimary
            )
            LivingFlowSegmentedControl(
                items = listOf(SuctionLevel.ECO, SuctionLevel.AUTO, SuctionLevel.STRONG, SuctionLevel.MAX),
                selectedItem = suction,
                labelProvider = { it.label },
                onSelect = { suction = it },
                modifier = Modifier.fillMaxWidth()
            )

            // Scope & Passes
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                LivingFlowSegmentedControl(
                    items = listOf(CleanModeType.WHOLE_HOME, CleanModeType.ZONES),
                    selectedItem = cleanMode,
                    labelProvider = { if (it == CleanModeType.WHOLE_HOME) "Whole Home" else "Saved Zones" },
                    onSelect = { cleanMode = it },
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.size(10.dp))
                LivingFlowSegmentedControl(
                    items = listOf(1, 2),
                    selectedItem = passes,
                    labelProvider = { "${it}x" },
                    onSelect = { passes = it },
                    modifier = Modifier.size(width = 100.dp, height = 46.dp)
                )
            }

            LivingFlowPillButton(
                text = "Save Schedule (21001)",
                variant = FluidButtonVariant.PRIMARY,
                onClick = {
                    val h = (hourText.toIntOrNull() ?: 9).coerceIn(0, 23)
                    val m = (minuteText.toIntOrNull() ?: 0).coerceIn(0, 59)
                    onSave(
                        ScheduleEntry(
                            id = UUID.randomUUID().toString(),
                            label = label.ifBlank { "Scheduled Clean" },
                            enabled = true,
                            hour = h,
                            minute = m,
                            repeatDays = selectedDays,
                            cleanMode = cleanMode,
                            fanLevel = suction,
                            passes = passes
                        )
                    )
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun EditQuietHoursGlassDialog(
    initial: QuietHoursConfig,
    onSave: (QuietHoursConfig) -> Unit,
    onDismiss: () -> Unit
) {
    val tokens = LivingFlowTheme.tokens
    val colors = tokens.colors

    var startH by remember { mutableStateOf(initial.startHour.toString()) }
    var startM by remember { mutableStateOf(initial.startMinute.toString()) }
    var endH by remember { mutableStateOf(initial.endHour.toString()) }
    var endM by remember { mutableStateOf(initial.endMinute.toString()) }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .livingFlowGlass(
                    level = GlassMaterialLevel.THICK,
                    cornerRadius = tokens.radii.xl,
                    isActive = true
                )
                .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "Configure Quiet Hours",
                style = MaterialTheme.typography.titleMedium,
                color = colors.textPrimary
            )
            Text(
                text = "Per FUNC_COMMANDS.md §7.1, overnight windows (e.g. 23:00 -> 07:00) are automatically split into two `active: false` entries across midnight.",
                style = MaterialTheme.typography.labelSmall,
                color = colors.textSecondary
            )

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = startH,
                    onValueChange = { startH = it.filter(Char::isDigit).take(2) },
                    label = { Text("Start Hour") },
                    singleLine = true,
                    shape = RoundedCornerShape(tokens.radii.md),
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = startM,
                    onValueChange = { startM = it.filter(Char::isDigit).take(2) },
                    label = { Text("Start Min") },
                    singleLine = true,
                    shape = RoundedCornerShape(tokens.radii.md),
                    modifier = Modifier.weight(1f)
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = endH,
                    onValueChange = { endH = it.filter(Char::isDigit).take(2) },
                    label = { Text("End Hour") },
                    singleLine = true,
                    shape = RoundedCornerShape(tokens.radii.md),
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = endM,
                    onValueChange = { endM = it.filter(Char::isDigit).take(2) },
                    label = { Text("End Min") },
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
                    text = "Cancel",
                    variant = FluidButtonVariant.SECONDARY_GLASS,
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f)
                )
                LivingFlowPillButton(
                    text = "Save Window",
                    variant = FluidButtonVariant.PRIMARY,
                    onClick = {
                        onSave(
                            QuietHoursConfig(
                                enabled = true,
                                startHour = (startH.toIntOrNull() ?: 23).coerceIn(0, 23),
                                startMinute = (startM.toIntOrNull() ?: 0).coerceIn(0, 59),
                                endHour = (endH.toIntOrNull() ?: 7).coerceIn(0, 23),
                                endMinute = (endM.toIntOrNull() ?: 0).coerceIn(0, 59)
                            )
                        )
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}
