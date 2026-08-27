package com.elevium.mobileposteragent.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugScreenshotCapturePolicyTest {
    @Test fun planeLayoutRejectsStrideOverflowAndTruncation() {
        assertTrue(ScreenshotPlaneLayoutPolicy.isSafe(ScreenshotPlaneLayout(720, 1280, 4, 2880, 3_686_400)))
        assertTrue(ScreenshotPlaneLayoutPolicy.isSafe(ScreenshotPlaneLayout(720, 1280, 4, 2944, 3_768_256)))
        assertFalse(ScreenshotPlaneLayoutPolicy.isSafe(ScreenshotPlaneLayout(720, 1280, 3, 2160, 2_764_800)))
        assertFalse(ScreenshotPlaneLayoutPolicy.isSafe(ScreenshotPlaneLayout(720, 1280, 4, 2879, 3_686_400)))
        assertFalse(ScreenshotPlaneLayoutPolicy.isSafe(ScreenshotPlaneLayout(720, 1280, 4, 2880, 3_686_399)))
        assertFalse(ScreenshotPlaneLayoutPolicy.isSafe(ScreenshotPlaneLayout(Int.MAX_VALUE, 2, 4, 1, Int.MAX_VALUE)))
    }

    @Test fun completionGateAllowsExactlyOneOwner() {
        val gate = ScreenshotCaptureCompletionGate()
        assertTrue(gate.claim())
        assertFalse(gate.claim())
        assertFalse(gate.claim())
    }

    @Test fun terminalEvidenceWaitsForIdleAndFailsClosedOnTimeout() {
        var busyChecks = 0
        assertTrue(ScreenshotCaptureIdleWaiter.await(1_000, { ++busyChecks < 3 }, {}))
        assertTrue(TerminalEvidenceSequencingPolicy.isReady(true, true, true, true, true, true))
        assertFalse(TerminalEvidenceSequencingPolicy.isReady(true, false, true, true, true, true))
        assertFalse(TerminalEvidenceSequencingPolicy.isReady(true, true, true, false, false, true))
        assertFalse(TerminalEvidenceSequencingPolicy.isReady(true, true, true, true, true, false))
    }

    @Test fun stepAdmissionClosesAtomicallyWithoutLateRegistration() {
        val gate = StepCaptureAdmission<String>()
        assertTrue(gate.admit { "before" } == "before")
        assertTrue(gate.closeAndSnapshot() == listOf("before"))
        assertTrue(gate.isClosed())
        assertTrue(gate.admit { "late" } == null)
        assertTrue(gate.closeAndSnapshot() == listOf("before"))
    }

    @Test fun completedBestEffortFailuresProceedButHungOrCancelledGroupBlocks() {
        assertTrue(StepCaptureCompletionPolicy.canProceed(true, true, true,
            listOf(StepCaptureOutcome.SUCCESS, StepCaptureOutcome.BEST_EFFORT_FAILED)))
        assertFalse(StepCaptureCompletionPolicy.canProceed(false, false, true, listOf(StepCaptureOutcome.PENDING)))
        assertFalse(StepCaptureCompletionPolicy.canProceed(true, false, true, listOf(StepCaptureOutcome.PENDING)))
        assertFalse(StepCaptureCompletionPolicy.canProceed(true, true, false, listOf(StepCaptureOutcome.SUCCESS)))
        assertFalse(StepCaptureCompletionPolicy.canProceed(true, true, true, listOf(StepCaptureOutcome.CANCELLED)))
        assertFalse(StepCaptureCompletionPolicy.canProceed(true, true, true, listOf(StepCaptureOutcome.LEASE_LOST)))
    }
}
