package com.elevium.mobileposteragent.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.elevium.mobileposteragent.R
import com.elevium.mobileposteragent.data.ConfigStore
import com.elevium.mobileposteragent.data.EvidenceUploadResult
import com.elevium.mobileposteragent.data.HubContract
import com.elevium.mobileposteragent.data.HubApi
import com.elevium.mobileposteragent.data.HubHttpException
import com.elevium.mobileposteragent.data.HubStatusResponse
import com.elevium.mobileposteragent.data.JobStatusReport
import com.elevium.mobileposteragent.data.LeaseLostException
import com.elevium.mobileposteragent.model.PublishJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class AgentForegroundService : LifecycleService() {
    private val runLoopLock = Any()
    @Volatile
    private var runLoopJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
        ensureRunLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureRunLoop()
        return Service.START_STICKY
    }

    private fun ensureRunLoop() {
        synchronized(runLoopLock) {
            if (runLoopJob?.isActive == true) return
            runLoopJob = lifecycleScope.launch {
                try {
                    runLoop()
                } finally {
                    synchronized(runLoopLock) {
                        runLoopJob = null
                    }
                }
            }
        }
    }

    override fun onBind(intent: Intent): IBinder? {
        return super.onBind(intent)
    }

    private suspend fun runLoop() {
        val config = ConfigStore(this).load()?.takeIf { it.isValid() } ?: return
        val api = HubApi(config, deviceId = stableDeviceId(this))

        while (currentCoroutineContext().isActive) {
            try {
                withContext(Dispatchers.IO) {
                    val accessibility = AgentAccessibilityService.instance
                    val automationState =
                when {
                    accessibility == null -> "blocked_no_accessibility"
                    accessibility.prepareAutomationWindow() -> "ready"
                            accessibility.canAutomate() -> "ready"
                            else -> "blocked_no_active_window"
                        }
                    api.registerDevice(automationState = automationState)
                    if (automationState != "ready") {
                        return@withContext
                    }
                    val readyAccessibility = accessibility ?: return@withContext
                    val job = api.claimNext() ?: return@withContext
                    executeJob(api, readyAccessibility, job)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e(TAG, "Agent loop ${classifyError(error)}; retrying after poll interval", error)
            }
            delay(POLL_INTERVAL_MS)
        }
    }

    private suspend fun executeJob(
        api: HubApi,
        accessibility: AgentAccessibilityService,
        job: PublishJob,
    ) = coroutineScope {
        val running = api.updateJobStatus(
            job,
            JobStatusReport.running("Android agent started publish flow; attempt ${job.attemptNumber}"),
        )
        if (running.ignoredReport || running.status != "running") {
            Log.i(TAG, "Ignoring replayed job ${job.jobId}; Hub terminal status is ${running.status}")
            return@coroutineScope
        }

        val heartbeatJob = launch(Dispatchers.IO) { runHeartbeatLoop(api, job) }
        try {
            val report = executeActiveJob(api, accessibility, job)
            var terminal: HubStatusResponse? = null
            for (attempt in 1..TERMINAL_STATUS_ATTEMPTS) {
                try {
                    terminal = api.updateJobStatus(job, report)
                    break
                } catch (error: CancellationException) {
                    throw error
                } catch (error: LeaseLostException) {
                    throw error
                } catch (error: Exception) {
                    if (attempt == TERMINAL_STATUS_ATTEMPTS) throw error
                    delay(TERMINAL_STATUS_RETRY_DELAY_MS * attempt)
                }
            }
            val persisted = checkNotNull(terminal)
            if (persisted.ignoredReport || persisted.status != report.expectedPersistedStatus) {
                Log.i(TAG, "Hub ignored ${report.status} for ${job.jobId}; stored=${persisted.status}")
            }
        } finally {
            heartbeatJob.cancelAndJoin()
        }
    }

    private suspend fun runHeartbeatLoop(api: HubApi, job: PublishJob) {
        var consecutiveFailures = 0
        while (currentCoroutineContext().isActive) {
            delay(HEARTBEAT_INTERVAL_MS)
            try {
                api.heartbeat(job)
                consecutiveFailures = 0
            } catch (error: LeaseLostException) {
                throw error
            } catch (error: Exception) {
                consecutiveFailures += 1
                if (consecutiveFailures >= MAX_HEARTBEAT_FAILURES) {
                    throw LeaseLostException(
                        "Heartbeat failed $consecutiveFailures consecutive times for ${job.jobId}",
                        error,
                    )
                }
                delay(HEARTBEAT_RETRY_DELAY_MS)
            }
        }
    }

    private suspend fun executeActiveJob(
        api: HubApi,
        accessibility: AgentAccessibilityService,
        job: PublishJob,
    ): JobStatusReport = coroutineScope {
        if (!DebugScreenshotCapture.hasVerifiedPermission()) {
            api.addEvent(
                job,
                level = "warning",
                message = "Screen capture permission is unavailable; real publish may continue, while dry-run terminal evidence remains fail-closed",
            )
        }
        val importedMedia = try {
            MediaPreparer.prepare(this@AgentForegroundService, job)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val failure = classifyFailure(error, "media_prepare")
            api.addEvent(
                job,
                level = "error",
                message = failure.message ?: "Media preparation failed without a diagnostic message",
            )
            return@coroutineScope failure
        }
        if (importedMedia != null) {
            api.addEvent(job, message = "Media prepared on device")
        }

        data class AdmittedStepCapture(
            val job: Job,
            val outcome: AtomicReference<StepCaptureOutcome>,
        )
        val parentJob = currentCoroutineContext()[Job]
        val stepLeaseLost = AtomicBoolean(false)
        val stepUploads = StepCaptureAdmission<AdmittedStepCapture>()
        val stepSupervisor = SupervisorJob(currentCoroutineContext()[Job])
        val stepCaptureMutex = Mutex()
        val success =
            accessibility.publish(job, importedMedia) { stepLabel ->
                val upload = stepUploads.admit {
              val outcome = AtomicReference(StepCaptureOutcome.PENDING)
              val child = launch(Dispatchers.IO + stepSupervisor, start = CoroutineStart.LAZY) {
                    StepCaptureCoroutineRunner.run(outcome, stepLeaseLost, parentJob) {
                        stepCaptureMutex.withLock {
                            captureAndUploadStepScreenshot(api, job, stepLabel)
                        }
                  }
              }
                    AdmittedStepCapture(child, outcome)
                }
                upload?.job?.start()
            }
        val joinedStepUploads = stepUploads.closeAndSnapshot()
        val trace = accessibility.lastTraceSummary()
        if (success) {
            // A real-platform receipt is the authoritative terminal proof. Diagnostic step
            // screenshots are best effort and must never delay or strand that terminal report:
            // Android 9 capture/upload work can remain blocked even after the platform receipt
            // has been observed. Close admission, cancel only unfinished diagnostics, then report
            // the verified publication while the lease is still live.
            joinedStepUploads.map(AdmittedStepCapture::job)
                .filterNot(Job::isCompleted)
                .forEach(Job::cancel)
            stepSupervisor.cancel()
            if (trace.isNotBlank()) {
                api.addEvent(job, message = "Accessibility trace: $trace")
            }
            api.addEvent(job, message = "Accessibility publish flow finished with positive terminal proof")
            return@coroutineScope HubContract.publicationReport(
                target = job.target,
                positivePublicationProof = true,
                message = "Android agent verified external publication",
                publicationId = accessibility.lastVerifiedPublicationId(),
            )
        }
        val joinedWithinBound = withTimeoutOrNull(StepCaptureJoinBudget.milliseconds(joinedStepUploads.size)) {
            joinedStepUploads.map(AdmittedStepCapture::job).joinAll()
            true
        } == true
        if (!joinedWithinBound) joinedStepUploads.map(AdmittedStepCapture::job)
            .filterNot(Job::isCompleted).forEach(Job::cancel)
        stepSupervisor.complete()
        if (stepLeaseLost.get()) throw CancellationException("Lease lost during step evidence")
        val allStepCapturesCompleted = joinedStepUploads.all { it.job.isCompleted }
        val parentStillActive = currentCoroutineContext().isActive
        val stepCapturesJoined = StepCaptureCompletionPolicy.canProceed(
            joinedWithinBound,
            allStepCapturesCompleted,
            parentStillActive,
            joinedStepUploads.map { it.outcome.get() },
        )
        val reason = accessibility.lastErrorMessage() ?: "Accessibility publish flow failed"
        if (reason.startsWith(AgentAccessibilityService.NEEDS_REVIEW_PREFIX)) {
            val evidence = captureAndUploadFailureScreenshot(api, job)
            api.addEvent(job, level = "warning", message = reason.take(4_000), screenshotPath = evidence?.screenshotPath)
            return@coroutineScope JobStatusReport.needsReview(reason, evidence?.evidenceId)
        }
        if (reason.startsWith(AgentAccessibilityService.READY_TO_PUBLISH_PREFIX)) {
            val uiVerifiedBefore = accessibility.isCurrentDryRunUiVerified(job)
            api.addEvent(
                job,
                level = if (uiVerifiedBefore) "info" else "warning",
                message = accessibility.lastEditorVerificationDiagnostic(),
            )
            var captureIdle = false
            var captureAttempts = 0
            var captureFile: File? = null
            if (TerminalEvidenceFlowPolicy.shouldAttemptCapture(
                    stepCapturesJoined,
                    stepLeaseLost.get(),
                    uiVerifiedBefore,
                )) {
                val attempt = withContext(Dispatchers.IO) {
                    TerminalCaptureRetryRunner.run(
                        TERMINAL_CAPTURE_ATTEMPTS,
                        awaitIdle = { DebugScreenshotCapture.awaitIdle(TERMINAL_CAPTURE_IDLE_TIMEOUT_MS) },
                        capture = {
                            DebugScreenshotCapture.capture(
                                this@AgentForegroundService,
                                "job_${job.jobId}_attempt_${job.attemptNumber}_verified_editor",
                            )
                        },
                        backoff = { Thread.sleep(TERMINAL_CAPTURE_RETRY_DELAY_MS * it) },
                    )
                }
                captureIdle = attempt.idleObserved
                captureAttempts = attempt.attempts
                captureFile = attempt.value
            }
            var uploadAttempted = false
            val evidence = if (captureFile != null) {
                uploadAttempted = true
                api.uploadScreenshot(job, captureFile!!)
            } else null
            val evidenceAvailable = evidence?.isAvailable == true
            val evidenceCurrentAttempt = evidence?.attemptNumber == job.attemptNumber
            val uiVerifiedAfter = if (evidenceAvailable && evidenceCurrentAttempt) {
                accessibility.isCurrentDryRunUiVerified(job)
            } else false
            val policyAllowed = TerminalEvidenceSequencingPolicy.isReady(
                    stepCapturesJoined, captureFile != null, uiVerifiedBefore,
                    evidenceAvailable, evidenceCurrentAttempt, uiVerifiedAfter,
                )
            val diagnostics = TerminalEvidenceDiagnostics(
                admissionClosed = stepUploads.isClosed(),
                snapshotCount = joinedStepUploads.size,
                allCompleted = allStepCapturesCompleted,
                joinWithinBound = joinedWithinBound,
                parentActive = parentStillActive,
                fatalLeaseLost = stepLeaseLost.get(),
                awaitIdle = captureIdle,
                preUiVerified = uiVerifiedBefore,
                captureAttempts = captureAttempts,
                capturePresent = captureFile != null,
                uploadAttempted = uploadAttempted,
                uploadAvailable = evidenceAvailable,
                evidenceCurrentAttempt = evidenceCurrentAttempt,
                postUiVerified = uiVerifiedAfter,
                policyAllowed = policyAllowed,
            )
            api.addEvent(job, level = if (policyAllowed) "info" else "warning", message = diagnostics.redactedMessage())
            if (!policyAllowed || evidence == null) {
                return@coroutineScope JobStatusReport.needsReview(
                    "Verified editor terminal evidence gate failed; final Create was not pressed",
                    evidence?.evidenceId,
                )
            }
            api.addEvent(job, message = reason, screenshotPath = evidence.screenshotPath)
            return@coroutineScope JobStatusReport.readyToPublish("Stopped before Publish by dry-run guard", evidence.evidenceId)
        }
        val screenshot = captureAndUploadFailureScreenshot(api, job)
        if (trace.isNotBlank()) {
            api.addEvent(job, message = "Accessibility trace: $trace")
        }
        api.addEvent(job, level = "error", message = reason, screenshotPath = screenshot?.screenshotPath)
        JobStatusReport.failed(
            message = reason,
            retryable = isRetryableAccessibilityFailure(reason),
            errorCode = accessibilityErrorCode(reason),
        )
    }

    private fun captureAndUploadFailureScreenshot(api: HubApi, job: PublishJob): EvidenceUploadResult? {
        if (!DebugScreenshotCapture.hasPermission()) {
            api.addEvent(job, level = "error", message = "Failure screenshot unavailable: capture permission is missing or capture is disabled")
            return null
        }
        return uploadCapturedScreenshot(api, job, "job_${job.jobId}_failure", "failure")
    }

    private fun captureAndUploadStepScreenshot(api: HubApi, job: PublishJob, stepLabel: String) {
        if (!DebugScreenshotCapture.hasPermission()) {
            api.addEvent(job, level = "error", message = "Step snapshot skipped: capture permission is missing or capture is disabled")
            return
        }
        val evidence = uploadCapturedScreenshot(api, job, "job_${job.jobId}_${sanitizeStepLabel(stepLabel)}", stepLabel)
        if (evidence != null) {
            api.addEvent(job, message = "Step snapshot: $stepLabel", screenshotPath = evidence.screenshotPath)
        }
    }

    private fun uploadCapturedScreenshot(api: HubApi, job: PublishJob, label: String, outcomeLabel: String): EvidenceUploadResult? {
        repeat(SCREENSHOT_ATTEMPTS) { attempt ->
            try {
                val file = DebugScreenshotCapture.capture(this, label)
                    ?: throw IllegalStateException("capture returned no image")
                return api.uploadScreenshot(job, file)
            } catch (error: CancellationException) {
                throw error
            } catch (error: LeaseLostException) {
                throw error
            } catch (error: Exception) {
                if (attempt == SCREENSHOT_ATTEMPTS - 1) {
                    api.addEvent(job, level = "error", message = "Screenshot $outcomeLabel failed after $SCREENSHOT_ATTEMPTS attempts: ${classifyError(error)}")
                } else {
                    Thread.sleep(SCREENSHOT_RETRY_DELAY_MS * (attempt + 1))
                }
            }
        }
        return null
    }

    private fun classifyFailure(error: Throwable, prefix: String): JobStatusReport {
        val retryable = when (error) {
            is HubHttpException -> error.retryable
            is IOException -> true
            else -> false
        }
        val suffix = when (error) {
            is HubHttpException -> "http_${error.statusCode}"
            is IOException -> "io"
            else -> error.javaClass.simpleName.lowercase()
        }
        return JobStatusReport.failed(
            message = "$prefix failed: ${classifyError(error)}",
            retryable = retryable,
            errorCode = "${prefix}_$suffix".take(120),
        )
    }

    private fun isRetryableAccessibilityFailure(reason: String): Boolean {
        val normalized = reason.lowercase()
        return listOf("account blocked", "login required", "permission denied", "unsupported target")
            .none(normalized::contains)
    }

    private fun accessibilityErrorCode(reason: String): String {
        val normalized = reason.lowercase()
        return when {
            "timeout" in normalized -> "accessibility_timeout"
            "login" in normalized -> "login_required"
            "account blocked" in normalized -> "account_blocked"
            "permission" in normalized -> "permission_denied"
            else -> "accessibility_flow_failed"
        }
    }

    private fun classifyError(error: Throwable): String =
        "${error.javaClass.simpleName}: ${error.message ?: "no message"}"

    private fun sanitizeStepLabel(value: String): String {
        return value.replace(Regex("[^a-zA-Z0-9._-]"), "_")
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_description)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "MobilePosterAgent"
        private const val SCREENSHOT_ATTEMPTS = 2
        private const val SCREENSHOT_RETRY_DELAY_MS = 500L
        private const val TERMINAL_CAPTURE_IDLE_TIMEOUT_MS = 4_000L
        private const val TERMINAL_CAPTURE_ATTEMPTS = 3
        private const val TERMINAL_STATUS_ATTEMPTS = 3
        private const val TERMINAL_STATUS_RETRY_DELAY_MS = 2_000L
        private const val TERMINAL_CAPTURE_RETRY_DELAY_MS = 350L
        private const val HEARTBEAT_INTERVAL_MS = 10_000L
        private const val HEARTBEAT_RETRY_DELAY_MS = 2_000L
        private const val MAX_HEARTBEAT_FAILURES = 3
        private const val CHANNEL_ID = "mobile_poster_agent"
        private const val NOTIFICATION_ID = 1001
        private const val POLL_INTERVAL_MS = 20_000L

        fun start(context: Context) {
            val intent = Intent(context, AgentForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AgentForegroundService::class.java))
        }

        fun stableDeviceId(context: Context): String {
            val prefs = context.getSharedPreferences("mobile_poster_agent", Context.MODE_PRIVATE)
            var id = prefs.getString("stable_device_id", null)
            if (id == null) {
                id = "android-agent-" + UUID.randomUUID().toString().replace("-", "")
                prefs.edit().putString("stable_device_id", id).apply()
            }
            return id
        }
    }
}
