package cc.uukanshu.data.export

import cc.uukanshu.core.BookNotCachedException
import cc.uukanshu.core.Display
import cc.uukanshu.core.NoDownloadedChaptersException
import cc.uukanshu.data.convert.T2S
import cc.uukanshu.data.parse.BookIds
import cc.uukanshu.di.RepoApi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.UUID

/**
 * Builds one EPUB from the cached rows. This is a read-only projection:
 * it never fetches missing chapters and never writes the DB.
 *
 * The chapter list is snapshotted first, then bodies are read one at a
 * time by stable pageId. That keeps memory bounded for a several-thousand-
 * chapter book and tolerates a concurrent TOC refresh: a chapter pruned
 * between the two reads is simply skipped.
 *
 * Output is complete before it is visible: `<title>.epub.tmp` is renamed
 * only after the package is done, using an atomic replace where the
 * filesystem supports it. Files older than [STALE_MS] are pruned on each
 * export to keep the cache directory bounded.
 */
class EpubExporter(
    private val repo: RepoApi,
    private val t2s: T2S,
    private val dir: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Instant = Instant::now,
) {
    companion object {
        /** Cache subdirectory exposed by res/xml/filepaths.xml. */
        const val DIR_NAME = "epub"

        internal const val PROGRESS_STEP = 20
        internal const val STALE_MS = 60L * 60L * 1000L
    }

    /**
     * Writes and returns the final file. [onProgress] is called every
     * [PROGRESS_STEP] chapters and once at the end; it runs on the IO
     * dispatcher, so callers must publish thread-safely.
     *
     * Leaving the caller cancels the run; the temp file is deleted and no
     * partial EPUB is renamed into place.
     */
    suspend fun export(
        bookId: String,
        simplified: Boolean,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): File = withContext(ioDispatcher) {
        val info = repo.bookEntry(bookId) ?: throw BookNotCachedException()
        val refs = repo.cachedChapters(bookId)
        if (refs.isEmpty()) throw NoDownloadedChaptersException()

        val display: (String) -> String = { raw -> Display.text(t2s, raw, simplified) }
        val title = display(info.title).trim().ifBlank { "book-$bookId" }
        val meta = EpubMeta(
            identifier = identifierFor(bookId),
            title = title,
            author = display(info.author).trim(),
            intro = display(info.intro).trim(),
            category = display(info.category).trim(),
            source = BookIds.bookUrl(bookId),
            language = if (simplified) "zh-Hans" else "zh-Hant",
        )

        dir.mkdirs()
        if (!dir.isDirectory) throw IOException("cannot create export directory")
        pruneStale()

        val name = EpubPaths.fileNameFor(title, bookId)
        val tmp = File(dir, "$name.tmp")
        val final = File(dir, name)
        try {
            var written = 0
            FileOutputStream(tmp).use { out ->
                EpubWriter(out, meta, now()).use { writer ->
                    refs.forEachIndexed { index, ref ->
                        currentCoroutineContext().ensureActive()
                        val text = repo.cachedChapterContent(bookId, ref.pageId)
                        if (text != null) {
                            writer.addChapter(display(ref.title).trim(), display(text))
                            written++
                        }
                        val done = index + 1
                        if (done == refs.size || done % PROGRESS_STEP == 0) {
                            onProgress(done, refs.size)
                        }
                    }
                }
            }
            if (written == 0) throw NoDownloadedChaptersException()
            replace(tmp, final)
            final
        } catch (e: Throwable) {
            tmp.delete()
            throw e
        }
    }

    /**
     * One atomic replacement where the filesystem supports it, so a share of
     * a previous export never observes a missing target. The delete+rename
     * fallback only runs on filesystems without atomic moves.
     */
    private fun replace(tmp: File, target: File) {
        try {
            Files.move(
                tmp.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (e: AtomicMoveNotSupportedException) {
            if (!target.delete() && target.exists()) {
                throw IOException("cannot replace ${target.name}")
            }
            if (!tmp.renameTo(target)) throw IOException("cannot finish ${target.name}")
        }
    }

    /** Stable per book, so a reader can recognize a re-export as the same book. */
    private fun identifierFor(bookId: String): String =
        "urn:uuid:" + UUID.nameUUIDFromBytes("uukanshu:$bookId".toByteArray(Charsets.UTF_8))

    /** The cache is disposable: keep an hour of shares, drop crashed leftovers. */
    private fun pruneStale() {
        val cutoff = now().toEpochMilli() - STALE_MS
        dir.listFiles()?.forEach { file ->
            val isExport = file.name.endsWith(".epub") || file.name.endsWith(".tmp")
            if (isExport && file.lastModified() < cutoff) file.delete()
        }
    }
}
