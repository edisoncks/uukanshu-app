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
    /** Human-readable one-liner for dialogs/snackbars. Never call with cancellation. */
    fun message(e: Throwable): String = "${e.javaClass.simpleName}: ${e.message}"

    /** Rethrow cancellation, return the message otherwise. Use in every `catch (e: Exception)`. */
    fun messageOrThrow(e: Exception): String {
        if (e is CancellationException) throw e
        return message(e)
    }
}

/** Shorthand for `catch (e: Exception) { messageOrThrow(e) }` sites. */
fun Exception.userMessage(): String = Errors.messageOrThrow(this)
