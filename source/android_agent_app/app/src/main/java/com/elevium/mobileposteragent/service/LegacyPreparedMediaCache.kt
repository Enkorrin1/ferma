package com.elevium.mobileposteragent.service

import java.io.File

internal object LegacyPreparedMediaCache {
    fun takeValidExactFile(
        targetDir: File,
        jobId: String,
        extension: String,
        validator: (File) -> Boolean,
    ): File? {
        val displayName = "mobileposter_$jobId.$extension"
        val targetFile = File(targetDir, displayName)
        val partialFile = File(targetDir, "$displayName.part")
        if (partialFile.exists()) {
            partialFile.delete()
        }
        val isValid = targetFile.isFile && targetFile.length() > 0L &&
            runCatching { validator(targetFile) }.getOrDefault(false)
        if (!isValid) {
            if (targetFile.exists()) {
                targetFile.delete()
            }
            return null
        }
        return targetFile
    }
}
