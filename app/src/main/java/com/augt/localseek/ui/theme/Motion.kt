package com.augt.localseek.ui.theme

import android.content.Context
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/** True unless the user turned animations off ("Remove animations" / animator duration scale 0). */
fun animationsEnabled(animatorDurationScale: Float): Boolean = animatorDurationScale != 0f

fun readAnimatorDurationScale(context: Context): Float = try {
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
} catch (_: Exception) {
    1f
}

/** Decorative motion (mascot loop, bouncing taps, slide-ins) should be skipped when this is false. */
@Composable
fun rememberAnimationsEnabled(): Boolean {
    val context = LocalContext.current
    return remember { animationsEnabled(readAnimatorDurationScale(context)) }
}
