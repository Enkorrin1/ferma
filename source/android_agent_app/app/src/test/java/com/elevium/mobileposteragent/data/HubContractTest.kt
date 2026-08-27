package com.elevium.mobileposteragent.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HubContractTest {
    @Test
    fun claimFixtureParsesLeaseAndAttemptFields() {
        val fixture = requireNotNull(javaClass.getResource("/claim-next-with-lease.json")).readText()
        val job = requireNotNull(HubContract.parseClaimResponse(fixture))

        assertEquals("lease-token-0123456789abcdef", job.leaseToken)
        assertEquals("2026-08-14T22:30:00+00:00", job.leaseExpiresAt)
        assertEquals(3, job.attemptNumber)
        assertEquals("pinterest_dry_run", job.target)
        assertEquals("main_account", job.accountLabel)
        assertNull(job.description)
    }

    @Test
    fun everyActiveRequestUsesTheLeaseHeaderName() {
        val fixture = requireNotNull(javaClass.getResource("/claim-next-with-lease.json")).readText()
        val job = requireNotNull(HubContract.parseClaimResponse(fixture))

        assertEquals(
            mapOf("X-Lease-Token" to "lease-token-0123456789abcdef"),
            HubContract.leaseHeaders(job),
        )
    }

    @Test
    fun statusPayloadContainsTheFullAuthoritativeSchema() {
        val payload = HubContract.statusPayload(
            JobStatusReport.failed("network unavailable", retryable = true, errorCode = "network_io"),
        )

        assertEquals("failed", payload.getString("status"))
        assertEquals("network unavailable", payload.getString("message"))
        assertTrue(payload.getBoolean("retryable"))
        assertEquals("network_io", payload.getString("error_code"))
        assertFalse(payload.getBoolean("publication_verified"))
        assertTrue(payload.isNull("publication_id"))
        assertFalse(payload.getBoolean("ui_state_verified"))
        assertTrue(payload.isNull("evidence_id"))
    }

    @Test
    fun dryRunCanNeverMapToSucceeded() {
        val report = HubContract.publicationReport(
            target = "pinterest_dry_run",
            positivePublicationProof = true,
            message = "must not become success",
        )

        assertEquals("ready_to_publish", report.status)
        assertFalse(report.publicationVerified)
        assertNull(report.publicationId)
        assertFalse(report.uiStateVerified)
        assertEquals("needs_review", report.expectedPersistedStatus)
    }

    @Test
    fun everySocialDryRunIsAlsoProtectedFromSucceeded() {
        listOf("instagram_reel_dry_run", "tiktok_post_dry_run").forEach { target ->
            val report = HubContract.publicationReport(
                target = target,
                positivePublicationProof = true,
                message = "must not become success",
            )
            assertEquals("ready_to_publish", report.status)
            assertFalse(report.publicationVerified)
            assertEquals("needs_review", report.expectedPersistedStatus)
        }
    }

    @Test
    fun wirePayloadTruncatesBoundedHubFieldsInsteadOfLeavingLeaseRunning() {
        val payload = HubContract.statusPayload(
            JobStatusReport.failed(
                message = "m".repeat(2_500),
                retryable = false,
                errorCode = "e".repeat(200),
            ),
        )
        assertEquals(2_000, payload.getString("message").length)
        assertEquals(120, payload.getString("error_code").length)
    }

    @Test
    fun verifiedReadyCarriesCurrentAttemptEvidenceContract() {
        val report = JobStatusReport.readyToPublish("verified editor", "evidence-current-123")
        val payload = HubContract.statusPayload(report)

        assertEquals("ready_to_publish", payload.getString("status"))
        assertTrue(payload.getBoolean("ui_state_verified"))
        assertEquals("evidence-current-123", payload.getString("evidence_id"))
        assertEquals("ready_to_publish", report.expectedPersistedStatus)
    }

    @Test
    fun missingEvidenceMapsToHubNeedsReviewRequest() {
        val report = JobStatusReport.needsReview("capture unavailable")
        val payload = HubContract.statusPayload(report)

        assertEquals("ready_to_publish", payload.getString("status"))
        assertFalse(payload.getBoolean("ui_state_verified"))
        assertTrue(payload.isNull("evidence_id"))
        assertEquals("needs_review", report.expectedPersistedStatus)
    }

    @Test
    fun screenshotFixtureParsesAvailableEvidenceForExactAttempt() {
        val fixture = requireNotNull(javaClass.getResource("/screenshot-upload-available.json")).readText()
        val result = HubContract.parseEvidenceUploadResponse(JSONObject(fixture), attemptNumber = 5)

        assertEquals("evidence-current-123", result.evidenceId)
        assertEquals("available", result.status)
        assertEquals(5, result.attemptNumber)
        assertTrue(result.isAvailable)
    }

    @Test(expected = IllegalArgumentException::class)
    fun verifiedReadyWithoutEvidenceIsRejectedLocally() {
        JobStatusReport(status = "ready_to_publish", uiStateVerified = true)
    }

    @Test
    fun realSuccessCarriesPositivePublicationProof() {
        val report = HubContract.publicationReport(
            target = "pinterest_pin",
            positivePublicationProof = true,
            message = "verified",
            publicationId = "pin-123",
        )

        assertEquals("succeeded", report.status)
        assertTrue(report.publicationVerified)
        assertEquals("pin-123", report.publicationId)
    }

    @Test(expected = IllegalArgumentException::class)
    fun succeededWithoutProofIsRejectedLocally() {
        JobStatusReport(status = "succeeded", publicationVerified = false)
    }

    @Test
    fun terminalReplayKeepsThePersistedHubStatus() {
        val response = HubContract.parseStatusResponse(
            JSONObject(
                """{"status":"ready_to_publish","idempotent_replay":true,"ignored_report":true}""",
            ),
        )

        assertEquals("ready_to_publish", response.status)
        assertTrue(response.idempotentReplay)
        assertTrue(response.ignoredReport)
    }
}
