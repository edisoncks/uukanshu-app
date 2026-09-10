package cc.uukanshu

import cc.uukanshu.data.convert.T2S
import cc.uukanshu.data.prefs.Prefs
import cc.uukanshu.ui.reader.ReaderErrors
import cc.uukanshu.ui.reader.ReaderErrorKind
import cc.uukanshu.ui.reader.ReaderParagraphs
import cc.uukanshu.ui.reader.ReaderViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ReaderParagraphsTest {
    @Test fun emptyStaysEmpty() {
        assertEquals(emptyList<String>(), ReaderParagraphs.split(""))
        assertEquals(emptyList<String>(), ReaderParagraphs.split("  \n\n  "))
    }

    @Test fun singleParagraph() {
        assertEquals(listOf("夜色漸深。"), ReaderParagraphs.split("夜色漸深。"))
    }

    @Test fun splitsOnDoubleNewlineTrimsDropsEmpty() {
        val text = "第一段。\n\n  \n\n第二段。\n\n第三段。"
        assertEquals(listOf("第一段。", "第二段。", "第三段。"), ReaderParagraphs.split(text))
    }

    @Test fun roundTripsChapterParserShape() {
        // ChapterParser joins with "\n\n" — split must invert it.
        val paragraphs = listOf("陳平安緊了緊衣領。", "「既然來了，便不回頭。」", "山門前只剩一盞孤燈。")
        assertEquals(paragraphs, ReaderParagraphs.split(paragraphs.joinToString("\n\n")))
    }
}

class ReaderErrorsTest {
    @Test fun minusOneIsDeletedElseOutOfRange() {
        assertEquals(ReaderErrorKind.Deleted, ReaderErrors.boundsKind(-1))
        assertEquals(ReaderErrorKind.OutOfRange, ReaderErrors.boundsKind(0))
        assertEquals(ReaderErrorKind.OutOfRange, ReaderErrors.boundsKind(99))
    }

    @Test fun deletedMessageIsTraditionalSource() {
        // Must flow through Display.text like every error (see RoutesDisplayTest).
        val msg = ReaderErrors.deletedMessage()
        assertEquals("該章節已刪除", msg)
        val t2s = T2S()
        assertEquals(t2s.convert(msg), cc.uukanshu.core.Display.text(t2s, msg, true))
    }
}

class ReaderChromeTest {
    @get:Rule val main = MainDispatcherRule()

    private fun idle() {
        main.dispatcher.scheduler.advanceUntilIdle()
    }

    @Test fun prevAtFirstReturnsFalse() = runTest {
        val repo = MutableFakeRepo(
            fresh = testDetail(101L, 102L),
            chaptersText = mutableMapOf(101L to "t1", 102L to "t2"),
        )
        val vm = ReaderViewModel(repo, T2S(), MutableFakePrefs(), "1", 1, 101L)
        idle()
        assertFalse(vm.prev())
        assertEquals(1, (vm.ui.value as ReaderViewModel.Ui.Content).position)
    }

    @Test fun nextAtEndReturnsAtEnd() = runTest {
        val repo = MutableFakeRepo(
            fresh = testDetail(101L, 102L),
            chaptersText = mutableMapOf(101L to "t1", 102L to "t2"),
        )
        val vm = ReaderViewModel(repo, T2S(), MutableFakePrefs(), "1", 2, 102L)
        idle()
        assertTrue(vm.next() is ReaderViewModel.NextStep.AtEnd)
        assertEquals(2, (vm.ui.value as ReaderViewModel.Ui.Content).position)
    }

    @Test fun nextMidStartsLoad() = runTest {
        val repo = MutableFakeRepo(
            fresh = testDetail(101L, 102L),
            chaptersText = mutableMapOf(101L to "t1", 102L to "t2"),
        )
        val vm = ReaderViewModel(repo, T2S(), MutableFakePrefs(), "1", 1, 101L)
        idle()
        val step = vm.next()
        assertTrue(step is ReaderViewModel.NextStep.Started)
        assertEquals(2, (step as ReaderViewModel.NextStep.Started).position)
        idle()
        assertEquals(2, (vm.ui.value as ReaderViewModel.Ui.Content).position)
    }

