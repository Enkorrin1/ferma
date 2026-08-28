package com.elevium.mobileposteragent.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentConfigTest {
    private fun config(url: String) = AgentConfig(
        hubUrl = url,
        runnerToken = "runner-token",
        deviceLabel = "device",
        accountLabel = null,
        pinterestBoard = null,
    )

    @Test
    fun acceptsHttpsAndLocalUsbBridgeOnly() {
        assertTrue(config("https://hub.example").isValid())
        assertTrue(config("http://127.0.0.1:18082").isValid())
        assertTrue(config("http://localhost:18082").isValid())
        assertFalse(config("http://192.168.100.210:18082").isValid())
        assertFalse(config("http://hub.example").isValid())
        assertFalse(config("ftp://127.0.0.1:18082").isValid())
    }
}
