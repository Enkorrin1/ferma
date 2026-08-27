package com.elevium.mobileposteragent.data

import com.elevium.mobileposteragent.model.PublishJob
import org.json.JSONObject

internal data class JobStatusReport(
    val status: String,
    val message: String? = null,
    val retryable: Boolean = true,
    val errorCode: String? = null,
    val publicationVerified: Boolean = false,
    val publicationId: String? = null,
    val uiStateVerified: Boolean = false,
    val evidenceId: String? = null,
) {
    val expectedPersistedStatus: String
        get() = if (status == "ready_to_publish" && !uiStateVerified) "needs_review" else status

    init {
        require(status in VALID_STATUSES) { "Unsupported job status: $status" }
        require(status != "succeeded" || publicationVerified) {
            "succeeded requires positive publication proof"
        }
        require(status != "ready_to_publish" || !publicationVerified) {
            "ready_to_publish must not carry publication proof"
        }
        require(status != "ready_to_publish" || !uiStateVerified || !evidenceId.isNullOrBlank()) {
            "verified ready_to_publish requires evidence_id"
        }
    }

    companion object {
        private val VALID_STATUSES = setOf("running", "succeeded", "failed", "ready_to_publish")

        fun running(message: String) = JobStatusReport(status = "running", message = message)

        fun succeeded(message: String, publicationId: String? = null) = JobStatusReport(
            status = "succeeded",
            message = message,
            publicationVerified = true,
            publicationId = publicationId,
        )

        fun readyToPublish(message: String, evidenceId: String) = JobStatusReport(
            status = "ready_to_publish",
            message = message,
            retryable = false,
            uiStateVerified = true,
            evidenceId = evidenceId,
        )

        // The current Hub wire contract accepts this as a ready_to_publish request
        // and atomically persists needs_review because ui_state_verified=false.
        fun needsReview(message: String, evidenceId: String? = null) = JobStatusReport(
            status = "ready_to_publish",
            message = message,
            retryable = false,
            uiStateVerified = false,
            evidenceId = evidenceId,
        )

        fun failed(message: String, retryable: Boolean, errorCode: String) = JobStatusReport(
            status = "failed",
            message = message,
            retryable = retryable,
            errorCode = errorCode,
        )
    }
}

internal data class HubStatusResponse(
    val status: String,
    val idempotentReplay: Boolean,
    val ignoredReport: Boolean,
    val requestedStatus: String? = null,
    val reviewReason: String? = null,
    val evidenceId: String? = null,
)

internal data class EvidenceUploadResult(
    val evidenceId: String,
    val status: String,
    val screenshotPath: String,
    val idempotentReplay: Boolean,
    val attemptNumber: Int,
) {
    val isAvailable: Boolean get() = status == "available"
}

internal data class HeartbeatResponse(
    val status: String,
    val leaseExpiresAt: String,
)

internal object HubContract {
    const val LEASE_HEADER = "X-Lease-Token"

    fun parseClaimResponse(body: String): PublishJob? {
        val envelope = JSONObject(body)
        if (envelope.isNull("job")) return null
        val job = envelope.getJSONObject("job")
        return PublishJob(
            jobId = job.getString("job_id"),
            target = job.getString("target"),
            caption = job.optString("caption"),
            title = job.optNullableString("title"),
            description = job.optNullableString("description"),
            link = job.optNullableString("link"),
            board = job.optNullableString("board"),
            mediaPath = job.optNullableString("media_path"),
            mediaUrl = job.optNullableString("media_url"),
            leaseToken = job.getString("lease_token").also {
                require(it.length >= 16) { "Claim response contains an invalid lease token" }
            },
            leaseExpiresAt = job.getString("lease_expires_at"),
            attemptNumber = job.getInt("attempt_number").also {
                require(it > 0) { "Claim response contains an invalid attempt number" }
            },
            accountLabel = job.optNullableString("account_label"),
            platformAccountLabel = job.optNullableString("platform_account_label"),
        )
    }

    fun leaseHeaders(job: PublishJob): Map<String, String> = leaseHeaders(job.leaseToken)

    fun leaseHeaders(leaseToken: String): Map<String, String> =
        mapOf(LEASE_HEADER to leaseToken)

    fun statusPayload(report: JobStatusReport): JSONObject = JSONObject()
        .put("status", report.status)
        .put("message", report.message?.take(MAX_STATUS_MESSAGE_LENGTH) ?: JSONObject.NULL)
        .put("retryable", report.retryable)
        .put("error_code", report.errorCode?.take(MAX_ERROR_CODE_LENGTH) ?: JSONObject.NULL)
        .put("publication_verified", report.publicationVerified)
        .put("publication_id", report.publicationId?.take(MAX_PUBLICATION_ID_LENGTH) ?: JSONObject.NULL)
        .put("ui_state_verified", report.uiStateVerified)
        .put("evidence_id", report.evidenceId ?: JSONObject.NULL)

    fun parseStatusResponse(body: JSONObject?): HubStatusResponse {
        require(body != null) { "Hub status response is empty" }
        return HubStatusResponse(
            status = body.getString("status"),
            idempotentReplay = body.optBoolean("idempotent_replay", false),
            ignoredReport = body.optBoolean("ignored_report", false),
            requestedStatus = body.optNullableString("requested_status"),
            reviewReason = body.optNullableString("review_reason"),
            evidenceId = body.optNullableString("evidence_id"),
        )
    }

    fun parseEvidenceUploadResponse(body: JSONObject?, attemptNumber: Int): EvidenceUploadResult {
        require(body != null) { "Hub screenshot response is empty" }
        return EvidenceUploadResult(
            evidenceId = body.getString("evidence_id").also { require(it.isNotBlank()) },
            status = body.getString("status"),
            screenshotPath = body.getString("screenshot_path"),
            idempotentReplay = body.optBoolean("idempotent_replay", false),
            attemptNumber = attemptNumber,
        )
    }

    fun parseHeartbeatResponse(body: JSONObject?): HeartbeatResponse {
        require(body != null) { "Hub heartbeat response is empty" }
        return HeartbeatResponse(
            status = body.getString("status"),
            leaseExpiresAt = body.getString("lease_expires_at"),
        )
    }

    fun publicationReport(
        target: String,
        positivePublicationProof: Boolean,
        message: String,
        publicationId: String? = null,
    ): JobStatusReport {
        if (target in DRY_RUN_TARGETS) {
            return JobStatusReport.needsReview("Dry-run completion requires verified current-attempt evidence")
        }
        require(positivePublicationProof) { "A real success requires positive publication proof" }
        return JobStatusReport.succeeded(message, publicationId)
    }

    private fun JSONObject.optNullableString(name: String): String? {
        if (isNull(name)) return null
        return optString(name).ifBlank { null }
    }

    private const val MAX_STATUS_MESSAGE_LENGTH = 2_000
    private const val MAX_ERROR_CODE_LENGTH = 120
    private const val MAX_PUBLICATION_ID_LENGTH = 500
    private val DRY_RUN_TARGETS = setOf(
        "pinterest_dry_run",
        "instagram_reel_dry_run",
        "tiktok_post_dry_run",
    )
}

internal open class HubHttpException(
    val statusCode: Int,
    val endpoint: String,
    detail: String?,
) : IllegalStateException("Hub HTTP $statusCode on $endpoint${detail?.let { ": $it" }.orEmpty()}") {
    val retryable: Boolean
        get() = statusCode == 408 || statusCode == 429 || statusCode >= 500
}

internal class LeaseLostException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)
