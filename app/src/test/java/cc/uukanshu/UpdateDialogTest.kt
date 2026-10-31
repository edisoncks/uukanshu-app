package cc.uukanshu

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import cc.uukanshu.ui.AppTheme
import cc.uukanshu.ui.update.UpdateDialog
import cc.uukanshu.ui.update.UpdateViewModel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The failed-check branch of the update dialog must render the browser fallback.
 *
 * `ARCHITECTURE.md` § In-app update promises it, and a failed check is exactly
 * when no release exists to download from — the promise is worth nothing if the
 * button is not on screen. Composition-level, because no unit test can see which
 * buttons a branch renders.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xhdpi")
class UpdateDialogTest {
    @get:Rule val compose = createComposeRule()

    /** `info == null` + an error is the failed-check branch. */
    private fun failedCheck(onBrowser: () -> Unit) {
        compose.setContent {
            MaterialTheme(colorScheme = AppTheme.paperScheme()) {
                UpdateDialog(
                    ui = UpdateViewModel.Ui(visible = true, error = "離線，檢查更新失敗"),
                    display = { it },
                    onDownload = {},
                    onInstall = {},
                    onCancelDownload = {},
                    onSkip = {},
                    onDismiss = {},
                    onRetry = {},
                    onBrowser = onBrowser,
                    onOpenUnknownSources = {},
                )
            }
        }
    }

    @Test fun failedCheckOffersTheBrowserFallback() {
        var opened = 0
        failedCheck { opened++ }

        compose.onNodeWithText("改用瀏覽器下載").assertExists()
        compose.onNodeWithText("改用瀏覽器下載").performClick()
        assertEquals("the fallback must reach the browser", 1, opened)
    }
}
