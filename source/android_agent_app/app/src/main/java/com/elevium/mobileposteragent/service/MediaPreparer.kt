package com.elevium.mobileposteragent.service

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.elevium.mobileposteragent.model.PublishJob
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object MediaPreparer {
    private val client = OkHttpClient()

    private data class MediaKind(
        val extension: String,
        val mimeType: String,
        val isVideo: Boolean,
    )

    fun prepare(context: Context, job: PublishJob): PreparedMedia? {
        val mediaUrl = job.mediaUrl ?: return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            importViaMediaStore(context, mediaUrl, job.jobId)
        } else {
            importViaLegacyStorage(context, mediaUrl, job.jobId)
        }
    }

    private fun importViaMediaStore(context: Context, url: String, jobId: String): PreparedMedia {
        return withDownloadRetry(url) { response ->
            val body = response.body ?: throw IOException("Media download returned empty body")
            val mediaKind = detectMediaKind(url, response.header("Content-Type"))
            val resolver = context.contentResolver
            val displayName = "mobileposter_$jobId.${mediaKind.extension}"
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, mediaKind.mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${mediaDirectory(mediaKind)}/MobilePosterAgent")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val collectionUri =
                if (mediaKind.isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val uri = resolver.insert(collectionUri, values)
                ?: throw IOException("Failed to create MediaStore record")

            resolver.openOutputStream(uri)?.use { output ->
                body.byteStream().use { input ->
                    input.copyTo(output)
                }
            } ?: throw IOException("Failed to open MediaStore output stream")

            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            PreparedMedia(uri.toString(), absolutePath = null, displayName = displayName)
        }
    }

    private fun importViaLegacyStorage(context: Context, url: String, jobId: String): PreparedMedia {
        if (!hasLegacyStoragePermission(context)) {
            throw IOException("Storage permission is not granted on this Android version")
        }

        val expectedKind = detectMediaKind(url, contentType = null)
        val expectedDirectory = File(
            Environment.getExternalStoragePublicDirectory(mediaDirectory(expectedKind)),
            "MobilePosterAgent",
        )
        val cachedFile = LegacyPreparedMediaCache.takeValidExactFile(
            targetDir = expectedDirectory,
            jobId = jobId,
            extension = expectedKind.extension,
        ) { file ->
            validateLegacyMediaFile(file, expectedKind)
        }
        if (cachedFile != null) {
            // A retry can reuse the exact bytes, but Android 9 media pickers sort by the
            // MediaStore timestamp. Refresh only this job-owned cache entry so that the
            // calibrated first media tile still represents the active job after a reboot
            // or lease retry.
            cachedFile.setLastModified(System.currentTimeMillis())
            return preparedLegacyMedia(context, cachedFile, expectedKind.mimeType)
        }

        return withDownloadRetry(url) { response ->
            val body = response.body ?: throw IOException("Media download returned empty body")
            val mediaKind = detectMediaKind(url, response.header("Content-Type"))
            val publicDir = Environment.getExternalStoragePublicDirectory(mediaDirectory(mediaKind))
            val targetDir = File(publicDir, "MobilePosterAgent")
            if (!targetDir.exists()) {
                targetDir.mkdirs()
            }
            val displayName = "mobileposter_$jobId.${mediaKind.extension}"
            val targetFile = File(targetDir, displayName)
            val partialFile = File(targetDir, "$displayName.part")
            partialFile.outputStream().use { output ->
                body.byteStream().use { input ->
                    input.copyTo(output)
                }
            }
            if (targetFile.exists() && !targetFile.delete()) {
                partialFile.delete()
                throw IOException("Failed to replace existing media file")
            }
            if (!partialFile.renameTo(targetFile)) {
                partialFile.delete()
                throw IOException("Failed to finalize downloaded media file")
            }
            preparedLegacyMedia(context, targetFile, mediaKind.mimeType)
        }
    }

    private fun preparedLegacyMedia(context: Context, file: File, mimeType: String): PreparedMedia {
        val scanFinished = CountDownLatch(1)
        MediaScannerConnection.scanFile(
            context,
            arrayOf(file.absolutePath),
            arrayOf(mimeType),
        ) { _, _ -> scanFinished.countDown() }
        if (!scanFinished.await(MEDIA_SCAN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            throw IOException("MediaStore scan timed out")
        }
        val shareUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        ).toString()
        return PreparedMedia(shareUri, file.absolutePath, file.name)
    }

    private fun validateLegacyMediaFile(file: File, mediaKind: MediaKind): Boolean {
        return if (mediaKind.isVideo) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.absolutePath)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes" ||
                    (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0) > 0 &&
                    (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0) > 0
            } catch (_: RuntimeException) {
                false
            } finally {
                retriever.release()
            }
        } else {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, options)
            options.outWidth > 0 && options.outHeight > 0
        }
    }

    private fun <T> withDownloadRetry(url: String, operation: (Response) -> T): T {
        var lastError: IOException? = null
        repeat(MAX_DOWNLOAD_ATTEMPTS) { index ->
            try {
                val request = Request.Builder().url(url).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        val error = IOException("Media download failed: HTTP ${response.code}")
                        if (!isTransientHttpStatus(response.code)) throw PermanentDownloadException(error.message.orEmpty())
                        throw error
                    }
                    return operation(response)
                }
            } catch (error: PermanentDownloadException) {
                throw error
            } catch (error: IOException) {
                lastError = error
                if (index == MAX_DOWNLOAD_ATTEMPTS - 1) throw error
                Thread.sleep(RETRY_BASE_DELAY_MS * (1L shl index))
            }
        }
        throw lastError ?: IOException("Media download failed")
    }

    internal fun isTransientHttpStatus(code: Int): Boolean = code == 408 || code == 429 || code >= 500

    private class PermanentDownloadException(message: String) : IOException(message)

    fun hasLegacyStoragePermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun detectMediaKind(url: String, contentType: String?): MediaKind {
        val extension = detectExtension(url, contentType)
        val mimeType = detectMimeType(extension, contentType)
        return MediaKind(
            extension = extension,
            mimeType = mimeType,
            isVideo = isVideo(extension, mimeType),
        )
    }

    private fun detectExtension(url: String, contentType: String?): String {
        val cleanPath = url.lowercase(Locale.US).substringBefore("?").substringBefore("#")
        return when {
            cleanPath.endsWith(".mp4") -> "mp4"
            cleanPath.endsWith(".mov") -> "mov"
            cleanPath.endsWith(".webm") -> "webm"
            cleanPath.endsWith(".mkv") -> "mkv"
            cleanPath.endsWith(".3gp") -> "3gp"
            cleanPath.endsWith(".png") -> "png"
            cleanPath.endsWith(".webp") -> "webp"
            cleanPath.endsWith(".jpg") || cleanPath.endsWith(".jpeg") -> "jpg"
            contentType?.contains("mp4", ignoreCase = true) == true -> "mp4"
            contentType?.contains("quicktime", ignoreCase = true) == true -> "mov"
            contentType?.contains("webm", ignoreCase = true) == true -> "webm"
            contentType?.contains("matroska", ignoreCase = true) == true -> "mkv"
            contentType?.contains("3gpp", ignoreCase = true) == true -> "3gp"
            contentType?.contains("video", ignoreCase = true) == true -> "mp4"
            contentType?.contains("png", ignoreCase = true) == true -> "png"
            contentType?.contains("webp", ignoreCase = true) == true -> "webp"
            else -> "jpg"
        }
    }

    private fun detectMimeType(extension: String, contentType: String?): String {
        if (!contentType.isNullOrBlank()) {
            return contentType.substringBefore(";").trim()
        }
        return when (extension) {
            "mp4" -> "video/mp4"
            "mov" -> "video/quicktime"
            "webm" -> "video/webm"
            "mkv" -> "video/x-matroska"
            "3gp" -> "video/3gpp"
            "png" -> "image/png"
            "webp" -> "image/webp"
            else -> "image/jpeg"
        }
    }

    private fun isVideo(extension: String, mimeType: String): Boolean {
        return mimeType.startsWith("video/", ignoreCase = true) ||
            extension in setOf("mp4", "mov", "webm", "mkv", "3gp")
    }

    private fun mediaDirectory(mediaKind: MediaKind): String {
        return if (mediaKind.isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
    }

    private const val MAX_DOWNLOAD_ATTEMPTS = 3
    private const val RETRY_BASE_DELAY_MS = 500L
    private const val MEDIA_SCAN_TIMEOUT_SECONDS = 5L
}
