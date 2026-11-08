package com.proscenic.m7.ui.theme

import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * ColorOS 17 "Living Flow Design" (流体设计) Token System & Modifiers.
 *
 * Built on the three Living Flow pillars:
 * 1. **Luminous Effect (凝光视效):** Restrained frosted glass (`glass.thin`, `glass.regular`, `glass.thick`),
 *    1dp top-to-bottom linear gradient **contour glow**, progressive blur under floating bars, and
 *    radial **touch glow** following pointer contact.
 * 2. **Fluid Motion (流体动效):** Spring-first morphing (`spring.press`, `spring.release`, `spring.morph`,
 *    `spring.gentle`) with floating island navigation and seamless state transitions.
 * 3. **Flexible Feedback (柔性反馈):** Physical press compression (`0.95f`–`0.97f`), directional drag stretch,
 *    overshoot rebound (`dampingRatio = 0.55f`), and semantic haptics.
 */

enum class GlassMaterialLevel(val blurRadiusDp: Dp, val saturationPercent: Int) {
    THIN(blurRadiusDp = 24.dp, saturationPercent = 150),
    REGULAR(blurRadiusDp = 32.dp, saturationPercent = 160),
    THICK(blurRadiusDp = 44.dp, saturationPercent = 170)
}

@Immutable
data class LivingFlowColors(
    val isDark: Boolean,
    val accent: Color,
    val accentPressed: Color,
    val accentCyan: Color,
    val bgBase: Color,
    val bgCanvas: Color,
    val surfaceSolid: Color,
    val surfaceRaised: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val divider: Color,
    val success: Color,
    val warning: Color,
    val danger: Color,
    val glassThinFill: Color,
    val glassRegularFill: Color,
    val glassThickFill: Color,
    val modalScrim: Color,
    val contourTop: Color,
    val contourBottom: Color,
    val touchGlowColor: Color
)

@Immutable
data class LivingFlowRadii(
    val xs: Dp = 8.dp,
    val sm: Dp = 12.dp,
    val md: Dp = 16.dp,
    val lg: Dp = 24.dp,
    val xl: Dp = 32.dp,
    val full: Dp = 999.dp
) {
    val shapeXs: Shape get() = RoundedCornerShape(xs)
    val shapeSm: Shape get() = RoundedCornerShape(sm)
    val shapeMd: Shape get() = RoundedCornerShape(md)
    val shapeLg: Shape get() = RoundedCornerShape(lg)
    val shapeXl: Shape get() = RoundedCornerShape(xl)
    val shapeFull: Shape get() = CircleShape
}

@Immutable
data class LivingFlowSpacing(
    val xxs: Dp = 4.dp,
    val xs: Dp = 8.dp,
    val sm: Dp = 12.dp,
    val md: Dp = 16.dp,
    val lg: Dp = 20.dp,
    val xl: Dp = 24.dp,
    val xxl: Dp = 32.dp,
    val xxxl: Dp = 40.dp,
    val huge: Dp = 48.dp
)

@Immutable
data class LivingFlowMotion(
    val reducedMotion: Boolean = false
) {
    /** `spring.press`: stiffness 800, dampingRatio 0.80 -> scale 0.95–0.97 within ~90ms */
    fun <T> pressSpring(): androidx.compose.animation.core.FiniteAnimationSpec<T> =
        if (reducedMotion) tween(90) else spring(dampingRatio = 0.80f, stiffness = 800f)

    /** `spring.release` (rebound): stiffness 450, dampingRatio 0.55 -> ~3–5% overshoot, settles ~380ms */
    fun <T> releaseSpring(): androidx.compose.animation.core.FiniteAnimationSpec<T> =
        if (reducedMotion) tween(140) else spring(dampingRatio = 0.55f, stiffness = 450f)

    /** `spring.morph` (container transforms, island pill, sheets): stiffness 380, dampingRatio 0.82 */
    fun <T> morphSpring(): androidx.compose.animation.core.FiniteAnimationSpec<T> =
        if (reducedMotion) tween(150) else spring(dampingRatio = 0.82f, stiffness = 380f)

    /** `spring.gentle` (fades, color, glow): stiffness 200, dampingRatio 1.0 */
    fun <T> gentleSpring(): androidx.compose.animation.core.FiniteAnimationSpec<T> =
        if (reducedMotion) tween(150) else spring(dampingRatio = 1.0f, stiffness = 200f)
}

