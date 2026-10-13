package cc.uukanshu

import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import cc.uukanshu.data.prefs.Prefs
import cc.uukanshu.ui.AppTheme
import cc.uukanshu.ui.PaperPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Paper is a fixed scheme (docs/ARCHITECTURE.md § Theme): these tests pin the
 * palette to [PaperPalette] so the scheme can never drift from the constants,
 * and pin a contrast floor so a "nicer" hex cannot make text unreadable.
 */
class AppThemeTest {
    private val paper = AppTheme.paperScheme()

    @Test fun paperMapsEveryRoleToTheFixedPalette() {
        assertEquals(PaperPalette.Background, paper.background)
        assertEquals(PaperPalette.OnBackground, paper.onBackground)
        assertEquals(PaperPalette.Surface, paper.surface)
        assertEquals(PaperPalette.OnSurface, paper.onSurface)
        assertEquals(PaperPalette.SurfaceVariant, paper.surfaceVariant)
        assertEquals(PaperPalette.OnSurfaceVariant, paper.onSurfaceVariant)
        assertEquals(PaperPalette.SurfaceTint, paper.surfaceTint)
        assertEquals(PaperPalette.SurfaceBright, paper.surfaceBright)
        assertEquals(PaperPalette.SurfaceDim, paper.surfaceDim)
        assertEquals(PaperPalette.SurfaceContainer, paper.surfaceContainer)
        assertEquals(PaperPalette.SurfaceContainerLow, paper.surfaceContainerLow)
        assertEquals(PaperPalette.SurfaceContainerLowest, paper.surfaceContainerLowest)
        assertEquals(PaperPalette.SurfaceContainerHigh, paper.surfaceContainerHigh)
        assertEquals(PaperPalette.SurfaceContainerHighest, paper.surfaceContainerHighest)
        assertEquals(PaperPalette.Outline, paper.outline)
        assertEquals(PaperPalette.OutlineVariant, paper.outlineVariant)
        assertEquals(PaperPalette.Primary, paper.primary)
        assertEquals(PaperPalette.OnPrimary, paper.onPrimary)
        assertEquals(PaperPalette.PrimaryContainer, paper.primaryContainer)
        assertEquals(PaperPalette.OnPrimaryContainer, paper.onPrimaryContainer)
        assertEquals(PaperPalette.InversePrimary, paper.inversePrimary)
        assertEquals(PaperPalette.InverseSurface, paper.inverseSurface)
        assertEquals(PaperPalette.InverseOnSurface, paper.inverseOnSurface)
        assertEquals(PaperPalette.Scrim, paper.scrim)
        assertEquals(PaperPalette.Secondary, paper.secondary)
        assertEquals(PaperPalette.OnSecondary, paper.onSecondary)
        assertEquals(PaperPalette.SecondaryContainer, paper.secondaryContainer)
        assertEquals(PaperPalette.OnSecondaryContainer, paper.onSecondaryContainer)
        assertEquals(PaperPalette.Tertiary, paper.tertiary)
        assertEquals(PaperPalette.OnTertiary, paper.onTertiary)
        assertEquals(PaperPalette.TertiaryContainer, paper.tertiaryContainer)
        assertEquals(PaperPalette.OnTertiaryContainer, paper.onTertiaryContainer)
        assertEquals(PaperPalette.Error, paper.error)
        assertEquals(PaperPalette.OnError, paper.onError)
        assertEquals(PaperPalette.ErrorContainer, paper.errorContainer)
        assertEquals(PaperPalette.OnErrorContainer, paper.onErrorContainer)
    }

    /**
     * Guards the two ways "fully fixed, no Material You" can regress: an
     * unset role falling back to the baseline (purple) scheme, or a surface
     * role drifting away from the warm hue family.
     */
    @Test fun paperKeepsNoBaselineOrCoolHue() {
        val baseline = lightColorScheme()
        assertTrue("primary is baseline purple", paper.primary != baseline.primary)
        assertTrue("secondary is baseline purple", paper.secondary != baseline.secondary)
        listOf(
            "surface" to paper.surface,
            "background" to paper.background,
            "surfaceVariant" to paper.surfaceVariant,
            "surfaceContainer" to paper.surfaceContainer,
            "surfaceContainerLow" to paper.surfaceContainerLow,
            "surfaceContainerLowest" to paper.surfaceContainerLowest,
            "surfaceContainerHigh" to paper.surfaceContainerHigh,
            "surfaceContainerHighest" to paper.surfaceContainerHighest,
            "surfaceBright" to paper.surfaceBright,
            "surfaceDim" to paper.surfaceDim,
        ).forEach { (name, c) ->
            assertTrue(
                "$name is not warm: r=${c.red} g=${c.green} b=${c.blue}",
                c.red >= c.green && c.green >= c.blue,
            )
        }
    }

    @Test fun paperStaysWarmLightEvenWhenSystemIsDark() {
        assertFalse(AppTheme.isDark(Prefs.PAPER, systemDark = true))
        assertTrue(paper.onSurface.luminance() < paper.surface.luminance())
        assertTrue(paper.onBackground.luminance() < paper.background.luminance())
    }

    @Test fun foregroundOnContainerPairsKeepAaContrast() {
        // Pairs the app actually renders: body text, secondary labels,
        // filled/tonal buttons, error banners, the reader settings preview,
        // the snackbars (inverse roles).
        listOf(
            "surface/onSurface" to (paper.onSurface to paper.surface),
            "background/onBackground" to (paper.onBackground to paper.background),
            "surfaceVariant/onSurfaceVariant" to (paper.onSurfaceVariant to paper.surfaceVariant),
            "primary/onPrimary" to (paper.onPrimary to paper.primary),
            "primaryContainer/onPrimaryContainer" to (paper.onPrimaryContainer to paper.primaryContainer),
            "secondaryContainer/onSecondaryContainer" to (paper.onSecondaryContainer to paper.secondaryContainer),
            "tertiaryContainer/onTertiaryContainer" to (paper.onTertiaryContainer to paper.tertiaryContainer),
            "errorContainer/onErrorContainer" to (paper.onErrorContainer to paper.errorContainer),
            "surfaceContainer/onSurface" to (paper.onSurface to paper.surfaceContainer),
            "inverseSurface/inverseOnSurface" to (paper.inverseOnSurface to paper.inverseSurface),
        ).forEach { (name, pair) ->
            val ratio = contrast(pair.first, pair.second)
            assertTrue("$name contrast $ratio < 4.5", ratio >= 4.5)
        }
    }

    private fun contrast(a: Color, b: Color): Double {
        val la = a.luminance().toDouble()
        val lb = b.luminance().toDouble()
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }
}
