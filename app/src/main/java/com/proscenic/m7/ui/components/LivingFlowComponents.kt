package com.proscenic.m7.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.proscenic.m7.ui.theme.GlassMaterialLevel
import com.proscenic.m7.ui.theme.LivingFlowHaptic
import com.proscenic.m7.ui.theme.LivingFlowTheme
import com.proscenic.m7.ui.theme.livingFlowCalmCard
import com.proscenic.m7.ui.theme.livingFlowGlass
import com.proscenic.m7.ui.theme.livingFlowPress
import com.proscenic.m7.ui.theme.performLivingFlowHaptic
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * ColorOS 17 Living Flow Design Component Library.
 */

data class LivingFlowNavTab(
    val title: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
)

/**
 * **Floating Island Navigation Bar (`COMPONENT RULES` §1):**
 * - A pill (`radius.full`), `glass.thin` + contour glow, floating 12–16dp above the bottom safe area,
 *   height `60dp`.
 * - Selected tab = filled icon + label inside a lighter inner pill indicator that **morphs**
 *   (stretches horizontally in the direction of travel, then settles via `spring.morph`).
 * - Supports **horizontal swipe / press-and-slide** across the bar to switch tabs with haptic ticks.
 * - Optional separate circular glass action button sits beside the island bar.
 */
@Composable
fun FloatingIslandNavBar(
    tabs: List<LivingFlowNavTab>,
    selectedIndex: Int,
    onSelectIndex: (Int) -> Unit,
    sideActionIcon: ImageVector? = null,
    sideActionContentDescription: String? = null,
    sideActionActive: Boolean = false,
    onSideActionClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val tokens = LivingFlowTheme.tokens
    val colors = tokens.colors
    val view = LocalView.current

    // Animate indicator fraction with spring.morph so it stretches while in flight
    val indicatorPos by animateFloatAsState(
        targetValue = selectedIndex.toFloat(),
        animationSpec = tokens.motion.morphSpring(),
        label = "islandIndicatorPos"
    )
    // Dynamic stretch factor proportional to distance from settled integer index
    val velocityStretch = 1f + (abs(indicatorPos - indicatorPos.roundToInt()) * 0.28f)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Main Floating Pill Island
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .height(60.dp)
                .livingFlowGlass(
                    level = GlassMaterialLevel.THIN,
                    cornerRadius = tokens.radii.full
                )
                .padding(4.dp)
                .pointerInput(tabs.size) {
                    detectHorizontalDragGestures { change, _ ->
                        change.consume()
                        val fraction = (change.position.x / size.width.coerceAtLeast(1)).coerceIn(0f, 0.999f)
                        val newIdx = (fraction * tabs.size).toInt().coerceIn(0, tabs.lastIndex)
                        if (newIdx != selectedIndex) {
                            view.performLivingFlowHaptic(LivingFlowHaptic.DETENT_TICK)
                            onSelectIndex(newIdx)
                        }
                    }
                }
        ) {
            val tabWidth = maxWidth / tabs.size.coerceAtLeast(1)
            val density = LocalDensity.current

            // Morphing Inner Pill Indicator
            val stretchedWidth = tabWidth * velocityStretch.coerceIn(1f, 1.24f)
            val baseOffsetPx = with(density) { (tabWidth * indicatorPos).toPx() }
            val stretchAdjustPx = with(density) { ((stretchedWidth - tabWidth) / 2f).toPx() }

            Box(
                modifier = Modifier
                    .offset { IntOffset((baseOffsetPx - stretchAdjustPx).roundToInt(), 0) }
                    .width(stretchedWidth)
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                colors.accent.copy(alpha = if (colors.isDark) 0.36f else 0.22f),
                                colors.accent.copy(alpha = if (colors.isDark) 0.18f else 0.12f)
                            )
                        )
                    )
                    .livingFlowGlass(
                        level = GlassMaterialLevel.THIN,
                        cornerRadius = tokens.radii.full,
                        isActive = true,
                        elevated = false
                    )
            )

            // Tab Items
            Row(modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
                tabs.forEachIndexed { idx, tab ->
                    val isSelected = idx == selectedIndex
                    val tint by animateColorAsState(
                        targetValue = if (isSelected) {
                            if (colors.isDark) Color.White else colors.accent
                        } else {
                            colors.textSecondary
                        },
                        animationSpec = tokens.motion.gentleSpring(),
                        label = "tabTint"
                    )
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(CircleShape)
                            .livingFlowPress(
                                pressScale = 0.94f,
                                onClick = { onSelectIndex(idx) }
                            )
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = if (isSelected) tab.selectedIcon else tab.unselectedIcon,
                            contentDescription = tab.title,
                            tint = tint,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = tab.title,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                            color = tint,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        // Separate circular glass button beside the island bar (e.g. D-Pad or Quick Action)
        if (sideActionIcon != null && onSideActionClick != null) {
            CircularGlassIconButton(
                icon = sideActionIcon,
                contentDescription = sideActionContentDescription ?: "Quick action",
                isActive = sideActionActive,
                size = 56.dp,
                onClick = onSideActionClick
            )
        }
    }
}