@Immutable
data class LivingFlowTokens(
    val colors: LivingFlowColors,
    val radii: LivingFlowRadii = LivingFlowRadii(),
    val spacing: LivingFlowSpacing = LivingFlowSpacing(),
    val motion: LivingFlowMotion = LivingFlowMotion(),
    val reduceTransparency: Boolean = false,
    val highContrast: Boolean = false
)

val DarkLivingFlowColors = LivingFlowColors(
    isDark = true,
    accent = Color(0xFF2F6BFF),
    accentPressed = Color(0xFF2558D9),
    accentCyan = Color(0xFF38BDF8),
    bgBase = Color(0xFF000000), // True OLED black base per Living Flow spec
    bgCanvas = Color(0xFF070B14),
    surfaceSolid = Color(0xFF1C1C1E),
    surfaceRaised = Color(0xFF2A2A2D),
    textPrimary = Color(0xEBFFFFFF),   // rgba(255,255,255,0.92)
    textSecondary = Color(0x99FFFFFF), // rgba(255,255,255,0.60)
    textTertiary = Color(0x61FFFFFF),  // rgba(255,255,255,0.38)
    divider = Color(0x1AFFFFFF),       // rgba(255,255,255,0.10)
    success = Color(0xFF22B35E),
    warning = Color(0xFFFF9F0A),
    danger = Color(0xFFF2453D),
    glassThinFill = Color(0x8C28282C),    // rgba(40,40,44,0.55)
    glassRegularFill = Color(0xAD242428), // rgba(36,36,40,0.68)
    glassThickFill = Color(0xCF1E1E22),   // rgba(30,30,34,0.81)
    modalScrim = Color(0x66000000),       // rgba(0,0,0,0.40)
    contourTop = Color(0x38FFFFFF),       // rgba(255,255,255,0.22)
    contourBottom = Color(0x0DFFFFFF),    // rgba(255,255,255,0.05)
    touchGlowColor = Color(0x1AFFFFFF)    // white @ 10% dark
)

val LightLivingFlowColors = LivingFlowColors(
    isDark = false,
    accent = Color(0xFF2F6BFF),
    accentPressed = Color(0xFF2558D9),
    accentCyan = Color(0xFF0284C7),
    bgBase = Color(0xFFF2F3F5),
    bgCanvas = Color(0xFFE5E9F0),
    surfaceSolid = Color(0xFFFFFFFF),
    surfaceRaised = Color(0xFFF7F7F9),
    textPrimary = Color(0xE6000000),   // rgba(0,0,0,0.90)
    textSecondary = Color(0x8C000000), // rgba(0,0,0,0.55)
    textTertiary = Color(0x59000000),  // rgba(0,0,0,0.35)
    divider = Color(0x14000000),       // rgba(0,0,0,0.08)
    success = Color(0xFF1D9E50),
    warning = Color(0xFFE68A00),
    danger = Color(0xFFD9362E),
    glassThinFill = Color(0x99FFFFFF),    // rgba(255,255,255,0.60)
    glassRegularFill = Color(0xB8FFFFFF), // rgba(255,255,255,0.72)
    glassThickFill = Color(0xD9FAFAFC),   // rgba(250,250,252,0.85)
    modalScrim = Color(0x2E000000),       // rgba(0,0,0,0.18)
    contourTop = Color(0xBFFFFFFF),       // rgba(255,255,255,0.75)
    contourBottom = Color(0x33FFFFFF),    // rgba(255,255,255,0.20)
    touchGlowColor = Color(0x24FFFFFF)    // white @ 14% light
)

val LivingFlowTypography = Typography(
    // display 40/48 Medium (tabular numerals)
    displayLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 40.sp,
        lineHeight = 48.sp,
        fontFeatureSettings = "tnum"
    ),
    // title.lg 28/36 Medium
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 28.sp,
        lineHeight = 36.sp,
        fontFeatureSettings = "tnum"
    ),
    // title.md 22/28 Medium
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        fontFeatureSettings = "tnum"
    ),
    // headline 18/24 Semibold
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
        fontFeatureSettings = "tnum"
    ),
    // body.lg 16/24 Regular
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp
    ),
    // body 15/22 Regular
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp
    ),
    // label 14/20 Medium
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontFeatureSettings = "tnum"
    ),
    // caption 12/16 Regular (never smaller than 12sp on glass)
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontFeatureSettings = "tnum"
    )
)

