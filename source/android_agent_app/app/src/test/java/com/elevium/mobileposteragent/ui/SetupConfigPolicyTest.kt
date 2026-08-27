package com.elevium.mobileposteragent.ui

import com.elevium.mobileposteragent.model.AgentConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class SetupConfigPolicyTest {
    @Test
    fun blankReplacementPreservesSavedTokenWithoutRenderingIt() {
        val saved = "existing-secret-token"

        assertEquals(saved, SetupConfigPolicy.resolveRunnerToken("   ", saved))
        assertFalse(SetupConfigPolicy.SAVED_TOKEN_PLACEHOLDER.contains(saved))
    }

    @Test
    fun explicitReplacementWinsAndIsTrimmed() {
        assertEquals(
            "replacement-token",
            SetupConfigPolicy.resolveRunnerToken("  replacement-token  ", "saved-token"),
        )
    }

    @Test
    fun boardNameIsExactTrimmedOrEmpty() {
        assertEquals("Farm", SetupConfigPolicy.normalizeBoardName("  Farm  "))
        assertNull(SetupConfigPolicy.normalizeBoardName("   "))
        assertNull(SetupConfigPolicy.normalizeBoardName(null))
    }

    @Test
    fun configStringNeverContainsRunnerCredential() {
        val secret = "runner-secret"
        val config = AgentConfig("https://hub.example", secret, "device", "account", "Farm")

        assertFalse(config.toString().contains(secret))
        assertFalse(config.toString().contains("runnerToken=$secret"))
    }
}