/**
 * **Circular Glass Icon / Quick-Toggle Button (`COMPONENT RULES` §5):**
 * - `radius.full`, `glass.thin` or `glass.regular`.
 * - ON state = `--accent` tinted glass + `--accent` contour glow; OFF = neutral glass.
 * - Compresses to `0.92f` via `spring.press` and rebounds with `spring.release`.
 */
@Composable
fun CircularGlassIconButton(
    icon: ImageVector,
    contentDescription: String,
    isActive: Boolean = false,
    size: Dp = 48.dp,
    accentOverride: Color? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LivingFlowTheme.colors
    val activeColor = accentOverride ?: colors.accent
    Box(
        modifier = modifier
            .size(size)
            .semantics {
                this.contentDescription = contentDescription
                this.role = Role.Button
            }
            .livingFlowGlass(
                level = GlassMaterialLevel.REGULAR,
                cornerRadius = 999.dp,
                isActive = isActive,
                accentOverride = activeColor
            )
            .background(
                color = if (isActive) activeColor.copy(alpha = 0.25f) else Color.Transparent,
                shape = CircleShape
            )
            .livingFlowPress(
                pressScale = 0.92f,
                glowColor = activeColor,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (isActive) (if (colors.isDark) Color.White else activeColor) else colors.textPrimary,
            modifier = Modifier.size(22.dp)
        )
    }
}

/**
 * **Living Flow Pill Button (`COMPONENT RULES` §4):**
 * - Primary = filled `--accent` pill, white text, height `48dp` (or `52dp` large).
 * - Secondary = `glass.thin` or tonal (`accent @ 14%`) pill with accent/primary text + contour glow.
 * - Danger = semantic danger fill/tint.
 * - All buttons feature `spring.press` compression, radial touch glow, and haptic tick.
 */
enum class FluidButtonVariant { PRIMARY, SECONDARY_GLASS, TONAL_ACCENT, DANGER }

