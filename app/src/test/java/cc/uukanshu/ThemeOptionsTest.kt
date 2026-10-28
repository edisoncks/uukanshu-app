package cc.uukanshu

import cc.uukanshu.data.prefs.Prefs
import cc.uukanshu.ui.THEME_OPTIONS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The appearance row ([cc.uukanshu.ui.ThemeOptionsCard]) is how a theme is
 * picked on both screens that show a picker — the app settings screen and the
 * reader settings sheet — so its option list is the UI half of the theme
 * contract (docs/ARCHITECTURE.md § Theme): a mode added to `Prefs`/`AppTheme`
 * but not here is silently unreachable. `ThemeOptionsCard` renders this order,
 * and `system` comes first because it is the default a fresh install runs.
 *
 * These pin the list, not the rendering: the repo has no Compose UI tests, so
 * neither the row's click wiring nor its layout is covered here.
 */
class ThemeOptionsTest {
    private val modes = listOf(Prefs.SYSTEM, Prefs.LIGHT, Prefs.PAPER, Prefs.DARK)

    @Test fun rowOffersEveryModeExactlyOnce() {
        assertEquals(modes, THEME_OPTIONS.map { it.first })
    }

    @Test fun everyOptionCarriesItsOwnNormalizedValueAndALabel() {
        THEME_OPTIONS.forEach { (value, label) ->
            assertEquals("'$value' is not a normalized mode", value, Prefs.normalizeTheme(value))
            assertTrue("empty label for '$value'", label.isNotEmpty())
        }
    }

    /**
     * Four segments share one line because each label is two glyphs, which is
     * the width the row is designed around (see the width note on
     * `ThemeOptionsCard`). This asserts length only — it cannot see layout, so
     * it does not prove the labels fit, only that nobody folds a subtitle like
     * `跟隨系統` into a segment without making that a decision.
     */
    @Test fun labelsStayTwoGlyphs() {
        THEME_OPTIONS.forEach { (value, label) ->
            assertTrue("label '$label' for '$value' outgrew a four-segment row", label.length <= 2)
        }
    }
}
