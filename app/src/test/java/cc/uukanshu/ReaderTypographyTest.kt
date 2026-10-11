package cc.uukanshu

import cc.uukanshu.data.convert.T2S
import cc.uukanshu.data.prefs.Prefs
import cc.uukanshu.ui.reader.ReaderTypography
import cc.uukanshu.ui.reader.ReaderViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ReaderTypographyTest {
    @Test fun baseFormulaMatchesOldHardcoded() {
        assertEquals(17f, ReaderTypography.fontSizeSp(1f), 0.001f)
        assertEquals(28f, ReaderTypography.lineHeightSp(1f, 1f), 0.001f)
        assertEquals(12f, ReaderTypography.paragraphSpacingDp(1f), 0.001f)
    }

    @Test fun scalesMultiplyOrthogonally() {
        assertEquals(17f * 1.2f, ReaderTypography.fontSizeSp(1.2f), 0.001f)
        assertEquals(28f * 1.2f * 1.1f, ReaderTypography.lineHeightSp(1.2f, 1.1f), 0.001f)
        assertEquals(12f * 0.5f, ReaderTypography.paragraphSpacingDp(0.5f), 0.001f)
    }

    @Test fun prefsNormalizeAndCoerce() {
        assertEquals(Prefs.READER_FONT_SANS, Prefs.normalizeReaderFont("kaiti"))
        assertEquals(Prefs.READER_FONT_SERIF, Prefs.normalizeReaderFont(Prefs.READER_FONT_SERIF))
        assertEquals(Prefs.LINE_MAX, Prefs.coerceLineScale(99f), 0.001f)
        assertEquals(Prefs.LINE_MIN, Prefs.coerceLineScale(-1f), 0.001f)
        assertEquals(Prefs.PARA_MAX, Prefs.coerceParaScale(99f), 0.001f)
        assertEquals(Prefs.PARA_MIN, Prefs.coerceParaScale(-1f), 0.001f)
    }
}

class ReaderTypographyVmTest {
    @get:Rule val main = MainDispatcherRule()

    private fun idle() {
        main.dispatcher.scheduler.advanceUntilIdle()
    }

    @Test fun typographySettersPersistAndIdempotent() = runTest {
        val prefs = MutableFakePrefs()
        val repo = MutableFakeRepo(
            fresh = testDetail(101L),
            chaptersText = mutableMapOf(101L to "t1"),
        )
        val vm = ReaderViewModel(repo, T2S(), prefs, "1", 1, 101L)
        idle()
        vm.setReaderFont(Prefs.READER_FONT_SERIF)
        vm.setLineScale(99f)
        vm.setParaScale(-1f)
        vm.setPaperEnabled(true)
        assertEquals(Prefs.READER_FONT_SERIF, vm.readerFont.value)
        assertEquals(Prefs.LINE_MAX, vm.lineScale.value, 0.001f)
        assertEquals(Prefs.PARA_MIN, vm.paraScale.value, 0.001f)
        assertEquals(true, vm.paperEnabled.value)
        idle()
        assertEquals(Prefs.READER_FONT_SERIF, prefs.readerFont.first())
        // Unknown font normalizes back to sans.
        vm.setReaderFont("kaiti")
        assertEquals(Prefs.READER_FONT_SANS, vm.readerFont.value)
    }
}
