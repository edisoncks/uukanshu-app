package cc.uukanshu

import kotlinx.coroutines.delay

/**
 * Poll [condition] every 10ms until it holds, or [budgetMs] runs out.
 *
 * Returns the last result instead of throwing. A `withTimeout` around a poll
 * fails as `TimeoutCancellationException`, which names no invariant and hides
 * the assertion message written right after it: with a `guard` counter sized to
 * the same budget, the poll spends the whole timeout and the message never
 * prints. Callers assert on the result with a message that says what never
 * happened.
 *
 * Must run on a real-time dispatcher (`runBlocking`, `Dispatchers.Default`):
 * the deadline is wall-clock while `delay` obeys the coroutine scheduler, so
 * under `runTest`'s virtual time the loop would spin through the whole budget
 * without the clock advancing. See `AwaitConditionTest` for the contract.
 */
internal suspend fun awaitCondition(
    budgetMs: Long = 5_000,
    condition: () -> Boolean,
): Boolean {
    val deadline = System.nanoTime() + budgetMs * 1_000_000
    while (System.nanoTime() < deadline) {
        if (condition()) return true
        delay(10)
    }
    return condition()
}
