package com.elevium.mobileposteragent.service

import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StepCaptureSerializationTest {
    @Test fun tenConcurrentCallbacksAreSerializedAndTerminalCanProceed() = runBlocking {
        val owner = Job()
        val mutex = Mutex()
        val active = AtomicInteger(0)
        val maxActive = AtomicInteger(0)
        val leaseLost = AtomicBoolean(false)
        val outcomes = List(10) { AtomicReference(StepCaptureOutcome.PENDING) }
        val jobs = outcomes.mapIndexed { index, outcome ->
            CoroutineScope(Dispatchers.Default + SupervisorJob(owner)).launch {
                StepCaptureCoroutineRunner.run(outcome, leaseLost, owner) {
                    mutex.withLock {
                        val now = active.incrementAndGet()
                        maxActive.updateAndGet { maxOf(it, now) }
                        delay(5)
                        active.decrementAndGet()
                        if (index % 3 == 0) throw IOException("no image")
                    }
                }
            }
        }
        val joined = withTimeoutOrNull(StepCaptureJoinBudget.milliseconds(jobs.size)) {
            jobs.joinAll(); true
        } == true
        assertTrue(joined)
        assertEquals(1, maxActive.get())
        assertFalse(mutex.isLocked)
        assertTrue(jobs.all(Job::isCompleted))
        assertTrue(outcomes.all { it.get() == StepCaptureOutcome.SUCCESS || it.get() == StepCaptureOutcome.BEST_EFFORT_FAILED })
        assertTrue(StepCaptureCompletionPolicy.canProceed(true, true, owner.isActive, outcomes.map { it.get() }))
        owner.cancel()
    }

    @Test fun hungMutexOwnerTimesOutAndBlocksTerminal() = runBlocking {
        val owner = Job()
        val mutex = Mutex(locked = true)
        val outcome = AtomicReference(StepCaptureOutcome.PENDING)
        val child = CoroutineScope(Dispatchers.Default + SupervisorJob(owner)).launch {
            StepCaptureCoroutineRunner.run(outcome, AtomicBoolean(false), owner) { mutex.withLock {} }
        }
        val joined = withTimeoutOrNull(100) { child.join(); true } == true
        child.cancelAndJoin()
        assertFalse(joined)
        assertEquals(StepCaptureOutcome.CANCELLED, outcome.get())
        assertFalse(StepCaptureCompletionPolicy.canProceed(joined, child.isCompleted, owner.isActive, listOf(outcome.get())))
        owner.cancel()
    }

    @Test fun joinBudgetIsDerivedAndCapped() {
        assertEquals(2_000L, StepCaptureJoinBudget.milliseconds(0))
        assertEquals(20_000L, StepCaptureJoinBudget.milliseconds(10))
        assertEquals(30_000L, StepCaptureJoinBudget.milliseconds(100))
    }
}
