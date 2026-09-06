package cc.uukanshu

import androidx.test.core.app.ApplicationProvider
import cc.uukanshu.data.prefs.Prefs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class PrefsStoreTest {
    private fun prefs() = Prefs(ApplicationProvider.getApplicationContext())

    @Before fun resetToDefaults() = kotlinx.coroutines.runBlocking {
        // Same DataStore file persists across tests in one Robolectric app: reset first.
        val p = prefs()
        p.setSimplified(false)
        p.setFontScale(Prefs.FONT_DEFAULT)
        p.setTheme(Prefs.SYSTEM)
    }

    @Test fun defaultsAreTraditionalSystem() = runTest {
        val p = prefs()
        assertEquals(false, p.simplified.first())
        assertEquals(1f, p.fontScale.first())
        assertEquals(Prefs.SYSTEM, p.theme.first())
    }

    @Test fun fontWriteClampsToBounds() = runTest {
        val p = prefs()
        p.setFontScale(99f)
        assertEquals(Prefs.FONT_MAX, p.fontScale.first())
        p.setFontScale(-1f)
        assertEquals(Prefs.FONT_MIN, p.fontScale.first())
    }

    @Test fun simplifiedRoundtrip() = runTest {
        val p = prefs()
        p.setSimplified(true)
        assertEquals(true, p.simplified.first())
        p.setSimplified(false)
        assertEquals(false, p.simplified.first())
    }

    @Test fun themeRoundtrip() = runTest {
        val p = prefs()
        p.setTheme(Prefs.DARK)
        assertEquals(Prefs.DARK, p.theme.first())
    }
}
