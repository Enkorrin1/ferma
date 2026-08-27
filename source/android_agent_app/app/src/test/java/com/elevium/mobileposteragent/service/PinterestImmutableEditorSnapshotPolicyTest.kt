package com.elevium.mobileposteragent.service

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class PinterestImmutableEditorSnapshotPolicyTest {
    private val stableNodes = listOf(
        PinterestNodeSnapshot("root/0", "", "Image", "", "android.widget.ImageView", "com.pinterest:id/attribute_image_view", true, false, "[10,100][210,300]"),
        PinterestNodeSnapshot("root/1", "Title. Tell everyone what your Pin is about", "", "Title", "android.widget.EditText", "title", true, true, "[20,320][700,420]"),
        PinterestNodeSnapshot("root/2", "Description. Tell everyone more about your Pin", "", "Description", "android.widget.EditText", "description", true, true, "[20,430][700,600]"),
        PinterestNodeSnapshot("root/3", "Link. Add your link here", "", "Link", "android.widget.EditText", "link", true, true, "[20,610][700,700]"),
        PinterestNodeSnapshot("root/4", "0/100", "0 characters out of 100", "", "android.view.View", "counter", true, false, "[600,400][700,430]"),
        PinterestNodeSnapshot("root/5", "0/800", "", "", "android.view.View", "counter", true, false, "[600,580][700,610]"),
    )

    @Test fun stableSingleGenerationFixturePassesMediaAndEditorGate() {
        val snapshot = PinterestImmutableEditorSnapshotPolicy.create(stableNodes, 42, 42)
        val extraction = PinterestEditorExtractionAdapter.extract(snapshot.nodes)
        val gate = PinterestEditorExtractionAdapter.evaluate(
            extraction, null, "", null,
            PinterestImmutableEditorSnapshotPolicy.hasBoardOverlay(snapshot),
            PinterestImmutableEditorSnapshotPolicy.hasMediaPreview(snapshot, true),
            snapshot.generation > 0, snapshot.fingerprint.isNotBlank(),
        )
        assertTrue(snapshot.consistent)
        assertTrue(gate.verified)
    }

    @Test fun rootGenerationChangeBetweenReadsFailsClosed() {
        val snapshot = PinterestImmutableEditorSnapshotPolicy.create(stableNodes, 42, 43)
        assertFalse(snapshot.consistent)
        assertFalse(PinterestImmutableEditorSnapshotPolicy.hasMediaPreview(snapshot, true))
    }

    @Test fun overlayFromSameSnapshotOverridesUnderlyingComposer() {
        val overlay = stableNodes + PinterestNodeSnapshot(
            "root/6", "Save to board", "", "", "android.view.View", "board_picker", true, false, "[0,0][720,400]",
        )
        val snapshot = PinterestImmutableEditorSnapshotPolicy.create(overlay, 44, 44)
        assertTrue(PinterestImmutableEditorSnapshotPolicy.hasBoardOverlay(snapshot))
        assertFalse(PinterestImmutableEditorSnapshotPolicy.hasMediaPreview(snapshot, false))
    }

    @Test fun exactAttempt35XmlPassesProductionExtractionAndGate() {
        val file = File("D:/Project/Ферма/artifacts/runtime-smoke/job-ed4b-attempt35-terminal.xml")
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = mutableListOf<PinterestNodeSnapshot>()
        fun visit(element: org.w3c.dom.Element, path: String) {
            if (element.tagName == "node") {
                nodes += PinterestNodeSnapshot(
                    path, element.getAttribute("text"), element.getAttribute("content-desc"), "",
                    element.getAttribute("class"), element.getAttribute("resource-id"),
                    element.getAttribute("visible-to-user") != "false", element.getAttribute("class") == "android.widget.EditText",
                    element.getAttribute("bounds"),
                )
            }
            var childIndex = 0
            val children = element.childNodes
            for (index in 0 until children.length) {
                val child = children.item(index)
                if (child is org.w3c.dom.Element) {
                    visit(child, "$path/$childIndex")
                    childIndex += 1
                }
            }
        }
        visit(document.documentElement, "root")
        val snapshot = PinterestImmutableEditorSnapshotPolicy.create(nodes, 50, 50)
        val extraction = PinterestEditorExtractionAdapter.extract(snapshot.nodes)
        val overlay = PinterestImmutableEditorSnapshotPolicy.hasBoardOverlay(snapshot)
        val media = PinterestImmutableEditorSnapshotPolicy.hasMediaPreview(snapshot, true)
        val gate = PinterestEditorExtractionAdapter.evaluate(
            extraction, null, "", null, overlay, media,
            snapshot.generation > 0, snapshot.fingerprint.isNotBlank(),
        )
        assertFalse(overlay)
        assertTrue(media)
        assertTrue(gate.diagnostic, gate.verified)
    }

    @Test fun repeatedGenerationChurnTraversesThreeTimesAndSleepsTwice() {
        var calls = 0
        var sleeps = 0
        val result = PinterestImmutableSnapshotAcquirer.acquire(3, {
            calls += 1
            PinterestImmutableEditorSnapshotPolicy.create(stableNodes, calls.toLong(), calls.toLong() + 1)
        }, { sleeps += 1 })
        assertEquals(PinterestImmutableSnapshotReason.GENERATION_CHURN, result.reason)
        assertEquals(3, calls)
        assertEquals(2, sleeps)
    }

    @Test fun emptyNodesFailImmediatelyWithoutSleep() {
        var calls = 0
        var sleeps = 0
        val result = PinterestImmutableSnapshotAcquirer.acquire(3, {
            calls += 1
            PinterestImmutableEditorSnapshotPolicy.create(emptyList(), 1, 1)
        }, { sleeps += 1 })
        assertEquals(PinterestImmutableSnapshotReason.EMPTY_NODES, result.reason)
        assertEquals(1, calls)
        assertEquals(0, sleeps)
    }

    @Test fun nonpositiveGenerationFailsImmediatelyWithoutSleep() {
        var calls = 0
        var sleeps = 0
        val result = PinterestImmutableSnapshotAcquirer.acquire(3, {
            calls += 1
            PinterestImmutableEditorSnapshotPolicy.create(stableNodes, 0, 0)
        }, { sleeps += 1 })
        assertEquals(PinterestImmutableSnapshotReason.NONPOSITIVE_GENERATION, result.reason)
        assertEquals(1, calls)
        assertEquals(0, sleeps)
    }

    @Test fun churnThenStableUsesOneSleepAndReturnsStable() {
        var calls = 0
        var sleeps = 0
        val result = PinterestImmutableSnapshotAcquirer.acquire(3, {
            calls += 1
            if (calls == 1) PinterestImmutableEditorSnapshotPolicy.create(stableNodes, 2, 3)
            else PinterestImmutableEditorSnapshotPolicy.create(stableNodes, 3, 3)
        }, { sleeps += 1 })
        assertTrue(result.consistent)
        assertEquals(2, calls)
        assertEquals(1, sleeps)
    }

    @Test fun invalidFingerprintFailsImmediatelyWithoutSleep() {
        var calls = 0
        var sleeps = 0
        val invalid = PinterestImmutableEditorSnapshot(
            stableNodes, 5, "", false, PinterestImmutableSnapshotReason.INVALID_FINGERPRINT,
        )
        val result = PinterestImmutableSnapshotAcquirer.acquire(3, { calls += 1; invalid }, { sleeps += 1 })
        assertEquals(PinterestImmutableSnapshotReason.INVALID_FINGERPRINT, result.reason)
        assertEquals(1, calls)
        assertEquals(0, sleeps)
    }

    @Test fun sameAttemptPromotedBoardProofSurvivesLiveCleanup() {
        assertTrue(PinterestAttemptProofPolicy.qualifies(
            false, false, false, true, false, false, true,
        ))
    }

    @Test fun staleAttemptOrWrongJobProofIsRejected() {
        assertFalse(PinterestAttemptProofPolicy.qualifies(
            false, false, false, false, true, true, true,
        ))
    }
}
