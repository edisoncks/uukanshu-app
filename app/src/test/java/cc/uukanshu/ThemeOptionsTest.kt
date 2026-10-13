package cc.uukanshu

import cc.uukanshu.data.prefs.Prefs
import cc.uukanshu.ui.settings.THEME_OPTIONS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The appearance row is the only place a theme can be picked, so its option
 * list is the UI half of the theme contract (docs/ARCHITECTURE.md § Settings):
 * a mode added to `Prefs`/`AppTheme` but not here is silently unreachable.
 * These tests pin the row to the mode set and to the cycle the reader's theme
 * toggle walks, so the two ways of changing a theme cannot disagree.
 */
class ThemeOptionsTest {
    private val modes = listOf(Prefs.SYSTEM, Prefs.LIGHT, Prefs.PAPER, Prefs.DARK)

    @Test fun rowOffersEveryModeExactlyOnce() {
        assertEquals(modes, THEME_OPTIONS.map { it.first })
    }

    @Test fun rowOrderMatchesTheReaderToggleCycle() {
        var cursor = Prefs.SYSTEM
        val walked = modes.map { cursor.also { cursor = Prefs.next(cursor) } }
        assertEquals(THEME_OPTIONS.map { it.first }, walked)
        assertEquals("cycle does not close back on SYSTEM", Prefs.SYSTEM, cursor)
    }

    @Test fun everyOptionCarriesItsOwnNormalizedValueAndALabel() {
        THEME_OPTIONS.forEach { (value, label) ->
            assertEquals("'$value' is not a normalized mode", value, Prefs.normalizeTheme(value))
            assertTrue("empty label for '$value'", label.isNotEmpty())
        }
    }
}
