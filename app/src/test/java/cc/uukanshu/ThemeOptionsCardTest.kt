package cc.uukanshu

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import cc.uukanshu.data.prefs.Prefs
import cc.uukanshu.ui.AppTheme
import cc.uukanshu.ui.ThemeOptionsCard
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
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
}