@Composable
fun LivingFlowPillButton(
    text: String,
    icon: ImageVector? = null,
    variant: FluidButtonVariant = FluidButtonVariant.PRIMARY,
    enabled: Boolean = true,
    large: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = LivingFlowTheme.tokens
    val colors = tokens.colors
    val height = if (large) 52.dp else 48.dp

    val bgModifier = when (variant) {
        FluidButtonVariant.PRIMARY -> Modifier
            .clip(CircleShape)
            .background(
                brush = Brush.verticalGradient(
                    listOf(
                        colors.accent,
                        colors.accentPressed
                    )
                ),
                shape = CircleShape
            )
            .livingFlowGlass(
                level = GlassMaterialLevel.THIN,
                cornerRadius = tokens.radii.full,
                isActive = true,
                elevated = enabled
            )
        FluidButtonVariant.SECONDARY_GLASS -> Modifier
            .livingFlowGlass(
                level = GlassMaterialLevel.THIN,
                cornerRadius = tokens.radii.full,
                isActive = false,
                elevated = enabled
            )
        FluidButtonVariant.TONAL_ACCENT -> Modifier
            .livingFlowGlass(
                level = GlassMaterialLevel.THIN,
                cornerRadius = tokens.radii.full,
                isActive = true,
                accentOverride = colors.accentCyan,
                elevated = false
            )
            .background(colors.accent.copy(alpha = 0.16f), CircleShape)
        FluidButtonVariant.DANGER -> Modifier
            .livingFlowGlass(
                level = GlassMaterialLevel.THIN,
                cornerRadius = tokens.radii.full,
                isActive = true,
                accentOverride = colors.danger,
                elevated = false
            )
            .background(colors.danger.copy(alpha = 0.22f), CircleShape)
    }

    val contentColor = when (variant) {
        FluidButtonVariant.PRIMARY -> Color.White
        FluidButtonVariant.SECONDARY_GLASS -> colors.textPrimary
        FluidButtonVariant.TONAL_ACCENT -> if (colors.isDark) Color.White else colors.accent
        FluidButtonVariant.DANGER -> if (colors.isDark) Color(0xFFFFB4B0) else colors.danger
    }

    Row(
        modifier = modifier
            .height(height)
            .alpha(if (enabled) 1f else 0.38f)
            .then(bgModifier)
            .livingFlowPress(
                enabled = enabled,
                pressScale = 0.96f,
                onClick = onClick
            )
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * **Morphing Segmented Glass Pill (`COMPONENT RULES` §8):**
 * - Glass pill container (`radius.full`).
 * - Selected indicator is a lighter pill with contour glow that slides with `spring.morph`
 *   and stretches in the direction of travel.
 * - Supports press-and-slide selection across segments with light haptic ticks.
 */
@Composable
fun <T> LivingFlowSegmentedControl(
    items: List<T>,
    selectedItem: T,
    labelProvider: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = LivingFlowTheme.tokens
    val colors = tokens.colors
    val view = LocalView.current
    val selectedIndex = items.indexOf(selectedItem).coerceAtLeast(0)

    val animatedIndex by animateFloatAsState(
        targetValue = selectedIndex.toFloat(),
        animationSpec = tokens.motion.morphSpring(),
        label = "segmentedMorph"
    )
    val stretchFactor = 1f + (abs(animatedIndex - animatedIndex.roundToInt()) * 0.25f)

    BoxWithConstraints(
        modifier = modifier
            .height(46.dp)
            .livingFlowGlass(
                level = GlassMaterialLevel.REGULAR,
                cornerRadius = tokens.radii.full,
                elevated = true
            )
            .padding(4.dp)
            .pointerInput(items) {
                detectHorizontalDragGestures { change, _ ->
                    change.consume()
                    val fraction = (change.position.x / size.width.coerceAtLeast(1)).coerceIn(0f, 0.999f)
                    val idx = (fraction * items.size).toInt().coerceIn(0, items.lastIndex)
                    val target = items[idx]
                    if (target != selectedItem) {
                        view.performLivingFlowHaptic(LivingFlowHaptic.DETENT_TICK)
                        onSelect(target)
                    }
                }
            }
    ) {
        val segmentWidth = maxWidth / items.size.coerceAtLeast(1)
        val density = LocalDensity.current
        val indicatorWidth = segmentWidth * stretchFactor.coerceIn(1f, 1.22f)
        val offsetPx = with(density) {
            (segmentWidth * animatedIndex - (indicatorWidth - segmentWidth) / 2f).toPx()
        }

        // Sliding & stretching inner pill
        Box(
            modifier = Modifier
                .offset { IntOffset(offsetPx.roundToInt(), 0) }
                .width(indicatorWidth)
                .fillMaxHeight()
                .clip(CircleShape)
                .background(colors.accent.copy(alpha = if (colors.isDark) 0.42f else 0.20f))
                .livingFlowGlass(
                    level = GlassMaterialLevel.THIN,
                    cornerRadius = tokens.radii.full,
                    isActive = true,
                    elevated = false
                )
        )

        Row(modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
            items.forEachIndexed { idx, item ->
                val isSelected = idx == selectedIndex
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .livingFlowPress(
                            pressScale = 0.95f,
                            onClick = { onSelect(item) }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = labelProvider(item),
                        style = MaterialTheme.typography.labelLarge,
                        fontSize = 13.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (isSelected) (if (colors.isDark) Color.White else colors.accent) else colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/**
 * **Elastic Pill Switch (`COMPONENT RULES` §6):**
 * - Pill track (`radius.full`) `52×32dp`.
 * - Thumb stretches horizontally (`+22%` width) while pressed/dragged and springs across via `spring.morph`.
 * - ON track = `--accent` with contour glow.
 */
@Composable
fun LivingFlowSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = LivingFlowTheme.tokens
    val colors = tokens.colors
    val view = LocalView.current
    var isPressed by remember { mutableStateOf(false) }

    val thumbProgress by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = tokens.motion.morphSpring(),
        label = "switchProgress"
    )
    // Stretch thumb while pressed or mid-flight
    val midFlightStretch = abs(thumbProgress - thumbProgress.roundToInt()) * 6f
    val thumbWidthDp by animateDpAsState(
        targetValue = if (isPressed) 29.dp else (24.dp + midFlightStretch.dp),
        animationSpec = tokens.motion.pressSpring(),
        label = "thumbWidth"
    )
    val trackColor by animateColorAsState(
        targetValue = if (checked) colors.accent else colors.surfaceRaised,
        animationSpec = tokens.motion.gentleSpring(),
        label = "trackColor"
    )

    Box(
        modifier = modifier
            .size(width = 52.dp, height = 32.dp)
            .clip(CircleShape)
            .background(trackColor)
            .livingFlowGlass(
                level = GlassMaterialLevel.THIN,
                cornerRadius = tokens.radii.full,
                isActive = checked,
                elevated = false
            )
            .pointerInput(checked) {
                detectTapGestures(
                    onPress = {
                        isPressed = true
                        view.performLivingFlowHaptic(LivingFlowHaptic.PRESS_TICK)
                        tryAwaitRelease()
                        isPressed = false
                    },
                    onTap = {
                        view.performLivingFlowHaptic(LivingFlowHaptic.CONFIRM)
                        onCheckedChange(!checked)
                    }
                )
            }
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        val travelDp = (44.dp - thumbWidthDp) * thumbProgress
        Box(
            modifier = Modifier
                .offset(x = travelDp)
                .size(width = thumbWidthDp, height = 24.dp)
                .clip(CircleShape)
                .background(Color.White)
        )
    }
}

/**
 * **Luminous Comet Seek Bar (`COMPONENT RULES` §7 & Key Findings §3):**
 * - Thick rounded track (`8dp` at rest; expands to `14dp` while touched).
 * - Luminous fill with a soft pulsing glow at the leading edge ("comet" highlight).
 * - Track gently stretches under the finger; snaps to discrete steps with `DETENT_TICK` haptics.
 */
@Composable
fun LuminousCometSlider(
    value: Int,
    valueRange: IntRange,
    onValueChange: (Int) -> Unit,
    accentColor: Color = LivingFlowTheme.colors.accentCyan,
    modifier: Modifier = Modifier
) {
    val tokens = LivingFlowTheme.tokens
    val colors = tokens.colors
    val view = LocalView.current

    var isTouching by remember { mutableStateOf(false) }
    val trackHeightDp by animateDpAsState(
        targetValue = if (isTouching) 16.dp else 9.dp,
        animationSpec = tokens.motion.morphSpring(),
        label = "cometTrackHeight"
    )
    val span = (valueRange.last - valueRange.first).coerceAtLeast(1)
    val targetFraction = ((value - valueRange.first).toFloat() / span.toFloat()).coerceIn(0f, 1f)
    val animatedFraction by animateFloatAsState(
        targetValue = targetFraction,
        animationSpec = tokens.motion.morphSpring(),
        label = "cometFraction"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(32.dp)
            .pointerInput(valueRange) {
                detectTapGestures(
                    onPress = { offset ->
                        isTouching = true
                        val frac = (offset.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                        val stepped = (valueRange.first + (frac * span).roundToInt()).coerceIn(valueRange)
                        if (stepped != value) {
                            view.performLivingFlowHaptic(LivingFlowHaptic.DETENT_TICK)
                            onValueChange(stepped)
                        }
                        tryAwaitRelease()
                        isTouching = false
                    }
                )
            }
            .pointerInput(valueRange) {
                detectHorizontalDragGestures(
                    onDragStart = { isTouching = true },
                    onDragEnd = { isTouching = false },
                    onDragCancel = { isTouching = false }
                ) { change, _ ->
                    change.consume()
                    val frac = (change.position.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                    val stepped = (valueRange.first + (frac * span).roundToInt()).coerceIn(valueRange)
                    if (stepped != value) {
                        view.performLivingFlowHaptic(LivingFlowHaptic.DETENT_TICK)
                        onValueChange(stepped)
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(trackHeightDp)
        ) {
            val radius = size.height / 2f
            // Base track
            drawRoundRect(
                color = colors.surfaceRaised,
                cornerRadius = CornerRadius(radius, radius),
                size = size
            )
            // Luminous fill
            val fillWidth = (size.width * animatedFraction).coerceAtLeast(size.height)
            drawRoundRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        colors.accent,
                        accentColor
                    ),
                    startX = 0f,
                    endX = fillWidth
                ),
                cornerRadius = CornerRadius(radius, radius),
                size = Size(fillWidth, size.height)
            )
            // Leading-edge "Comet" glow highlight
            val cometCenter = Offset(fillWidth - radius * 0.5f, size.height / 2f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.White.copy(alpha = if (isTouching) 0.95f else 0.75f),
                        accentColor.copy(alpha = 0.55f),
                        Color.Transparent
                    ),
                    center = cometCenter,
                    radius = size.height * 1.45f
                ),
                radius = size.height * 1.45f,
                center = cometCenter
            )
        }
    }
}

/**
 * **Inset Grouped Calm Surface Card (`Spacing & layout` §4 & `COMPONENT RULES` §3):**
 * - Uses `surface.raised` / `surface.solid` with `radius.lg = 24.dp` and faint contour glow.
 * - Keeps dense content/settings on calm, near-opaque surfaces for high contrast.
 */
@Composable
fun InsetGroupedCalmCard(
    title: String? = null,
    subtitle: String? = null,
    trailingHeader: (@Composable () -> Unit)? = null,
    isActive: Boolean = false,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = LivingFlowTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .livingFlowCalmCard(cornerRadius = 24.dp, raised = true, isActive = isActive)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (title != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        color = colors.textPrimary
                    )
                    if (subtitle != null) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.textSecondary
                        )
                    }
                }
                if (trailingHeader != null) {
                    trailingHeader()
                }
            }
        }
        content()
    }
}

