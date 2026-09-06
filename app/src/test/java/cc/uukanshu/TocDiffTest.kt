package cc.uukanshu

import cc.uukanshu.data.db.ChapterEntity
import cc.uukanshu.data.db.ChapterMetaRef
import cc.uukanshu.data.repo.TocDiff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TOC refresh is a metadata diff: unchanged rows are untouched, retitles and
 * shifts are in-place metadata updates (bodies never rewritten), absent ids
 * are pruned. Pure JVM, no Room needed.
 */
class TocDiffTest {
    private fun meta(pageId: Long, pos: Int, title: String = "t", url: String = "u") =
        ChapterMetaRef(pageId, pos, title, url)

    private fun row(pageId: Long, pos: Int, title: String = "t", url: String = "u") =
        ChapterEntity("b", pos, pageId, title, url, content = "")

    @Test fun identicalIsNoop() {
        val d = TocDiff.diff(
            listOf(meta(101L, 1), meta(102L, 2)),
            listOf(row(101L, 1), row(102L, 2)),
        )
        assertTrue(d.isNoop())
    }

    @Test fun retitleIsMetaUpdate() {
        val d = TocDiff.diff(
            listOf(meta(101L, 1, "old")),
            listOf(row(101L, 1, "new")),
        )
        assertTrue(d.insert.isEmpty())
        assertTrue(d.deleteIds.isEmpty())
        assertEquals(listOf(TocDiff.Update(101L, 1, "new", "u")), d.update)
    }

    @Test fun positionShiftIsMetaUpdate() {
        // TOC insert at the front shifts positions: same ids, new order.
        val d = TocDiff.diff(
            listOf(meta(101L, 1), meta(102L, 2)),
            listOf(row(100L, 1), row(101L, 2), row(102L, 3)),
        )
        assertEquals(listOf(100L), d.insert.map { it.pageId })
        assertEquals(setOf(101L, 102L), d.update.map { it.pageId }.toSet())
        assertEquals(2, d.update.first { it.pageId == 101L }.position)
        assertTrue(d.deleteIds.isEmpty())
    }

    @Test fun removedPageIdsArePruned() {
        val d = TocDiff.diff(
            listOf(meta(101L, 1), meta(102L, 2)),
            listOf(row(101L, 1), row(103L, 2)),
        )
        assertEquals(listOf(102L), d.deleteIds)
        assertEquals(listOf(103L), d.insert.map { it.pageId })
        assertTrue(d.update.isEmpty())
    }
}
