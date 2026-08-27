package com.elevium.mobileposteragent.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalCaptureRetryRunnerTest {
    @Test fun succeedsOnSecondOfThreeWithoutExtraCapture() {
        var captures = 0
        var backoffs = 0
        val result = TerminalCaptureRetryRunner.run(3, { true }, {
            captures += 1
            if (captures == 2) "png" else null
        }, { backoffs += 1 })
        assertEquals("png", result.value)
        assertEquals(2, result.attempts)
        assertEquals(2, captures)
        assertEquals(1, backoffs)
        assertTrue(result.idleObserved)
    }

    @Test fun succeedsOnThirdOfThree() {
        var captures = 0
        val result = TerminalCaptureRetryRunner.run(3, { true }, {
            captures += 1
            if (captures == 3) "png" else null
        }, {})
        assertEquals("png", result.value)
        assertEquals(3, result.attempts)
    }

    @Test fun awaitIdleFalseIsAdvisoryAndCaptureCanSucceedImmediately() {
        var captures = 0
        val result = TerminalCaptureRetryRunner.run(3, { false }, { captures += 1; "png" }, {})
        assertEquals("png", result.value)
        assertEquals(1, result.attempts)
        assertEquals(1, captures)
        assertFalse(result.idleObserved)
        assertTrue(TerminalEvidenceSequencingPolicy.isReady(true, result.value != null, true, true, true, true))
    }

    @Test fun atomicBusyNullThenSecondCaptureSucceeds() {
        var captures = 0
        val result = TerminalCaptureRetryRunner.run(3, { false }, {
            captures += 1
            if (captures == 2) "png" else null
        }, {})
        assertEquals("png", result.value)
        assertEquals(2, result.attempts)
        assertFalse(result.idleObserved)
    }

    @Test fun allAtomicBusyNullsFailClosed() {
        var captures = 0
        val result = TerminalCaptureRetryRunner.run<String>(3, { false }, { captures += 1; null }, {})
        assertNull(result.value)
        assertEquals(3, captures)
        assertFalse(TerminalEvidenceSequencingPolicy.isReady(true, result.value != null, true, true, true, true))
    }

    @Test fun advisoryIdleFalseDoesNotSuppressVerifiedUiCaptureFlow() {
        val advisoryIdle = false
        val preUiVerified = true
        var attempts = 0
        assertTrue(TerminalEvidenceFlowPolicy.shouldAttemptCapture(true, false, preUiVerified))
        val result = TerminalCaptureRetryRunner.run(3, { advisoryIdle }, { attempts += 1; "evidence" }, {})
        val ready = TerminalEvidenceSequencingPolicy.isReady(true, result.value != null, preUiVerified, true, true, true)
        val diagnostic = TerminalEvidenceDiagnostics(
            true, 10, true, true, true, false, result.idleObserved, preUiVerified,
            result.attempts, result.value != null, true, true, true, true, ready,
        ).redactedMessage()
        assertEquals(1, attempts)
        assertTrue(ready)
        assertTrue(diagnostic.contains("awaitIdle=false"))
        assertTrue(diagnostic.contains("preUiVerified=true"))
    }

    @Test fun falseUiSuppressesCaptureAndFailsClosed() {
        assertFalse(TerminalEvidenceFlowPolicy.shouldAttemptCapture(true, false, false))
    }

    @Test fun diagnosticContainsOnlyNamedState() {
        val message = TerminalEvidenceDiagnostics(
            true, 2, true, true, true, false, true, true, 2, true,
            true, true, true, true, true,
        ).redactedMessage()
        assertTrue(message.startsWith("terminal-evidence-diagnostic "))
        assertTrue(message.contains("captureAttempts=2"))
        assertFalse(message.contains("/storage/"))
        assertFalse(message.contains("token"))
    }
}
