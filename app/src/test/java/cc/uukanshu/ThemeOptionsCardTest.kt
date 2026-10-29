package cc.uukanshu

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import cc.uukanshu.data.prefs.Prefs
import cc.uukanshu.ui.AppTheme
import cc.uukanshu.ui.ThemeOptionsCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * `ThemeOptionsCard` actually composes and lays out, at the narrow phone width
 * whose budget its KDoc quotes.
 *
 * This exists because v1.2.26 shipped a crash that no unit test could see: the
 * label was wrapped in `BoxWithConstraints` to cap its width, which is a
 * `SubcomposeLayout`, and `SegmentedButton` measures intrinsics — so opening
 * either settings screen threw `NoIntrinsicsMessage` on the first measure.
 * Composition-level tests are the only thing that catches that class of bug,
 * so the smallest one that touches the layout lives here.
 *
 * Layout only; it is not a screenshot test, so it proves the card renders, not
 * that the labels look right at a given font scale.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xhdpi")
class ThemeOptionsCardTest {
    @get:Rule val compose = createComposeRule()

    @Test fun cardRendersAllFourModesAndMarksTheSelectedOne() {
        compose.setContent {
            MaterialTheme(colorScheme = AppTheme.paperScheme()) {
                ThemeOptionsCard(
                    selected = Prefs.PAPER,
                    display = { it },
                    onSelect = {},
                )
            }
        }

        listOf("自動", "淺色", "紙色", "深色").forEach { label ->
            compose.onNodeWithText(label).assertExists()
        }
        compose.onNodeWithText("紙色").assertIsSelected()
    }

    @Test fun tappingAnUnselectedModeReportsIt() {
        var picked: String? = null
        compose.setContent {
            MaterialTheme(colorScheme = AppTheme.paperScheme()) {
                ThemeOptionsCard(
                    selected = Prefs.SYSTEM,
                    display = { it },
                    onSelect = { picked = it },
                )
            }
        }

        compose.onNodeWithText("深色").performClick()
        assertEquals(Prefs.DARK, picked)
    }

    /**
     * Every label stays inside its own segment at the phone widths and font
     * scales the app is used at. Measured, not argued: earlier attempts to
     * reason about this budget in dp got it wrong twice (the 26dp icon reserve
     * is not subtracted from the label's width — material3 measures the label
     * against the whole inner width and lets the icon eat into the 12dp
     * padding), so the numbers a doc quotes now come from these bounds.
     *
     * A label that overruns gets shaved by the segment's `Surface`, which is
     * what users would see as clipped glyphs; the assertion is simply that
     * nothing is out of bounds. Covers the range that matters; at a 2.0x font
     * scale the selected label does get shaved by ~1dp at 360dp, which is
     * material's own behaviour, not a regression this repo introduced.
     */
    private fun assertLabelsFitInsideSegments() {
        RuntimeEnvironment.setFontScale(fontScale)
        compose.setContent {
            MaterialTheme(colorScheme = AppTheme.paperScheme()) {
                // The real screen: 16dp screen padding around the card.
                Column(Modifier.fillMaxSize().padding(16.dp)) {
                    ThemeOptionsCard(selected = Prefs.PAPER, display = { it }, onSelect = {})
                }
            }
        }
        listOf("自動", "淺色", "紙色", "深色").forEach { label ->
            val segment = compose.onNodeWithText(label).getBoundsInRoot()
            val text = compose.onNodeWithText(label, useUnmergedTree = true).getBoundsInRoot()
            assertTrue(
                "$label runs past its segment: text [${text.left}..${text.right}] " +
                    "segment [${segment.left}..${segment.right}]",
                text.left >= segment.left && text.right <= segment.right,
            )
        }
    }

    private var fontScale: Float = 1f

    @Test @Config(sdk = [34], qualifiers = "w320dp-h800dp-xhdpi")
    fun labelsFitSmallPhone() {
        fontScale = 1f
        assertLabelsFitInsideSegments()
    }

    @Test @Config(sdk = [34], qualifiers = "w320dp-h800dp-xhdpi")
    fun labelsFitSmallPhoneLargeFont() {
        fontScale = 1.3f
        assertLabelsFitInsideSegments()
    }

    @Test @Config(sdk = [34], qualifiers = "w360dp-h800dp-xhdpi")
    fun labelsFitNarrowPhone() {
        fontScale = 1f
        assertLabelsFitInsideSegments()
    }

    @Test @Config(sdk = [34], qualifiers = "w360dp-h800dp-xhdpi")
    fun labelsFitNarrowPhoneLargeFont() {
        fontScale = 1.3f
        assertLabelsFitInsideSegments()
    }

    @Test @Config(sdk = [34], qualifiers = "w393dp-h800dp-xhdpi")
    fun labelsFitTypicalPhone() {
        fontScale = 1f
        assertLabelsFitInsideSegments()
    }
}
