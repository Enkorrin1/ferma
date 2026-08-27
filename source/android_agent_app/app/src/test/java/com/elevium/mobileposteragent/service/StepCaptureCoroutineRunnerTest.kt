package com.elevium.mobileposteragent.service

import com.elevium.mobileposteragent.data.LeaseLostException
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StepCaptureCoroutineRunnerTest {
    @Test
    fun ordinaryFailureIsRecordedWithoutCancellingOwnerOrEscapingSupervisor() = runBlocking {
        val uncaught = AtomicInteger(0)
        val owner = Job()
        val outcome = AtomicReference(StepCaptureOutcome.PENDING)
        val child = scope(owner, uncaught).launch {
            StepCaptureCoroutineRunner.run(outcome, AtomicBoolean(false), owner) {
                throw IOException("ordinary")
            }
        }
        child.join()
        assertEquals(StepCaptureOutcome.BEST_EFFORT_FAILED, outcome.get())
        assertTrue(owner.isActive)
        assertEquals(0, uncaught.get())
        owner.cancel()
    }

    @Test
    fun leaseLossCancelsOwnerWithoutUncaughtErrorOrTerminalCallback() = runBlocking {
        val uncaught = AtomicInteger(0)
        val owner = Job()
        val terminalCallbacks = AtomicInteger(0)
        val leaseLost = AtomicBoolean(false)
        val outcome = AtomicReference(StepCaptureOutcome.PENDING)
        val child = scope(owner, uncaught).launch {
            StepCaptureCoroutineRunner.run(outcome, leaseLost, owner) {
                throw LeaseLostException("stale lease")
            }
            if (owner.isActive && !leaseLost.get()) terminalCallbacks.incrementAndGet()
        }
        child.join()
        assertEquals(StepCaptureOutcome.LEASE_LOST, outcome.get())
        assertTrue(leaseLost.get())
        assertFalse(owner.isActive)
        assertEquals(0, terminalCallbacks.get())
        assertEquals(0, uncaught.get())
    }

    @Test
    fun cooperativeCancellationIsRecordedAndNeverEscapesSupervisor() = runBlocking {
        val uncaught = AtomicInteger(0)
        val owner = Job()
        val outcome = AtomicReference(StepCaptureOutcome.PENDING)
        val entered = CompletableDeferred<Unit>()
        val child = scope(owner, uncaught).launch {
            StepCaptureCoroutineRunner.run(outcome, AtomicBoolean(false), owner) {
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        entered.await()
        child.cancelAndJoin()
        assertEquals(StepCaptureOutcome.CANCELLED, outcome.get())
        assertEquals(0, uncaught.get())
        owner.cancel()
    }

    @Test
    fun hungChildTimesOutIsCancelledAndBlocksTerminal() = runBlocking {
        val uncaught = AtomicInteger(0)
        val owner = Job()
        val outcome = AtomicReference(StepCaptureOutcome.PENDING)
        val entered = CompletableDeferred<Unit>()
        val child = scope(owner, uncaught).launch {
            StepCaptureCoroutineRunner.run(outcome, AtomicBoolean(false), owner) {
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        entered.await()
        val joined = withTimeoutOrNull(100) { child.join(); true } == true
        child.cancel(CancellationException("bounded join expired"))
        child.join()
        assertFalse(joined)
        assertEquals(StepCaptureOutcome.CANCELLED, outcome.get())
        assertFalse(
            StepCaptureCompletionPolicy.canProceed(joined, child.isCompleted, owner.isActive, listOf(outcome.get())),
        )
        assertEquals(0, uncaught.get())
        owner.cancel()
    }

    private fun scope(owner: Job, uncaught: AtomicInteger): CoroutineScope = CoroutineScope(
        Dispatchers.Default + SupervisorJob(owner) + CoroutineExceptionHandler { _, _ -> uncaught.incrementAndGet() },
    )
}
