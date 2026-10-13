package cc.uukanshu.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import android.app.Activity
import cc.uukanshu.data.prefs.Prefs

/**
 * Global theme: dynamic color on minSdk 31, plain schemes as fallback.
 * Pure [isDark] helper is JVM-testable (no Compose needed); the composable
 * only resolves the Android color scheme.
 * PAPER is a warm-light theme with a FIXED palette ([PaperPalette]): the one
 * theme that ignores Material You, so it renders identically on every device
 * and Android version (docs/ARCHITECTURE.md § Theme).
 */
object AppTheme {
    fun isDark(theme: String, systemDark: Boolean): Boolean = when (theme) {
        Prefs.LIGHT, Prefs.PAPER -> false
        Prefs.DARK -> true
        else -> systemDark
    }

    /**
     * The PAPER scheme. Deliberately takes no [android.content.Context]: without
     * one, dynamic color is structurally impossible here, so the palette cannot
     * silently start following the wallpaper again.
     *
     * Every role is named — nothing falls back to the baseline purple scheme.
     */
    fun paperScheme(): ColorScheme = lightColorScheme(
        background = PaperPalette.Background,
        onBackground = PaperPalette.OnBackground,
        surface = PaperPalette.Surface,
        onSurface = PaperPalette.OnSurface,
        surfaceVariant = PaperPalette.SurfaceVariant,
        onSurfaceVariant = PaperPalette.OnSurfaceVariant,
        surfaceTint = PaperPalette.SurfaceTint,
        surfaceBright = PaperPalette.SurfaceBright,
        surfaceDim = PaperPalette.SurfaceDim,
        surfaceContainer = PaperPalette.SurfaceContainer,
        surfaceContainerLow = PaperPalette.SurfaceContainerLow,
        surfaceContainerLowest = PaperPalette.SurfaceContainerLowest,
        surfaceContainerHigh = PaperPalette.SurfaceContainerHigh,
        surfaceContainerHighest = PaperPalette.SurfaceContainerHighest,
        outline = PaperPalette.Outline,
        outlineVariant = PaperPalette.OutlineVariant,
        primary = PaperPalette.Primary,
        onPrimary = PaperPalette.OnPrimary,
        primaryContainer = PaperPalette.PrimaryContainer,
        onPrimaryContainer = PaperPalette.OnPrimaryContainer,
        inversePrimary = PaperPalette.InversePrimary,
        inverseSurface = PaperPalette.InverseSurface,
        inverseOnSurface = PaperPalette.InverseOnSurface,
        scrim = PaperPalette.Scrim,
        secondary = PaperPalette.Secondary,
        onSecondary = PaperPalette.OnSecondary,
        secondaryContainer = PaperPalette.SecondaryContainer,
        onSecondaryContainer = PaperPalette.OnSecondaryContainer,
        tertiary = PaperPalette.Tertiary,
        onTertiary = PaperPalette.OnTertiary,
        tertiaryContainer = PaperPalette.TertiaryContainer,
        onTertiaryContainer = PaperPalette.OnTertiaryContainer,
        error = PaperPalette.Error,
        onError = PaperPalette.OnError,
        errorContainer = PaperPalette.ErrorContainer,
        onErrorContainer = PaperPalette.OnErrorContainer,
    )
}

/**
 * Fixed sepia palette for [Prefs.PAPER]. The neutral roles are the reader's
 * original warm-light colors, kept identical so paper mode looks the same in
 * the reader as it always did — now applied app-wide instead of locally.
 *
 * Contrast of every foreground/container pair used by the app is pinned by
 * `AppThemeTest`; changing a value here means re-checking it there.
 */
internal object PaperPalette {
    val Background = Color(0xFFF6F0DC)
    val OnBackground = Color(0xFF3E3525)
    val Surface = Color(0xFFF6F0DC)
    val OnSurface = Color(0xFF3E3525)
    val SurfaceVariant = Color(0xFFE6DEC4)
    val OnSurfaceVariant = Color(0xFF6B5F45)
    // Warm tint: elevation shading must never drag a sepia surface toward a
    // wallpaper hue the rest of this scheme does not use.
    val SurfaceTint = Color(0xFF7A5620)
    val SurfaceBright = Color(0xFFFFFCF2)
    val SurfaceDim = Color(0xFFE6DFC5)
    val SurfaceContainerLowest = Color(0xFFFFFCF2)
    val SurfaceContainerLow = Color(0xFFFCF7E8)
    val SurfaceContainer = Color(0xFFF6F0DC)
    val SurfaceContainerHigh = Color(0xFFF1EAD2)
    val SurfaceContainerHighest = Color(0xFFEBE4C9)
    val Outline = Color(0xFF857A5F)
    val OutlineVariant = Color(0xFFD8CEB2)

    val Primary = Color(0xFF7A5620)
    val OnPrimary = Color(0xFFFFFFFF)
    val PrimaryContainer = Color(0xFFF0DFBC)
    val OnPrimaryContainer = Color(0xFF291800)
    val InversePrimary = Color(0xFFE8C48C)
    val InverseSurface = Color(0xFF3A3124)
    val InverseOnSurface = Color(0xFFF3ECDA)
    val Scrim = Color(0xFF000000)

    val Secondary = Color(0xFF6B5D45)
    val OnSecondary = Color(0xFFFFFFFF)
    val SecondaryContainer = Color(0xFFEBDEC4)
    val OnSecondaryContainer = Color(0xFF251A00)

    val Tertiary = Color(0xFF5A6147)
    val OnTertiary = Color(0xFFFFFFFF)
    val TertiaryContainer = Color(0xFFDEE5C8)
    val OnTertiaryContainer = Color(0xFF181E06)

    // Error stays the Material baseline red: a semantic role, never tinted
    // sepia, or a failure would stop reading as a failure.
    val Error = Color(0xFFBA1A1A)
    val OnError = Color(0xFFFFFFFF)
    val ErrorContainer = Color(0xFFFFDAD6)
    val OnErrorContainer = Color(0xFF410002)
}

@Composable
fun AppTheme(theme: String, content: @Composable () -> Unit) {
    val dark = AppTheme.isDark(theme, isSystemInDarkTheme())
    val context = LocalContext.current
    // remember: paperScheme() and the dynamic schemes build a ColorScheme on
    // every call, and this composable recomposes on every theme/dark flip.
    val scheme = remember(theme, dark, context) {
        when {
            theme == Prefs.PAPER -> AppTheme.paperScheme()
            dark -> runCatching { dynamicDarkColorScheme(context) }.getOrElse { darkColorScheme() }
            else -> runCatching { dynamicLightColorScheme(context) }.getOrElse { lightColorScheme() }
        }
    }
    MaterialTheme(colorScheme = scheme) {
        // targetSdk 35: Android 15 enforces edge-to-edge — the (now transparent)
        // status/navigation bars draw over the app background, so system-bar icon
        // appearance must follow the IN-APP theme (the window theme is static
        // Material.Light and would leave white icons on a light background).
        val view = LocalView.current
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
        content()
    }
}
