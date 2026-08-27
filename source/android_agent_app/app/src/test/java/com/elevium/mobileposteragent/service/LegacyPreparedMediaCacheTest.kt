package com.elevium.mobileposteragent.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LegacyPreparedMediaCacheTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun cacheHitReturnsOnlyTheValidExactJobFile() {
        val targetDir = temporaryFolder.newFolder("pictures")
        val exact = targetDir.resolve("mobileposter_job-1.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }

        val cached = LegacyPreparedMediaCache.takeValidExactFile(targetDir, "job-1", "png") { it == exact }

        assertEquals(exact, cached)
        assertTrue(exact.exists())
    }

    @Test
    fun partialFileIsDeletedAndNeverReused() {
        val targetDir = temporaryFolder.newFolder("pictures")
        val partial = targetDir.resolve("mobileposter_job-1.png.part").apply { writeBytes(byteArrayOf(1)) }

        val cached = LegacyPreparedMediaCache.takeValidExactFile(targetDir, "job-1", "png") { true }

        assertNull(cached)
        assertFalse(partial.exists())
    }

    @Test
    fun invalidExactFileIsDeletedForRedownload() {
        val targetDir = temporaryFolder.newFolder("pictures")
        val invalid = targetDir.resolve("mobileposter_job-1.png").apply { writeBytes(byteArrayOf(1)) }

        val cached = LegacyPreparedMediaCache.takeValidExactFile(targetDir, "job-1", "png") { false }

        assertNull(cached)
        assertFalse(invalid.exists())
    }

    @Test
    fun fileFromDifferentJobIsIgnoredAndPreserved() {
        val targetDir = temporaryFolder.newFolder("pictures")
        val other = targetDir.resolve("mobileposter_job-2.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }

        val cached = LegacyPreparedMediaCache.takeValidExactFile(targetDir, "job-1", "png") { true }

        assertNull(cached)
        assertTrue(other.exists())
    }
}