val LocalLivingFlowTokens = staticCompositionLocalOf {
    LivingFlowTokens(colors = DarkLivingFlowColors)
}

object LivingFlowTheme {
    val tokens: LivingFlowTokens
        @Composable
        get() = LocalLivingFlowTokens.current

    val colors: LivingFlowColors
        @Composable
        get() = LocalLivingFlowTokens.current.colors

    val radii: LivingFlowRadii
        @Composable
        get() = LocalLivingFlowTokens.current.radii

    val spacing: LivingFlowSpacing
        @Composable
        get() = LocalLivingFlowTokens.current.spacing

    val motion: LivingFlowMotion
        @Composable
        get() = LocalLivingFlowTokens.current.motion
}

@Composable
fun ProscenicLivingFlowTheme(
    darkThemeOverride: Boolean? = true,
    reduceTransparency: Boolean = false,
    reduceMotionOverride: Boolean = false,
    highContrast: Boolean = false,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val systemDark = isSystemInDarkTheme()
    val useDark = darkThemeOverride ?: systemDark

    // Check OS-level ANIMATOR_DURATION_SCALE in addition to in-app reduceMotion toggle
    val osAnimatorDisabled = remember(context) {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
    }

    val baseColors = if (useDark) DarkLivingFlowColors else LightLivingFlowColors
    val effectiveColors = remember(baseColors, reduceTransparency, highContrast) {
        if (reduceTransparency) {
            // Accessibility guardrail: replace all glass fills with opaque surface tokens
            baseColors.copy(
                glassThinFill = baseColors.surfaceRaised,
                glassRegularFill = baseColors.surfaceSolid,
                glassThickFill = baseColors.surfaceSolid
            )
        } else if (highContrast) {
            baseColors.copy(
                glassThinFill = baseColors.glassThinFill.copy(alpha = 0.92f),
                glassRegularFill = baseColors.glassRegularFill.copy(alpha = 0.94f),
                glassThickFill = baseColors.glassThickFill.copy(alpha = 0.96f),
                contourTop = baseColors.accent.copy(alpha = 0.70f),
                contourBottom = baseColors.accent.copy(alpha = 0.35f)
            )
        } else {
            baseColors
        }
    }

    val tokens = remember(effectiveColors, reduceTransparency, reduceMotionOverride, osAnimatorDisabled, highContrast) {
        LivingFlowTokens(
            colors = effectiveColors,
            motion = LivingFlowMotion(reducedMotion = reduceMotionOverride || osAnimatorDisabled),
            reduceTransparency = reduceTransparency,
            highContrast = highContrast
        )
    }

    val materialColorScheme = if (useDark) {
        darkColorScheme(
            primary = effectiveColors.accent,
            secondary = effectiveColors.accentCyan,
            tertiary = effectiveColors.success,
            background = effectiveColors.bgBase,
            surface = effectiveColors.surfaceSolid,
            surfaceVariant = effectiveColors.surfaceRaised,
            onPrimary = Color.White,
            onBackground = effectiveColors.textPrimary,
            onSurface = effectiveColors.textPrimary,
            onSurfaceVariant = effectiveColors.textSecondary,
            error = effectiveColors.danger
        )
    } else {
        lightColorScheme(
            primary = effectiveColors.accent,
            secondary = effectiveColors.accentCyan,
            tertiary = effectiveColors.success,
            background = effectiveColors.bgBase,
            surface = effectiveColors.surfaceSolid,
            surfaceVariant = effectiveColors.surfaceRaised,
            onPrimary = Color.White,
            onBackground = effectiveColors.textPrimary,
            onSurface = effectiveColors.textPrimary,
            onSurfaceVariant = effectiveColors.textSecondary,
            error = effectiveColors.danger
        )
    }

    CompositionLocalProvider(LocalLivingFlowTokens provides tokens) {
        MaterialTheme(
            colorScheme = materialColorScheme,
            typography = LivingFlowTypography,
            content = content
        )
    }
}

// ============================================================================
// Living Flow Surface & Interactive Modifiers
// ============================================================================

/**
 * Applies a Living Flow frosted-glass surface (`glass.thin`, `glass.regular`, or `glass.thick`)
 * with a 1dp top-to-bottom linear gradient **Contour Glow** edge and optional active `--accent` halo.
 */
