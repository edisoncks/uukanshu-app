package cc.uukanshu.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import android.app.Activity
import cc.uukanshu.data.prefs.Prefs

/**
 * Global theme: dynamic color on minSdk 31, plain schemes as fallback.
 * Pure [isDark] helper is JVM-testable (no Compose needed); the composable
 * only resolves the Android color scheme.
 */
object AppTheme {
    fun isDark(theme: String, systemDark: Boolean): Boolean = when (theme) {
        Prefs.LIGHT -> false
        Prefs.DARK -> true
        else -> systemDark
    }
}

@Composable
fun AppTheme(theme: String, content: @Composable () -> Unit) {
    val dark = AppTheme.isDark(theme, isSystemInDarkTheme())
    val context = LocalContext.current
    MaterialTheme(
        colorScheme = if (dark) {
            runCatching { dynamicDarkColorScheme(context) }.getOrElse { darkColorScheme() }
        } else {
            runCatching { dynamicLightColorScheme(context) }.getOrElse { lightColorScheme() }
        },
    ) {
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
