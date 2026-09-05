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
}

/** Shorthand for `catch (e: Exception) { userMessageOrThrow(e) }` sites. */
fun Exception.userMessage(): String = Errors.userMessageOrThrow(this)