fun Modifier.livingFlowGlass(
    level: GlassMaterialLevel = GlassMaterialLevel.REGULAR,
    cornerRadius: Dp = 24.dp,
    isActive: Boolean = false,
    accentOverride: Color? = null,
    elevated: Boolean = true
): Modifier = composed {
    val tokens = LivingFlowTheme.tokens
    val colors = tokens.colors
    val shape = if (cornerRadius >= 900.dp) CircleShape else RoundedCornerShape(cornerRadius)
    val fill = when (level) {
        GlassMaterialLevel.THIN -> colors.glassThinFill
        GlassMaterialLevel.REGULAR -> colors.glassRegularFill
        GlassMaterialLevel.THICK -> colors.glassThickFill
    }
    val activeColor = accentOverride ?: colors.accent
    val strokeWidthDp = if (tokens.highContrast || isActive) 1.5.dp else 1.dp

    val shadowElevation = if (!elevated) 0.dp else when (level) {
        GlassMaterialLevel.THIN -> 6.dp
        GlassMaterialLevel.REGULAR -> 10.dp
        GlassMaterialLevel.THICK -> 16.dp
    }

    this
        .shadow(
            elevation = shadowElevation,
            shape = shape,
            ambientColor = if ( isActive ) activeColor.copy(alpha = 0.35f) else Color.Black.copy(alpha = 0.35f),
            spotColor = if ( isActive ) activeColor.copy(alpha = 0.45f) else Color.Black.copy(alpha = 0.40f)
        )
        .clip(shape)
        .background(fill, shape)
        .drawWithContent {
            // Subtle top specular inner sheen when transparency is active
            if (!tokens.reduceTransparency) {
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            if (isActive) activeColor.copy(alpha = 0.16f) else colors.contourTop.copy(alpha = 0.10f),
                            Color.Transparent
                        ),
                        startY = 0f,
                        endY = size.height * 0.55f
                    )
                )
            }
            drawContent()

            // Contour Glow (1dp inner gradient stroke; or 1px divider in reduced-transparency mode)
            val strokePx = strokeWidthDp.toPx()
            val radiusPx = if (cornerRadius >= 900.dp) size.minDimension / 2f else cornerRadius.toPx()
            val contourBrush = when {
                isActive -> Brush.verticalGradient(
                    colors = listOf(
                        activeColor.copy(alpha = 0.78f),
                        activeColor.copy(alpha = 0.32f)
                    )
                )
                tokens.reduceTransparency -> Brush.verticalGradient(
                    colors = listOf(colors.divider, colors.divider)
                )
                else -> Brush.verticalGradient(
                    colors = listOf(colors.contourTop, colors.contourBottom)
                )
            }
            drawRoundRect(
                brush = contourBrush,
                cornerRadius = CornerRadius(radiusPx, radiusPx),
                style = Stroke(width = strokePx)
            )
        }
}

/**
 * Applies a calm, solid/raised Living Flow card surface (`surface.solid` / `surface.raised`)
 * with subtle contour glow in dark mode (`radius.lg = 24.dp`).
 */
fun Modifier.livingFlowCalmCard(
    cornerRadius: Dp = 24.dp,
    raised: Boolean = true,
    isActive: Boolean = false
): Modifier = composed {
    val tokens = LivingFlowTheme.tokens
    val colors = tokens.colors
    val shape = RoundedCornerShape(cornerRadius)
    val bg = if (raised) colors.surfaceRaised else colors.surfaceSolid

    this
        .clip(shape)
        .background(bg, shape)
        .drawWithContent {
            drawContent()
            val radiusPx = cornerRadius.toPx()
            val brush = if (isActive) {
                Brush.verticalGradient(
                    listOf(colors.accent.copy(alpha = 0.7f), colors.accent.copy(alpha = 0.25f))
                )
            } else {
                Brush.verticalGradient(
                    listOf(colors.contourTop.copy(alpha = 0.65f), colors.divider)
                )
            }
            drawRoundRect(
                brush = brush,
                cornerRadius = CornerRadius(radiusPx, radiusPx),
                style = Stroke(width = if (isActive || tokens.highContrast) 1.5.dp.toPx() else 1.dp.toPx())
            )
        }
}

/**
 * Progressive blur / scrim gradient under top bars or above the floating navigation island.
 * Ramps smoothly from 0 to material opacity over 48–64dp with no hard edge.
 */
