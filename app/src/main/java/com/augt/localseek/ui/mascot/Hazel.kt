package com.augt.localseek.ui.mascot

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.augt.localseek.R
import com.augt.localseek.ui.theme.LocalSeekTheme
import com.augt.localseek.ui.theme.rememberAnimationsEnabled
import kotlin.math.PI
import kotlin.math.sin

/**
 * Hazel, the LocalSeek squirrel: ONE drawing shared with the launcher icon. The layers are vector drawables generated from the same
 * geometry as ic_launcher_foreground (docs/design/icon_candidates/round2/gen_hazel.py). [HazelMood.HAPPY] and [HazelMood.WORKING]
 * hold the magnifier in both paws; [HazelMood.EMPTY] has empty paws and a small shrug of the tail.
 */
enum class HazelMood { HAPPY, WORKING, EMPTY }

/** Layers drawn back to front for [mood]; pure so a test can pin which art each mood shows. */
internal fun hazelLayers(mood: HazelMood): List<Int> =
    listOf(R.drawable.hazel_tail, R.drawable.hazel_body, if (mood == HazelMood.EMPTY) R.drawable.hazel_paws_rest else R.drawable.hazel_lens)

/** Tail pivot (fraction of the 72 unit layer) = base of the tail behind the body. */
private val TailPivot = TransformOrigin(0.53f, 0.83f)

/** [propLift] is the magnifier bob in dp-fractions of [size] and [tailSwing] the tail angle in degrees; both are small animation offsets. */
@Composable
fun HazelCanvas(
    mood: HazelMood,
    modifier: Modifier = Modifier,
    size: Dp = 96.dp,
    propLift: Float = 0f,
    tailSwing: Float = 0f
) {
    Box(modifier = modifier.size(size)) {
        val layers = hazelLayers(mood)
        layers.forEachIndexed { index, res ->
            Image(
                painter = painterResource(res), contentDescription = null,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    when (index) {
                        0 -> { rotationZ = tailSwing; transformOrigin = TailPivot }
                        2 -> translationY = -propLift * this.size.height / 100f
                    }
                }
            )
        }
    }
}

/** Mascot art or, when "Mascot and playful text" is off, a plain icon of the same size. */
@Composable
fun MascotOrIcon(
    enabled: Boolean,
    plainIcon: ImageVector,
    modifier: Modifier = Modifier,
    mood: HazelMood = HazelMood.HAPPY,
    size: Dp = 96.dp
) {
    if (enabled) {
        HazelCanvas(mood = mood, modifier = modifier, size = size)
    } else {
        Icon(
            imageVector = plainIcon, contentDescription = null, modifier = modifier.size(size),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        )
    }
}

/**
 * Loader: Hazel bobbing her magnifier with a swishing tail, one 1.2 s loop, 48 to 64 dp. Static when the user removed
 * animations. The caller decides when to show it (the search screen waits 150 ms so fast searches never flicker).
 */
@Composable
fun HazelLoader(modifier: Modifier = Modifier, size: Dp = 56.dp) {
    if (!rememberAnimationsEnabled()) {
        HazelCanvas(HazelMood.WORKING, modifier, size)
        return
    }
    val transition = rememberInfiniteTransition(label = "hazelLoader")
    val phase by transition.animateFloat(
        initialValue = 0f, targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(durationMillis = 1200, easing = LinearEasing), RepeatMode.Restart),
        label = "phase"
    )
    HazelCanvas(
        mood = HazelMood.WORKING, modifier = modifier, size = size,
        propLift = 2.5f * (1f + sin(phase)), tailSwing = 7f * sin(phase + 1.2f)
    )
}

@Preview(showBackground = true, name = "Hazel moods")
@Composable
private fun HazelPreview() {
    LocalSeekTheme {
        androidx.compose.foundation.layout.Row {
            HazelCanvas(HazelMood.HAPPY)
            HazelCanvas(HazelMood.WORKING)
            HazelCanvas(HazelMood.EMPTY)
        }
    }
}
