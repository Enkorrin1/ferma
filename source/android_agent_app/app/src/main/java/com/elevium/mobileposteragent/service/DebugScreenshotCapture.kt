package com.elevium.mobileposteragent.service

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.view.WindowManager
import com.elevium.mobileposteragent.data.LeaseLostException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal data class ScreenshotPlaneLayout(
    val width: Int,
    val height: Int,
    val pixelStride: Int,
    val rowStride: Int,
    val bufferLimit: Int,
)

internal object ScreenshotPlaneLayoutPolicy {
    fun isSafe(layout: ScreenshotPlaneLayout): Boolean {
        if (layout.width <= 0 || layout.height <= 0 || layout.pixelStride < 4) return false
        val minimumRowBytes = layout.width.toLong() * layout.pixelStride
        if (minimumRowBytes <= 0 || layout.rowStride.toLong() < minimumRowBytes) return false
        val lastByteExclusive = (layout.height - 1L) * layout.rowStride + minimumRowBytes
        return lastByteExclusive > 0 && lastByteExclusive <= layout.bufferLimit.toLong()
    }
}

internal class ScreenshotCaptureCompletionGate {
    private val claimed = AtomicBoolean(false)
    fun claim(): Boolean = claimed.compareAndSet(false, true)
}

internal object ScreenshotCaptureIdleWaiter {
    fun await(timeoutMs: Long, isBusy: () -> Boolean, sleep: (Long) -> Unit): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs.coerceAtLeast(0))
        while (isBusy()) {
            if (System.nanoTime() >= deadline) return false
            sleep(25L)
        }
        return true
    }
}

internal object TerminalEvidenceSequencingPolicy {
    fun isReady(
        stepCapturesJoined: Boolean,
        capturePresent: Boolean,
        uiVerifiedBefore: Boolean,
        evidenceAvailable: Boolean,
        evidenceCurrentAttempt: Boolean,
        uiVerifiedAfter: Boolean,
    ): Boolean = stepCapturesJoined && capturePresent && uiVerifiedBefore && evidenceAvailable &&
        evidenceCurrentAttempt && uiVerifiedAfter
}

internal class StepCaptureAdmission<T> {
    private val lock = Any()
    private val admitted = mutableListOf<T>()
    private var closed = false

    fun admit(factory: () -> T): T? = synchronized(lock) {
        if (closed) null else factory().also(admitted::add)
    }

    fun closeAndSnapshot(): List<T> = synchronized(lock) {
        closed = true
        admitted.toList()
    }

    fun isClosed(): Boolean = synchronized(lock) { closed }
}

internal object StepCaptureCompletionPolicy {
    fun canProceed(
        joinedWithinBound: Boolean,
        allCompleted: Boolean,
        parentActive: Boolean,
        outcomes: List<StepCaptureOutcome> = emptyList(),
    ): Boolean = joinedWithinBound && allCompleted && parentActive && outcomes.none {
        it == StepCaptureOutcome.PENDING || it == StepCaptureOutcome.CANCELLED ||
            it == StepCaptureOutcome.LEASE_LOST
    }
}

internal enum class StepCaptureOutcome { PENDING, SUCCESS, BEST_EFFORT_FAILED, CANCELLED, LEASE_LOST }

internal data class TerminalEvidenceDiagnostics(
    val admissionClosed: Boolean,
    val snapshotCount: Int,
    val allCompleted: Boolean,
    val joinWithinBound: Boolean,
    val parentActive: Boolean,
    val fatalLeaseLost: Boolean,
    val awaitIdle: Boolean,
    val preUiVerified: Boolean,
    val captureAttempts: Int,
    val capturePresent: Boolean,
    val uploadAttempted: Boolean,
    val uploadAvailable: Boolean,
    val evidenceCurrentAttempt: Boolean,
    val postUiVerified: Boolean,
    val policyAllowed: Boolean,
) {
    fun redactedMessage(): String = listOf(
        "terminal-evidence-diagnostic",
        "admissionClosed=$admissionClosed",
        "snapshotCount=$snapshotCount",
        "allCompleted=$allCompleted",
        "joinWithinBound=$joinWithinBound",
        "parentActive=$parentActive",
        "fatalLeaseLost=$fatalLeaseLost",
        "awaitIdle=$awaitIdle",
        "preUiVerified=$preUiVerified",
        "captureAttempts=$captureAttempts",
        "capturePresent=$capturePresent",
        "uploadAttempted=$uploadAttempted",
        "uploadAvailable=$uploadAvailable",
        "evidenceCurrentAttempt=$evidenceCurrentAttempt",
        "postUiVerified=$postUiVerified",
        "policyAllowed=$policyAllowed",
    ).joinToString(" ")
}

