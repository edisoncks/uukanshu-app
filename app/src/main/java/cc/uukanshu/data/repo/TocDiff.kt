package cc.uukanshu.data.repo

import cc.uukanshu.data.db.ChapterEntity
import cc.uukanshu.data.db.ChapterMetaRef

/**
 * TOC refresh as a metadata diff, not a wipe+rewrite.
 *
 * The old `replaceToc` deleted every row and reinserted the skeleton,
 * dragging all chapter bodies through a `pageId → content` map just to
 * survive its own delete (tens of MB transient for long novels, thousands
 * of writes + flow churn on every refresh even when nothing changed).
 * The content column is never read or written here: inserts carry empty
 * content, metadata updates touch position/title/url only, and deletes
 * prune pageIds absent from the accepted fresh TOC. Chapter bodies are
 * written solely by the pageId-keyed single-row path. Pure + unit-tested
 * via `TocDiffTest` (no Room needed); applied by `AppDb.replaceToc`.
 */
object TocDiff {
    /** Metadata refresh for one cached row (content deliberately absent). */
    data class Update(
        val pageId: Long,
        val position: Int,
        val title: String,
        val url: String,
    )

    data class Diff(
        /** Fresh rows absent from cache (empty content). */
        val insert: List<ChapterEntity>,
        /** Cached rows whose position/title/url changed (content preserved). */
        val update: List<Update>,
        /** Cached pageIds absent from the accepted fresh TOC. */
        val deleteIds: List<Long>,
    ) {
        fun isNoop(): Boolean = insert.isEmpty() && update.isEmpty() && deleteIds.isEmpty()
    }

    fun diff(cached: List<ChapterMetaRef>, fresh: List<ChapterEntity>): Diff {
        val cachedById = cached.associateBy { it.pageId }
        val freshIds = fresh.mapTo(HashSet(fresh.size)) { it.pageId }
        // associateBy keeps last on duplicates; the parser dedups, so this
        // is unreachable in practice — and upsertAll(REPLACE) collapses it anyway.
        val insert = fresh.filter { it.pageId !in cachedById }
        val update = fresh.mapNotNull { f ->
            val c = cachedById[f.pageId]
            if (c != null && (c.position != f.position || c.title != f.title || c.url != f.url)) {
                Update(f.pageId, f.position, f.title, f.url)
            } else null
        }
        val deleteIds = cached.mapNotNull { if (it.pageId !in freshIds) it.pageId else null }
        return Diff(insert, update, deleteIds)
    }
}
