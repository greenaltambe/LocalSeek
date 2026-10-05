package com.augt.localseek.ui.theme

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * The Material 3 Expressive APIs (MaterialExpressiveTheme, MotionScheme, ButtonGroup, ToggleButton,
 * LoadingIndicator) are internal in the newest STABLE material3 artifact (1.4.0), so they are not
 * usable without a pre-release dependency. This file emulates the look with stable APIs only:
 * spring motion, press-morphing shapes, larger corner radii (see Shapes.kt) and bolder type (Type.kt).
 */

/** Bouncy spring specs used for spatial (size/shape/position) and effect (colour/alpha) animations. */
object ExpressiveMotion {
    fun <T> spatial(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.8f, stiffness = 380f)
    fun <T> effects(): FiniteAnimationSpec<T> = spring(dampingRatio = 1f, stiffness = 1600f)

    /** Taps and presses: bouncy. */
    fun <T> tap(): FiniteAnimationSpec<T> = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium)

    /** Layout changes (expanding cards, sliding banners): softer, no bounce. */
    fun <T> layout(): FiniteAnimationSpec<T> = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessLow)
}

/** Interaction source plus a shape whose corners tighten while pressed, for buttons. */
class PressMorph(val interactionSource: MutableInteractionSource, val shape: Shape)

@Composable
fun rememberPressMorph(rest: Dp = 12.dp, pressed: Dp = 6.dp): PressMorph {
    val source = remember { MutableInteractionSource() }
    val isPressed by source.collectIsPressedAsState()
    val corner by animateDpAsState(
        targetValue = if (isPressed) pressed else rest,
        animationSpec = ExpressiveMotion.spatial(),
        label = "pressMorphCorner"
    )
    return PressMorph(source, RoundedCornerShape(corner))
}
