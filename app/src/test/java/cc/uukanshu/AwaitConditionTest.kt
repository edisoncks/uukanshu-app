package cc.uukanshu

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract of the shared poll helper: a condition that holds returns `true`, a
 * condition that never holds returns `false` after spending its budget, and the
 * final check after the deadline still runs (callers rely on the side effects of
 * their predicate).
 *
 * `runBlocking`, not `runTest`: the deadline is wall-clock (see the KDoc), so
 * virtual time would spin instead of waiting.
 */
class AwaitConditionTest {
    @Test fun returnsTrueAsSoonAsTheConditionHolds() = runBlocking {
        var polls = 0
        val held = awaitCondition(budgetMs = 1_000) { ++polls >= 2 }
        assertTrue("a condition reached inside the budget must report true", held)
        assertTrue("must have polled until it held, polled $polls", polls >= 2)
    }

    @Test fun returnsFalseAfterSpendingTheBudget() = runBlocking {
        val started = System.nanoTime()
        val held = awaitCondition(budgetMs = 50) { false }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertFalse("a condition that never holds must report false", held)
        assertTrue("must actually wait, waited ${elapsedMs}ms", elapsedMs >= 40)
        assertTrue("must not overshoot the budget by much, waited ${elapsedMs}ms", elapsedMs < 2_000)
    }

    @Test fun conditionIsEvaluatedAgainAfterTheBudget() = runBlocking {
        // Callers such as the forgetAll drain do work inside the predicate; the
        // final check after the deadline must still reach it.
        var polls = 0
        val held = awaitCondition(budgetMs = 20) {
            polls++
            false
        }
        assertFalse(held)
        assertTrue("the check after the deadline must still run, polled $polls", polls >= 2)
    }
}
