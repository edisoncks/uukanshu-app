package cc.uukanshu

import cc.uukanshu.data.prefs.Prefs
import cc.uukanshu.ui.THEME_OPTIONS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The appearance row ([cc.uukanshu.ui.ThemeOptionsRow]) is how a theme is
 * picked on both screens that show a picker — the app settings screen and the
 * reader settings sheet — so its option list is the UI half of the theme
 * contract (docs/ARCHITECTURE.md § Theme): a mode added to `Prefs`/`AppTheme`
 * but not here is silently unreachable. (The reader's own theme toggle cycles
 * `Prefs.next`, so a mode can also be reached without this row; these tests
 * keep the row and that cycle from disagreeing.)
 *
 * These pin the list, not the rendering: the repo has no Compose UI tests, so
 * neither the row's click wiring nor its layout is covered here.
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

    /**
     * The row fits four segments across a phone width because each label is
     * short (see the width budget in `ThemeOptionsRow`). A longer label — a
     * subtitle folded into the segment, say — would ellipsize, so it has to be
     * a decision, not a slip: widen this bound only with a layout that holds.
     */
    @Test fun labelsStayShortEnoughForFourSegments() {
        THEME_OPTIONS.forEach { (value, label) ->
            assertTrue("label '$label' for '$value' outgrew a four-segment row", label.length <= 2)
        }
    }
}
