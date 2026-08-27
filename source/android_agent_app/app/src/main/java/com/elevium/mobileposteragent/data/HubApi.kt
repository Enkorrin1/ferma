package com.elevium.mobileposteragent.data

import android.os.Build
import android.util.Base64
import com.elevium.mobileposteragent.BuildConfig
import com.elevium.mobileposteragent.model.AgentConfig
import com.elevium.mobileposteragent.model.PublishJob
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class HubApi(
    private val config: AgentConfig,
    private val deviceId: String,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    fun registerDevice(automationState: String? = null) {
        val versionLabel = "android-agent/${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
        val notes = buildString {
            append("$versionLabel; model=${Build.MANUFACTURER} ${Build.MODEL}; sdk=${Build.VERSION.SDK_INT}")
            if (!automationState.isNullOrBlank()) {
                append("; automation=")
                append(automationState)
            }
        }
        val payload = JSONObject()
            .put("device_id", deviceId)
            .put("platform", "android")
            .put("mode", "remote_agent")
            .put("label", config.deviceLabel)
            .put("account_label", config.accountLabel)
            .put("notes", notes)
            .put("tags", JSONArray().put("android-agent").put("apk"))

        executeJsonPost("${config.hubUrl}/devices/register", payload)
    }

    fun claimNext(): PublishJob? {
        val request = requestBuilder("${config.hubUrl}/devices/$deviceId/claim-next")
            .post(ByteArray(0).toRequestBody(null))
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("Claim failed: ${response.code}")
            }
            return HubContract.parseClaimResponse(response.body?.string().orEmpty())
        }
    }

    fun enqueueJob(
        target: String,
        mediaUrl: String,
        caption: String,
        board: String? = null,
        platformAccountLabel: String? = null,
    ): String {
        val payload = JSONObject()
            .put("target", target)
            .put("media_url", mediaUrl.trim())
            .put("caption", caption)
            .put("board", board)
            .put("platform_account_label", platformAccountLabel)
        val keyMaterial = "$deviceId|$target|${mediaUrl.trim()}|$caption|${board.orEmpty()}"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(keyMaterial.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val idempotencyKey = "android-ui-$digest"
        return executeJsonPost(
            "${config.hubUrl}/devices/$deviceId/jobs",
            payload,
            extraHeaders = mapOf("Idempotency-Key" to idempotencyKey),
        )?.optString("job_id").orEmpty()
    }

    internal fun heartbeat(job: PublishJob): HeartbeatResponse {
        return try {
            HubContract.parseHeartbeatResponse(
                executeJsonPost("${config.hubUrl}/jobs/${job.jobId}/heartbeat", JSONObject(), job.leaseToken),
            )
        } catch (error: HubHttpException) {
            if (error.statusCode == 403 || error.statusCode == 409) {
                throw LeaseLostException("Lease rejected for job ${job.jobId}", error)
            }
            throw error
        }
    }

    internal fun updateJobStatus(job: PublishJob, report: JobStatusReport): HubStatusResponse {
        val response = withLeaseGuard(job) {
            executeJsonPost(
                "${config.hubUrl}/jobs/${job.jobId}/status",
                HubContract.statusPayload(report),
                job.leaseToken,
            )
        }
        return HubContract.parseStatusResponse(response)
    }

    fun addEvent(job: PublishJob, level: String = "info", message: String, screenshotPath: String? = null) {
        val payload = JSONObject()
            .put("level", level)
            .put("message", message)
            .put("payload", JSONObject().put("attempt", job.attemptNumber))
            .put("event_key", "android:${job.attemptNumber}:${message.hashCode()}:${screenshotPath.orEmpty().hashCode()}")
        if (screenshotPath != null) {
            payload.put("screenshot_path", screenshotPath)
        }
        try {
            executeJsonPost("${config.hubUrl}/jobs/${job.jobId}/events", payload, job.leaseToken)
        } catch (error: HubHttpException) {
            if (error.statusCode == 403) throw LeaseLostException("Lease ownership rejected for ${job.jobId}", error)
            throw error
        }
    }

    internal fun uploadScreenshot(job: PublishJob, file: File): EvidenceUploadResult {
        val bytes = file.readBytes()
        val payload = JSONObject()
            .put("filename", file.name)
            .put("content_base64", Base64.encodeToString(bytes, Base64.NO_WRAP))
            .put("content_type", "image/png")
        val response = withLeaseGuard(job) {
            executeJsonPost(
                "${config.hubUrl}/jobs/${job.jobId}/screenshots",
                payload,
                job.leaseToken,
            )
        }
        return HubContract.parseEvidenceUploadResponse(response, job.attemptNumber).also { result ->
            require(result.isAvailable) { "Hub evidence ${result.evidenceId} is not available" }
            require(result.attemptNumber == job.attemptNumber) { "Evidence attempt does not match active job attempt" }
        }
    }

    private fun executeJsonPost(
        url: String,
        payload: JSONObject,
        leaseToken: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
    ): JSONObject? {
        val builder = requestBuilder(url, leaseToken)
        extraHeaders.forEach(builder::header)
        val request = builder
            .post(payload.toString().toRequestBody(jsonMediaType))
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val detail = runCatching { JSONObject(body).optString("detail") }.getOrNull()
                throw HubHttpException(response.code, url.substringAfter(config.hubUrl), detail)
            }
            if (body.isBlank()) return null
            return JSONObject(body)
        }
    }

    private fun requestBuilder(url: String, leaseToken: String? = null): Request.Builder {
        require(url.startsWith("https://", ignoreCase = true)) { "Hub URL must use HTTPS" }
        val builder = Request.Builder()
            .url(url)
            .header("X-Hub-Token", config.runnerToken)
            .header("X-Device-Id", deviceId)
            .header("X-Agent-Version", "android-agent/${BuildConfig.VERSION_NAME}")
            .header("X-Device-Model", "${Build.MANUFACTURER} ${Build.MODEL}")
        if (leaseToken != null) {
            HubContract.leaseHeaders(leaseToken).forEach(builder::header)
        }
        return builder
    }

    private inline fun <T> withLeaseGuard(job: PublishJob, block: () -> T): T {
        try {
            return block()
        } catch (error: HubHttpException) {
            if (error.statusCode == 403 || error.statusCode == 409) {
                throw LeaseLostException("Lease rejected for job ${job.jobId}", error)
            }
            throw error
        }
    }
}
