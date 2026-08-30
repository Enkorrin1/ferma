package com.elevium.mobileposteragent.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbFarmControlPolicyTest {
    @Test
    fun acceptsOnlyCanonicalLoopbackHub() {
        assertTrue(UsbFarmControlPolicy.isAllowedHubUrl("http://127.0.0.1:18082"))
        assertTrue(UsbFarmControlPolicy.isAllowedHubUrl(" http://127.0.0.1:18082 "))
        assertFalse(UsbFarmControlPolicy.isAllowedHubUrl("http://localhost:18082"))
        assertFalse(UsbFarmControlPolicy.isAllowedHubUrl("https://example.com"))
        assertFalse(UsbFarmControlPolicy.isAllowedHubUrl("http://127.0.0.1:18083"))
        assertFalse(UsbFarmControlPolicy.isAllowedHubUrl(""))
    }

    @Test
    fun pairingCodeIsShortExactAndUnambiguous() {
        assertTrue(UsbFarmControlPolicy.isAllowedPairingCode("ABCD2345"))
        assertTrue(UsbFarmControlPolicy.isAllowedPairingCode("ABCDEFGHJKLMNPQR"))
        assertFalse(UsbFarmControlPolicy.isAllowedPairingCode("abcd2345"))
        assertFalse(UsbFarmControlPolicy.isAllowedPairingCode("ABCD0123"))
        assertFalse(UsbFarmControlPolicy.isAllowedPairingCode("ABCD-2345"))
        assertFalse(UsbFarmControlPolicy.isAllowedPairingCode("SHORT"))
    }
}