internal data class TerminalCaptureAttempt<T>(val idleObserved: Boolean, val attempts: Int, val value: T?)

internal object TerminalCaptureRetryRunner {
    fun <T> run(
        maxAttempts: Int,
        awaitIdle: () -> Boolean,
        capture: () -> T?,
        backoff: (Int) -> Unit,
    ): TerminalCaptureAttempt<T> {
        var idleObserved = false
        var attempts = 0
        var value: T? = null
        repeat(maxAttempts.coerceAtLeast(0)) { index ->
            if (value == null) {
                attempts = index + 1
                val idle = awaitIdle()
                idleObserved = idleObserved || idle
                // awaitIdle is advisory only. The atomic captureInFlight guard inside capture() is
                // the authoritative ownership check and safely returns null while busy.
                value = capture()
                if (value == null && index < maxAttempts - 1) backoff(index + 1)
            }
        }
        return TerminalCaptureAttempt(idleObserved, attempts, value)
    }
}

internal object StepCaptureJoinBudget {
    fun milliseconds(snapshotCount: Int): Long =
        (snapshotCount.coerceAtLeast(1) * 2_000L).coerceAtMost(30_000L)
}

internal object TerminalEvidenceFlowPolicy {
    fun shouldAttemptCapture(stepCapturesJoined: Boolean, fatalLeaseLost: Boolean, preUiVerified: Boolean): Boolean =
        stepCapturesJoined && !fatalLeaseLost && preUiVerified
}

/**
 * Executes one admitted best-effort capture without leaking ordinary or lease errors from a
 * SupervisorJob child. Lease loss is represented cooperatively by cancelling the owning publish
 * job; callers must also inspect [leaseLost] before sending any terminal status.
 */
internal object StepCaptureCoroutineRunner {
    suspend fun run(
        outcome: AtomicReference<StepCaptureOutcome>,
        leaseLost: AtomicBoolean,
        parentJob: Job?,
        block: suspend () -> Unit,
    ) {
        try {
            block()
            outcome.set(StepCaptureOutcome.SUCCESS)
        } catch (error: LeaseLostException) {
            outcome.set(StepCaptureOutcome.LEASE_LOST)
            leaseLost.set(true)
            parentJob?.cancel(CancellationException("Lease lost during step evidence", error))
            // The owner cancellation is the signal. Never throw the non-cancellation error from a
            // Supervisor child because that would reach CoroutineExceptionHandler as uncaught.
        } catch (error: CancellationException) {
            outcome.set(StepCaptureOutcome.CANCELLED)
            throw error
        } catch (_: Exception) {
            outcome.set(StepCaptureOutcome.BEST_EFFORT_FAILED)
        }
    }
}

object DebugScreenshotCapture {
    private val captureLock = Any()
    private val captureInFlight = AtomicBoolean(false)

    @Volatile
    private var permissionResultCode: Int? = null

    @Volatile
    private var permissionData: Intent? = null

    @Volatile
    private var permissionVerified: Boolean = false

    fun grant(resultCode: Int, data: Intent?) {
        if (resultCode == Activity.RESULT_OK && data != null) {
            permissionResultCode = resultCode
            permissionData = Intent(data)
            permissionVerified = false
        }
    }

    fun hasPermission(): Boolean = permissionResultCode != null && permissionData != null

    fun hasVerifiedPermission(): Boolean = hasPermission() && permissionVerified

    fun verify(context: Context): Boolean {
        val file = capture(context, "screen-capture-preflight")
        val verified = file?.isFile == true && file.length() > 0L
        file?.delete()
        permissionVerified = verified
        return verified
    }

    fun awaitIdle(timeoutMs: Long): Boolean = ScreenshotCaptureIdleWaiter.await(
        timeoutMs,
        isBusy = captureInFlight::get,
        sleep = Thread::sleep,
    )