    @Test fun deletedChapterErrorOffersBackNotRetry() = runTest {
        // pageId 999 misses the TOC → effective -1 → Deleted kind.
        val repo = MutableFakeRepo(
            fresh = testDetail(101L, 102L),
            chaptersText = mutableMapOf(101L to "t1", 102L to "t2"),
        )
        val vm = ReaderViewModel(repo, T2S(), MutableFakePrefs(), "1", 1, 999L)
        idle()
        val ui = vm.ui.value
        assertTrue(ui is ReaderViewModel.Ui.Error)
        ui as ReaderViewModel.Ui.Error
        assertEquals(ReaderErrorKind.Deleted, ui.kind)
        assertEquals(-1, ui.position)
        // Next from Deleted clamps to first instead of loading 0.
        val step = vm.next()
        assertTrue(step is ReaderViewModel.NextStep.Started)
        assertEquals(1, (step as ReaderViewModel.NextStep.Started).position)
    }

    @Test fun outOfRangeIsNotDeleted() = runTest {
        val repo = MutableFakeRepo(fresh = testDetail(101L))
        val vm = ReaderViewModel(repo, T2S(), MutableFakePrefs(), "1", 99, 0L)
        idle()
        val ui = vm.ui.value as ReaderViewModel.Ui.Error
        assertEquals(ReaderErrorKind.OutOfRange, ui.kind)
    }

    @Test fun setSimplifiedIsIdempotent() = runTest {
        val prefs = MutableFakePrefs()
        val repo = MutableFakeRepo(
            fresh = testDetail(101L),
            chaptersText = mutableMapOf(101L to "t1"),
        )
        val vm = ReaderViewModel(repo, T2S(), prefs, "1", 1, 101L)
        idle()
        val writes = prefs.started.size
        vm.setSimplified(false)
        idle()
        assertEquals(writes, prefs.started.size)
        vm.setSimplified(true)
        idle()
        assertEquals(true, vm.simplified.value)
        val afterFirst = prefs.started.size
        vm.setSimplified(true)
        idle()
        assertEquals(afterFirst, prefs.started.size)
    }

    @Test fun setThemeNormalizesAndIsIdempotent() = runTest {
        val prefs = MutableFakePrefs()
        val repo = MutableFakeRepo(
            fresh = testDetail(101L),
            chaptersText = mutableMapOf(101L to "t1"),
        )
        val vm = ReaderViewModel(repo, T2S(), prefs, "1", 1, 101L)
        idle()
        vm.setTheme("dark-mode")
        assertEquals(Prefs.SYSTEM, vm.theme.value)
        vm.setTheme(Prefs.DARK)
        assertEquals(Prefs.DARK, vm.theme.value)
        vm.setTheme(Prefs.DARK)
        assertEquals(Prefs.DARK, vm.theme.value)
    }

    @Test fun bookTitleHoldsMetaForChrome() = runTest {
        val repo = MutableFakeRepo(
            fresh = testDetail(101L, title = "天魔降臨"),
            chaptersText = mutableMapOf(101L to "t1"),
        )
        val vm = ReaderViewModel(repo, T2S(), MutableFakePrefs(), "1", 1, 101L)
        idle()
        assertEquals("天魔降臨", vm.bookTitle.value)
    }

    @Test fun fontPersistsThroughFake() = runTest {
        val prefs = MutableFakePrefs()
        val repo = MutableFakeRepo(
            fresh = testDetail(101L),
            chaptersText = mutableMapOf(101L to "t1"),
        )
        val vm = ReaderViewModel(repo, T2S(), prefs, "1", 1, 101L)
        idle()
        vm.font(10f)
        assertEquals(Prefs.FONT_MAX, vm.fontScale.value)
        idle()
        assertEquals(Prefs.FONT_MAX, prefs.fontScale.first())
    }
}
