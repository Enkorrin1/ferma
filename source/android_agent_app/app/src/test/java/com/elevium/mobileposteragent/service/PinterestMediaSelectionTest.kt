package com.elevium.mobileposteragent.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PinterestMediaSelectionTest {
    @Test
    fun exactCurrentJobPathOutranksFilenameOnlyMatch() {
        val path = "/storage/emulated/0/Pictures/MobilePosterAgent/mobileposter_job-42.png"

        assertEquals(100, pinterestMediaLabelScore("Photo: $path", path, "mobileposter_job-42.png"))
        assertEquals(50, pinterestMediaLabelScore("Photo: /other/mobileposter_job-42.png", path, "mobileposter_job-42.png"))
        assertEquals(0, pinterestMediaLabelScore("Photo: /other/unrelated.png", path, "mobileposter_job-42.png"))
    }

    @Test
    fun storageAliasesNormalizeToSamePath() {
        assertEquals(
            normalizeMediaPath("/storage/emulated/0/Pictures/item.png"),
            normalizeMediaPath("/sdcard/Pictures/item.png"),
        )
    }

    @Test
    fun retryClassificationOnlyIncludesTransientHttpFailures() {
        assertTrue(MediaPreparer.isTransientHttpStatus(408))
        assertTrue(MediaPreparer.isTransientHttpStatus(429))
        assertTrue(MediaPreparer.isTransientHttpStatus(503))
        assertEquals(false, MediaPreparer.isTransientHttpStatus(404))
    }
}