    fun capture(context: Context, label: String): File? {
        val resultCode = permissionResultCode ?: return null
        val data = permissionData ?: return null
        if (!captureInFlight.compareAndSet(false, true)) return null
        val projectionManager = context.getSystemService(MediaProjectionManager::class.java)
            ?: return null.also { captureInFlight.set(false) }
        val projection = projectionManager.getMediaProjection(resultCode, Intent(data))
            ?: return null.also { captureInFlight.set(false) }

        val metrics = readDisplayMetrics(context)
        if (metrics.widthPixels <= 0 || metrics.heightPixels <= 0) {
            projection.stop()
            captureInFlight.set(false)
            return null
        }

        val imageReader = try {
            ImageReader.newInstance(
                metrics.widthPixels,
                metrics.heightPixels,
                PixelFormat.RGBA_8888,
                2,
            )
        } catch (_: Exception) {
            projection.stop()
            captureInFlight.set(false)
            return null
        }
        val handlerThread = HandlerThread("mobile-poster-screen-capture").apply { start() }
            val handler = Handler(handlerThread.looper)
            val latch = CountDownLatch(1)
            val resultRef = AtomicReference<File?>()
            val completionGate = ScreenshotCaptureCompletionGate()

            var virtualDisplay: VirtualDisplay? = null
            fun completeOnCaptureThread(result: File?) {
                resultRef.set(result)
                imageReader.setOnImageAvailableListener(null, null)
                virtualDisplay?.release()
                virtualDisplay = null
                imageReader.close()
                projection.stop()
                captureInFlight.set(false)
                latch.countDown()
                handlerThread.quitSafely()
            }

            try {
                virtualDisplay = projection.createVirtualDisplay(
                    "mobile-poster-debug-capture",
                    metrics.widthPixels,
                    metrics.heightPixels,
                    metrics.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader.surface,
                    null,
                    handler,
                )

                imageReader.setOnImageAvailableListener({ reader ->
                    if (!completionGate.claim()) return@setOnImageAvailableListener
                    val image = reader.acquireLatestImage()
                    if (image == null) {
                        completeOnCaptureThread(null)
                        return@setOnImageAvailableListener
                    }
                    var captureResult: File? = null
                    try {
                        val plane = image.planes.firstOrNull()
                        if (plane != null) {
                            val pixelStride = plane.pixelStride
                            val rowStride = plane.rowStride
                            val buffer = plane.buffer.asReadOnlyBuffer()
                            val layout = ScreenshotPlaneLayout(
                                metrics.widthPixels, metrics.heightPixels, pixelStride, rowStride, buffer.limit(),
                            )
                            if (!ScreenshotPlaneLayoutPolicy.isSafe(layout)) {
                                throw IllegalStateException("Capture plane buffer is smaller than expected")
                            }
                            val pixels = IntArray(metrics.widthPixels * metrics.heightPixels)
                            var outputIndex = 0
                            for (y in 0 until metrics.heightPixels) {
                                val rowStart = y * rowStride
                                for (x in 0 until metrics.widthPixels) {
                                    val offset = rowStart + x * pixelStride
                                    val red = buffer.get(offset).toInt() and 0xff
                                    val green = buffer.get(offset + 1).toInt() and 0xff
                                    val blue = buffer.get(offset + 2).toInt() and 0xff
                                    val alpha = buffer.get(offset + 3).toInt() and 0xff
                                    pixels[outputIndex++] = (alpha shl 24) or (red shl 16) or (green shl 8) or blue
                                }
                            }
                            val bitmap = Bitmap.createBitmap(metrics.widthPixels, metrics.heightPixels, Bitmap.Config.ARGB_8888)
                            try {
                                bitmap.setPixels(pixels, 0, metrics.widthPixels, 0, 0, metrics.widthPixels, metrics.heightPixels)
                                captureResult = saveBitmap(context, bitmap, label)
                            } finally {
                                bitmap.recycle()
                            }
                        }
                    } catch (_: Exception) {
                        captureResult = null
                    } finally {
                        image.close()
                        completeOnCaptureThread(captureResult)
                    }
                }, handler)

                if (!latch.await(5, TimeUnit.SECONDS)) {
                    handler.post {
                        if (completionGate.claim()) completeOnCaptureThread(null)
                    }
                    latch.await(2, TimeUnit.SECONDS)
                }
                return resultRef.get()
            } catch (_: Exception) {
                handler.post {
                    if (completionGate.claim()) completeOnCaptureThread(null)
                }
                latch.await(2, TimeUnit.SECONDS)
                return null
            }
    }

    @Suppress("DEPRECATION")
    private fun readDisplayMetrics(context: Context): DisplayMetrics {
        val metrics = DisplayMetrics()
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager.defaultDisplay.getRealMetrics(metrics)
        return metrics
    }

    private fun saveBitmap(context: Context, bitmap: Bitmap, label: String): File {
        val dir = File(context.cacheDir, "debug_screenshots").apply { mkdirs() }
        val safeLabel = label.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val file = File(dir, "${safeLabel}_${System.currentTimeMillis()}.png")
        FileOutputStream(file).use { stream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        }
        return file
    }
}
