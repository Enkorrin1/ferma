package com.elevium.mobileposteragent.service

import java.io.File

internal fun pinterestMediaLabelScore(label: String, expectedPath: String?, expectedName: String?): Int {
    val normalizedLabel = normalizeMediaPath(label.removePrefix("Photo:").trim())
    val normalizedPath = normalizeMediaPath(expectedPath)
    if (normalizedPath.isNotBlank() && normalizedLabel == normalizedPath) return 100
    val name = expectedName?.trim().orEmpty().ifBlank { expectedPath?.let(::File)?.name.orEmpty() }
    if (name.isNotBlank() && label.contains(name, ignoreCase = true)) return 50
    return 0
}

internal fun normalizeMediaPath(value: String?): String =
    value.orEmpty().trim().replace('\\', '/')
        .replace("/storage/emulated/0/", "/sdcard/", ignoreCase = true)
        .lowercase()
