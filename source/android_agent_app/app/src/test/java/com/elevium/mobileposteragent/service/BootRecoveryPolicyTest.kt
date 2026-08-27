package com.elevium.mobileposteragent.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BootRecoveryPolicyTest {
    @Test fun startsOnlyAfterExplicitEnableUnlockAndValidConfig() {
        assertTrue(BootRecoveryPolicy.shouldStart(true, true, true))
        assertFalse(BootRecoveryPolicy.shouldStart(false, true, true))
        assertFalse(BootRecoveryPolicy.shouldStart(true, false, true))
        assertFalse(BootRecoveryPolicy.shouldStart(true, true, false))
    }

    @Test fun terminalHubStateIsNotPartOfBootDecision() {
        // Recovery starts only the polling service; Hub claim eligibility remains authoritative.
        assertTrue(BootRecoveryPolicy.shouldStart(true, true, true))
    }
}