fun Modifier.progressiveEdgeScrim(fromTop: Boolean): Modifier = composed {
    val colors = LivingFlowTheme.colors
    val base = colors.bgBase
    this.background(
        brush = Brush.verticalGradient(
            colors = if (fromTop) {
                listOf(
                    base.copy(alpha = 0.86f),
                    base.copy(alpha = 0.45f),
                    Color.Transparent
                )
            } else {
                listOf(
                    Color.Transparent,
                    base.copy(alpha = 0.55f),
                    base.copy(alpha = 0.92f)
                )
            }
        )
    )
}

/**
 * Flexible Feedback (`spring.press` + `spring.release` + Radial Touch Glow + Haptic Tick).
 *
 * - Compresses to `pressScale` (`0.95f` default, `0.92f` for small circular controls) via `spring.press`.
 * - Draws a radial highlight centered at the pointer that follows the finger and fades out over 250ms.
 * - Rebounds with a single ~3–5% elastic overshoot via `spring.release` on release.
 */
fun Modifier.livingFlowPress(
    enabled: Boolean = true,
    pressScale: Float = 0.95f,
    glowColor: Color? = null,
    onClick: (() -> Unit)? = null
): Modifier = composed {
    val tokens = LivingFlowTheme.tokens
    val view = LocalView.current
    val scope = rememberCoroutineScope()

    val scale = remember { Animatable(1f) }
    val glowAlpha = remember { Animatable(0f) }
    var touchOffset by remember { mutableStateOf(Offset.Zero) }

    this
        .graphicsLayer {
            scaleX = scale.value
            scaleY = scale.value
        }
        .drawWithContent {
            drawContent()
            if (glowAlpha.value > 0.01f) {
                val radius = size.minDimension * 1.25f
                val effectiveGlow = (glowColor ?: tokens.colors.touchGlowColor)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            effectiveGlow.copy(alpha = glowAlpha.value),
                            Color.Transparent
                        ),
                        center = touchOffset,
                        radius = radius.coerceAtLeast(24f)
                    ),
                    center = touchOffset,
                    radius = radius.coerceAtLeast(24f)
                )
            }
        }
        .pointerInput(enabled, tokens.motion.reducedMotion, onClick) {
            if (!enabled) return@pointerInput
            awaitPointerEventScope {
                while (true) {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    touchOffset = down.position
                    view.performLivingFlowHaptic(LivingFlowHaptic.PRESS_TICK)

                    scope.launch {
                        scale.animateTo(
                            targetValue = if (tokens.motion.reducedMotion) 0.98f else pressScale,
                            animationSpec = tokens.motion.pressSpring()
                        )
                    }
                    scope.launch {
                        glowAlpha.snapTo(if (tokens.colors.isDark) 0.16f else 0.20f)
                    }

                    val up = waitForUpOrCancellation()
                    scope.launch {
                        scale.animateTo(
                            targetValue = 1f,
                            animationSpec = tokens.motion.releaseSpring()
                        )
                    }
                    scope.launch {
                        glowAlpha.animateTo(0f, animationSpec = tween(250))
                    }

                    if (up != null && onClick != null) {
                        view.performLivingFlowHaptic(LivingFlowHaptic.CONFIRM)
                        onClick()
                    }
                }
            }
        }
}

enum class LivingFlowHaptic {
    PRESS_TICK,
    DETENT_TICK,
    CONFIRM,
    REJECT
}

fun View.performLivingFlowHaptic(type: LivingFlowHaptic) {
    runCatching {
        val constant = when (type) {
            LivingFlowHaptic.PRESS_TICK -> HapticFeedbackConstants.CONTEXT_CLICK
            LivingFlowHaptic.DETENT_TICK -> HapticFeedbackConstants.CLOCK_TICK
            LivingFlowHaptic.CONFIRM -> if (android.os.Build.VERSION.SDK_INT >= 30) {
                HapticFeedbackConstants.CONFIRM
            } else {
                HapticFeedbackConstants.VIRTUAL_KEY
            }
            LivingFlowHaptic.REJECT -> if (android.os.Build.VERSION.SDK_INT >= 30) {
                HapticFeedbackConstants.REJECT
            } else {
                HapticFeedbackConstants.LONG_PRESS
            }
        }
        performHapticFeedback(constant)
    }
}
