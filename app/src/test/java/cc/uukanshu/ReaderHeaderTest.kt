package cc.uukanshu

import cc.uukanshu.ui.reader.ReaderHeader
import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderHeaderTest {
    @Test fun line2JoinsProgressAndTitle() {
        assertEquals("307 / 872  第308章 邀请", ReaderHeader.line2(307, 872, "第308章 邀请"))
    }

    @Test fun emptyTitleFallsBackToProgress() {
        // Loading/Error carry no title: bar keeps height with progress alone.
        assertEquals("307 / 872", ReaderHeader.line2(307, 872, ""))
    }
}
