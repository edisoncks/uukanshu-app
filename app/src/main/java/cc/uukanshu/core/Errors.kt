package cc.uukanshu.core

import kotlinx.coroutines.CancellationException

/**
 * Single error-formatting policy for the whole app.
 *
 * Ten call sites used to inline `"${e.javaClass.simpleName}: ${e.message}"`
 * with a hand-written `if (e is CancellationException) throw e` above it.
 * Forgetting the rethrow swallows coroutine cancellation (leaked jobs);
 * formatting inline meant divergent messages. All ViewModels and managers
 * go through here.
 */
object Errors {
    /**
     * Log-oriented one-liner (`ClassName: message`). Keep the class name
     * here for debugging — never show this to users (see [userMessage]).
     * Never call with cancellation.
     */
    fun message(e: Throwable): String = "${e.javaClass.simpleName}: ${e.message}"

    /** Rethrow cancellation, return the message otherwise. Use in every `catch (e: Exception)`. */
    fun messageOrThrow(e: Exception): String {
        if (e is CancellationException) throw e
        return message(e)
    }

    /**
     * User-facing message: the raw detail without the `ClassName:` prefix.
     * Falls back to the class name only when the throwable carries no
     * message. All dialog/snackbar/error-state text must go through here
     * (or [userMessageOrThrow]); [message] stays for logs.
     */
    fun userMessage(e: Throwable): String =
        e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName

    /** Rethrow cancellation, return the user-facing message otherwise. */
    fun userMessageOrThrow(e: Exception): String {
        if (e is CancellationException) throw e
        return userMessage(e)
    }

    private val urlRegex = Regex("https?://\\S+")
    private val httpCodeRegex = Regex("HTTP\\s+(\\d{3})")

    /**
     * UI-facing message: mapped to short Traditional Chinese for known
     * network/site cases, URLs stripped so dialogs never show
     * `https://uukanshu.cc/...` internals. Unknown errors fall back to the
     * sanitized [userMessage]. All dialog/snackbar/error-state text should
     * go through here (or [friendlyOrThrow]); [userMessage] stays as the
     * raw accessor for logs/tests.
     */
    fun friendly(e: Throwable): String {
        val raw = userMessage(e)
        val lower = raw.lowercase()
        when {
            "blocked by cloudflare" in lower -> return "暫時被網站阻擋，請稍後再試或切換網路"
            "empty chapter list" in lower -> return "章節列表為空，請稍後再試"
            "book was deleted during download" in lower -> return "下載中書籍已被刪除"
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
            return when {
                code == 404 -> "找不到內容（404），可能已被刪除"
                code == 408 -> "網路逾時，請重試"
                code == 429 -> "請求太頻繁，請稍後再試"
                code in 500..599 -> "伺服器忙碌，請稍後再試"
                code in 400..499 -> "請求失敗（$code），請重試"
                else -> "網路錯誤（$code），請重試"
            }
        }
        // Fallback: strip URLs and redundant fetch prefixes.
        val cleaned = urlRegex.replace(raw, "").replace(Regex("\\s{2,}"), " ")
            .replace("failed to fetch :", "讀取失敗：")
            .replace("failed to fetch", "讀取失敗")
            .trim(' ', ':', '—', '-', '，', '。')
            .trim()
        return cleaned.ifBlank { e.javaClass.simpleName }
    }

    /** Rethrow cancellation, return the UI-facing [friendly] message otherwise. */
    fun friendlyOrThrow(e: Exception): String {
        if (e is CancellationException) throw e
        return friendly(e)
    }

    /**
     * String overload for non-exception failure reasons (e.g.
     * `DownloadStatus.Failed.reason` from DownloadManager). Applies the
     * same Chinese mapping + URL stripping as [friendly].
     */
    fun friendlyText(raw: String): String =
        friendly(RuntimeException(raw))

    /**
     * `runCatching` for suspend blocks that must not swallow cancellation.
     * `kotlin.runCatching` catches `CancellationException` (it is an
     * `Exception`), which suppresses coroutine cancellation and turns a
     * cancelled job into a normal failure. Use this instead for any
     * suspend call (DB, network, DataStore): cancellation rethrows,
     * other failures are captured in the `Result`.
     */
    suspend fun <T> runCatchingExceptCancel(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    /**
     * Fire-and-forget variant: returns null on ordinary failure, rethrows
     * cancellation. For optional reads (stale cache paint, best-effort
     * revalidate) where `null`/skip is the failure mode.
     */
    suspend fun <T> suppressExceptCancel(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
}

/** Shorthand for `catch (e: Exception) { userMessageOrThrow(e) }` sites. */
fun Exception.userMessage(): String = Errors.userMessageOrThrow(this)

/** UI-facing shorthand for `catch (e: Exception) { friendlyOrThrow(e) }` sites. */
fun Exception.friendlyMessage(): String = Errors.friendlyOrThrow(this)