/**
 * **Live-Activity Morphing Capsule Banner (`COMPONENT RULES` §12):**
 * - `glass.regular` pill that expands from a compact capsule into a larger status card on tap
 *   using `AnimatedContent` + `spring.morph`.
 */
@Composable
fun MorphingStatusCapsule(
    title: String,
    subtitle: String,
    accentDotColor: Color,
    progressFraction: Float? = null,
    modifier: Modifier = Modifier
) {
    val tokens = LivingFlowTheme.tokens
    val colors = tokens.colors
    var expanded by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .livingFlowGlass(
                level = GlassMaterialLevel.REGULAR,
                cornerRadius = if (expanded) tokens.radii.lg else tokens.radii.full,
                isActive = true,
                accentOverride = accentDotColor
            )
            .livingFlowPress(
                pressScale = 0.97f,
                glowColor = accentDotColor,
                onClick = { expanded = !expanded }
            )
            .padding(horizontal = 16.dp, vertical = if (expanded) 12.dp else 8.dp)
    ) {
        AnimatedContent(
            targetState = expanded,
            label = "capsuleMorph"
        ) { isExpanded ->
            if (!isExpanded) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(accentDotColor)
                    )
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "• Tap for telemetry",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textSecondary,
                        maxLines = 1
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(accentDotColor)
                        )
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleSmall,
                            color = colors.textPrimary
                        )
                    }
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textSecondary
                    )
                    if (progressFraction != null) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(CircleShape)
                                .background(colors.surfaceRaised)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(progressFraction.coerceIn(0.05f, 1f))
                                    .fillMaxHeight()
                                    .clip(CircleShape)
                                    .background(
                                        Brush.horizontalGradient(
                                            listOf(colors.accent, accentDotColor)
                                        )
                                    )
                            )
                        }
                    }
                }
            }
        }
    }
}
