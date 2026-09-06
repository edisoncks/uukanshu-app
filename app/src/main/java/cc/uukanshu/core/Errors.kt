package cc.uukanshu.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Typed failures with a stable mapping in [Errors.friendly].
 * Throw these instead of `IOException("some message")` so the UI mapping keys
 * on types, never on message substrings (messages stay identical to the old
 * strings, so logs keep working).
 */

/** uukanshu.cc served a Cloudflare challenge/block page (see SCRAPING.md). */
class CloudflareBlockedException :
    java.io.IOException("blocked by Cloudflare — try again later or from a different network")

/** Fresh TOC came back empty: block page / layout change, not an empty book. */
class EmptyChapterListException :
    java.io.IOException("empty chapter list — try again later")

/**
 * Fresh TOC shrank vs cache: truncated parse, not a real deletion.
 * Thrown by `BookRepo.detail` before touching the DB; mapped to
 * `TocRevalidator.Revalidate.RejectedShrink` without message sniffing.
 */
class TocShrunkException(val cached: Int, val fresh: Int) :
    java.io.IOException("chapter list shrank ($fresh < $cached) — refusing to wipe cache")

/** The book row vanished mid-download; partial writes are no-ops. */
class BookDeletedDuringDownloadException :
    java.io.IOException("book was deleted during download")

/** HTTP failure with a machine-readable status code (see [Errors.friendly]). */
class HttpStatusException(val code: Int, label: String) :
    java.io.IOException("HTTP $code for $label")

/** Single error-formatting policy: [friendly] for UI text, helpers below preserve cancellation. */
object Errors {
    private val urlRegex = Regex("https?://\\S+")
    private val httpCodeRegex = Regex("HTTP\\s+(\\d{3})")

    private fun rawMessage(e: Throwable): String =
        e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName

    private fun httpMessage(code: Int): String = when {
        code == 404 -> "找不到內容（404），可能已被刪除"
        code == 408 -> "網路逾時，請重試"
        code == 429 -> "請求太頻繁，請稍後再試"
        code in 500..599 -> "伺服器忙碌，請稍後再試"
        code in 400..499 -> "請求失敗（$code），請重試"
        else -> "網路錯誤（$code），請重試"
    }

    /**
     * UI-facing message: our own failures map by type; only foreign messages
     * (OkHttp internals, DownloadManager reason strings) fall back to
     * substring/regex matching. URLs are stripped so dialogs never show
     * `https://uukanshu.cc/...` internals. Callers rethrow CancellationException
     * themselves (`if (e is CancellationException) throw e`) before calling here.
     */
    fun friendly(e: Throwable): String {
        // Typed first: never key on our own message text.
        when (e) {
            is CloudflareBlockedException -> return "暫時被網站阻擋，請稍後再試或切換網路"
            is TocShrunkException, is EmptyChapterListException -> return "章節列表為空，請稍後再試"
            is BookDeletedDuringDownloadException -> return "下載中書籍已被刪除"
            is HttpStatusException -> return httpMessage(e.code)
            is UnknownHostException, is SocketTimeoutException, is ConnectException,
            is TimeoutCancellationException -> return "網路連線失敗，請檢查網路後重試"
        }
        // Legacy fallback for foreign/wrapped messages only: OkHttp wraps
        // DNS/connect failures in plain IOExceptions, and DownloadManager
        // reports plain reason strings via friendlyText.
        val raw = rawMessage(e)
        val lower = raw.lowercase()
        when {
            "download failed" in lower -> return "下載失敗，請重試"
            "download not found" in lower -> return "下載任務已失效，請重新下載"
            "out of range" in lower -> return "章節超出範圍"
            "unable to resolve host" in lower ||
                "failed to connect" in lower ||
                "network is unreachable" in lower ||
                "software caused connection abort" in lower ||
                "timed out" in lower || "timeout" in lower ->
                return "網路連線失敗，請檢查網路後重試"
        }
        httpCodeRegex.find(raw)?.let {
            val code = it.groupValues[1].toIntOrNull() ?: 0
            return httpMessage(code)
        }
        // Fallback: strip URLs and redundant fetch prefixes.
        val cleaned = urlRegex.replace(raw, "").replace(Regex("\\s{2,}"), " ")
            .replace("failed to fetch :", "讀取失敗：")
            .replace("failed to fetch", "讀取失敗")
            .trim(' ', ':', '—', '-', '，', '。')
            .trim()
        return cleaned.ifBlank { e.javaClass.simpleName }
    }

    /**
     * String overload for non-exception failure reasons (e.g.
     * `DownloadStatus.Failed.reason` from DownloadManager). Applies the
     * same Chinese mapping + URL stripping as [friendly].
     */
    fun friendlyText(raw: String): String =
        friendly(RuntimeException(raw))

    /** Like `runCatching` but rethrows cancellation instead of capturing it. */
    suspend fun <T> runCatchingExceptCancel(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    /** Returns null on ordinary failure, rethrows cancellation. */
    suspend fun <T> suppressExceptCancel(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
}
