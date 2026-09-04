package cc.uukanshu.ui

import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.painterResource
import cc.uukanshu.R
import cc.uukanshu.data.prefs.Prefs

/**
 * Sun/moon/auto glyph for the theme control. Shows the current mode;
 * tapping cycles system → light → dark. [label] converts the mode name
 * for accessibility (respects simplified mode).
 */
@Composable
fun ThemeIconButton(theme: String, onClick: () -> Unit, label: (String) -> String) {
    val (icon, name) = when (theme) {
        Prefs.LIGHT -> R.drawable.ic_theme_light to "淺色"
        Prefs.DARK -> R.drawable.ic_theme_dark to "深色"
        else -> R.drawable.ic_theme_system to "自動"
    }
    IconButton(onClick = onClick) {
        Icon(painterResource(icon), contentDescription = label(name))
    }
}
