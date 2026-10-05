package com.augt.localseek.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.augt.localseek.tools.AccentPreset
import com.augt.localseek.tools.ThemeSettings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

internal val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFD0BCFF),
    onPrimary = Color(0xFF381E72),
    primaryContainer = Color(0xFF4F378B),
    onPrimaryContainer = Color(0xFFEADDFF),
    secondary = Color(0xFFCCC2DC),
    onSecondary = Color(0xFF332D41),
    secondaryContainer = Color(0xFF4A4458),
    onSecondaryContainer = Color(0xFFE8DEF8),
    tertiary = Color(0xFFEFB8C8),
    onTertiary = Color(0xFF492532),
    tertiaryContainer = Color(0xFF633B48),
    onTertiaryContainer = Color(0xFFFFD8E4),
    error = Color(0xFFF2B8B5),
    errorContainer = Color(0xFF8C1D18),
    onError = Color(0xFF601410),
    onErrorContainer = Color(0xFFF9DEDC),
    background = Color(0xFF1C1B1F),
    onBackground = Color(0xFFE6E1E5),
    surface = Color(0xFF1C1B1F),
    onSurface = Color(0xFFE6E1E5),
    surfaceVariant = Color(0xFF49454F),
    onSurfaceVariant = Color(0xFFCAC4D0),
    outline = Color(0xFF938F99),
    inverseOnSurface = Color(0xFF1C1B1F),
    inverseSurface = Color(0xFFE6E1E5),
    inversePrimary = Color(0xFF6750A4),
    surfaceContainerLowest = Color(0xFF0F0D13),
    surfaceContainerLow = Color(0xFF1D1B20),
    surfaceContainer = Color(0xFF211F26),
    surfaceContainerHigh = Color(0xFF2B2930),
    surfaceContainerHighest = Color(0xFF36343B)
)

internal val LightColorScheme = lightColorScheme(
    primary = Color(0xFF6750A4),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEADDFF),
    onPrimaryContainer = Color(0xFF21005D),
    secondary = Color(0xFF625B71),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE8DEF8),
    onSecondaryContainer = Color(0xFF1D192B),
    tertiary = Color(0xFF7D5260),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFD8E4),
    onTertiaryContainer = Color(0xFF31111D),
    error = Color(0xFFB3261E),
    errorContainer = Color(0xFFF9DEDC),
    onError = Color(0xFFFFFFFF),
    onErrorContainer = Color(0xFF410E0B),
    background = Color(0xFFFFFBFE),
    onBackground = Color(0xFF1C1B1F),
    surface = Color(0xFFFFFBFE),
    onSurface = Color(0xFF1C1B1F),
    surfaceVariant = Color(0xFFE7E0EC),
    onSurfaceVariant = Color(0xFF49454F),
    outline = Color(0xFF79747E),
    inverseOnSurface = Color(0xFFF4EFF4),
    inverseSurface = Color(0xFF313033),
    inversePrimary = Color(0xFFD0BCFF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F2FA),
    surfaceContainer = Color(0xFFF3EDF7),
    surfaceContainerHigh = Color(0xFFECE6F0),
    surfaceContainerHighest = Color(0xFFE6E0E9)
)

internal val GreenLightColorScheme = LightColorScheme.copy(
    primary = Color(0xFF386A20), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFB8F397), onPrimaryContainer = Color(0xFF072100),
    secondary = Color(0xFF55624C), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD9E7CB), onSecondaryContainer = Color(0xFF131F0D),
    tertiary = Color(0xFF19686A), onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFBCEBEE), onTertiaryContainer = Color(0xFF002021),
    inversePrimary = Color(0xFF9DD67D),
    background = Color(0xFFFDFDF5), surface = Color(0xFFFDFDF5),
    surfaceVariant = Color(0xFFDFE4D7), onSurfaceVariant = Color(0xFF43483E),
    outline = Color(0xFF73796D),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F8EF),
    surfaceContainer = Color(0xFFF1F3EA),
    surfaceContainerHigh = Color(0xFFECEEE4),
    surfaceContainerHighest = Color(0xFFE6E9DF)
)

internal val GreenDarkColorScheme = DarkColorScheme.copy(
    primary = Color(0xFF9DD67D), onPrimary = Color(0xFF0F3900),
    primaryContainer = Color(0xFF205107), onPrimaryContainer = Color(0xFFB8F397),
    secondary = Color(0xFFBDCBB0), onSecondary = Color(0xFF283420),
    secondaryContainer = Color(0xFF3E4A36), onSecondaryContainer = Color(0xFFD9E7CB),
    tertiary = Color(0xFFA0CFD2), onTertiary = Color(0xFF00373A),
    tertiaryContainer = Color(0xFF004F52), onTertiaryContainer = Color(0xFFBCEBEE),
    inversePrimary = Color(0xFF386A20),
    background = Color(0xFF1A1C18), surface = Color(0xFF1A1C18),
    surfaceVariant = Color(0xFF43483E), onSurfaceVariant = Color(0xFFC3C8BB),
    outline = Color(0xFF8D9287),
    surfaceContainerLowest = Color(0xFF0F110D),
    surfaceContainerLow = Color(0xFF1E201A),
    surfaceContainer = Color(0xFF222520),
    surfaceContainerHigh = Color(0xFF2C2F2A),
    surfaceContainerHighest = Color(0xFF373A34)
)

@Composable
fun LocalSeekTheme(
    themeSettings: ThemeSettings = ThemeSettings(),
    content: @Composable () -> Unit
) {
    val darkTheme = themeSettings.isDark(isSystemInDarkTheme())
    val context = LocalContext.current
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && themeSettings.useDynamic(Build.VERSION.SDK_INT) ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        themeSettings.accent == AccentPreset.GREEN -> if (darkTheme) GreenDarkColorScheme else GreenLightColorScheme
        else -> if (darkTheme) DarkColorScheme else LightColorScheme
    }

    // Keep status/navigation bar icon contrast right when the user forces light/dark against the system setting.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !darkTheme
            controller.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        shapes = ExpressiveShapes,
        typography = Typography,
        content = content
    )
}
