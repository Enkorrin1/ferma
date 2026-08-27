package com.elevium.mobileposteragent.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ComponentName
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.content.pm.PackageManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.elevium.mobileposteragent.data.ConfigStore
import com.elevium.mobileposteragent.model.PublishJob
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AgentAccessibilityService : AccessibilityService() {
    private var lastFailureReason: String? = null
    private var lastEditorGateDiagnostic: String = "editor-subgates unavailable"
    private val trace = mutableListOf<String>()
    private var publishDeadlineAt: Long = Long.MAX_VALUE
    private var timeoutFailureEmitted = false
    private var debugStepSnapshotCallback: ((String) -> Unit)? = null
    private var debugStepSnapshotBudget = 0
    private var stopBeforePublish = false
    private var preparedMediaPath: String? = null
    private var preparedMediaName: String? = null
    private var preparedMediaShareUri: String? = null
    private var pinterestExactMediaSelectionConfirmed = false
    private var pinterestComposerReachedViaVerifiedPipeline = false
    private var pinterestConfiguredExactBoardSelectedThisAttempt = false
    private var pinterestDirectShareDiagnostic: String? = null
    private var pinterestDirectShareQualifiedThisAttempt = false
    private var pinterestPendingExactBoardSelection = false
    private var pinterestPendingBoardGeneration = 0L
    private var pinterestPendingBoardFingerprint = ""
    private var terminalProofJobId: String? = null
    private var terminalProofAttempt: Int? = null
    private var terminalProofComposerPipeline = false
    private var terminalProofExactBoard = false
    private var terminalProofDirectShare = false
    private var terminalSocialProofJobId: String? = null
    private var terminalSocialProofAttempt: Int? = null
    private var terminalSocialProofPackage: String? = null
    private var terminalSocialProofFingerprint: String? = null
    private var pinterestPendingBoardStartedAt = 0L
    private var softKeyboardSuppressedForPublish = false
    private var verifiedPublicationId: String? = null
    @Volatile private var windowContentGeneration = 0L
    private data class EditableField(
        val node: AccessibilityNodeInfo,
        val label: String,
    )
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        when (event?.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            -> windowContentGeneration++
        }
    }

    override fun onInterrupt() = Unit

    override fun onServiceConnected() {
        instance = this
        BootRecoveryCoordinator.recover(this)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) {
            instance = null
        }
        return super.onUnbind(intent)
    }

    fun canAutomate(): Boolean = instance === this && rootInActiveWindow != null

    fun prepareAutomationWindow(): Boolean {
        moveAwayFromAgentUiIfNeeded()
        if (rootInActiveWindow != null) return true
        performGlobalAction(GLOBAL_ACTION_HOME)
        val start = SystemClock.uptimeMillis()
        while (SystemClock.uptimeMillis() - start < 1500) {
            if (rootInActiveWindow != null) {
                return true
            }
            SystemClock.sleep(150)
        }
        return rootInActiveWindow != null
    }

    fun publish(
        job: PublishJob,
        preparedMedia: PreparedMedia? = null,
        onDebugStepSnapshot: ((String) -> Unit)? = null,
    ): Boolean {
        lastFailureReason = null
        verifiedPublicationId = null
        trace.clear()
        timeoutFailureEmitted = false
        debugStepSnapshotCallback = onDebugStepSnapshot
        debugStepSnapshotBudget = if (onDebugStepSnapshot != null) DEBUG_STEP_SNAPSHOT_LIMIT else 0
        stopBeforePublish = job.target == "pinterest_dry_run" || SocialDryRunPolicy.isDryRun(job.target)
        preparedMediaPath = preparedMedia?.absolutePath ?: job.mediaPath
        preparedMediaName = preparedMedia?.displayName ?: preparedMediaPath?.let(::File)?.name
        preparedMediaShareUri = preparedMedia?.shareUri
        pinterestExactMediaSelectionConfirmed = false
        pinterestComposerReachedViaVerifiedPipeline = false
        pinterestConfiguredExactBoardSelectedThisAttempt = false
        pinterestDirectShareDiagnostic = null
        pinterestDirectShareQualifiedThisAttempt = false
        clearTerminalPinterestProof()
        clearTerminalSocialProof()
        clearPinterestBoardSelectionProof()
        publishDeadlineAt = SystemClock.uptimeMillis() +
            if (SocialDryRunPolicy.automationTarget(job.target) != null || job.target == "youtube_short") {
                SOCIAL_PUBLISH_TIMEOUT_MS
            } else {
                PUBLISH_TIMEOUT_MS
            }
        step("publish:${job.target}")
        val result = when (job.target) {
            "instagram_reel", "tiktok_post", "instagram_reel_dry_run", "tiktok_post_dry_run" -> publishSocialFlow(job)
            "threads_post" -> publishThreads(job)
            "youtube_short" -> publishYouTubeShort(job)
        "pinterest_pin" -> if (verifyExistingPinterestPin(job)) true else publishPinterest(job, preparedMedia)
        "pinterest_pin_verify" -> if (verifyExistingPinterestPin(job)) {
            true
        } else {
            needsReview("Existing Pinterest Pin could not be verified in the exact configured board; no Create was pressed")
        }
            "pinterest_dry_run" -> publishPinterest(job, preparedMedia)
            "pinterest_calibrate_create" -> capturePinterestCreateCalibration()
            else -> fail("Unsupported target: ${job.target}")
        }
        if (job.target == "pinterest_dry_run" && lastFailureReason?.startsWith(READY_TO_PUBLISH_PREFIX) == true) {
            terminalProofJobId = job.jobId
            terminalProofAttempt = job.attemptNumber
            terminalProofComposerPipeline = pinterestComposerReachedViaVerifiedPipeline
            terminalProofExactBoard = pinterestConfiguredExactBoardSelectedThisAttempt
            terminalProofDirectShare = pinterestDirectShareQualifiedThisAttempt
        }
        publishDeadlineAt = Long.MAX_VALUE
        timeoutFailureEmitted = false
        debugStepSnapshotCallback = null
        debugStepSnapshotBudget = 0
        stopBeforePublish = false
        preparedMediaPath = null
        preparedMediaName = null
        preparedMediaShareUri = null
        pinterestExactMediaSelectionConfirmed = false
        pinterestComposerReachedViaVerifiedPipeline = false
        pinterestConfiguredExactBoardSelectedThisAttempt = false
        pinterestDirectShareDiagnostic = null
        pinterestDirectShareQualifiedThisAttempt = false
        restoreSoftKeyboardMode()
        clearPinterestBoardSelectionProof()
        return result
    }

    fun lastErrorMessage(): String? = lastFailureReason

    fun lastVerifiedPublicationId(): String? = verifiedPublicationId

    fun lastTraceSummary(): String = trace.joinToString(" -> ")

    fun lastEditorVerificationDiagnostic(): String = lastEditorGateDiagnostic

    fun isCurrentDryRunUiVerified(job: PublishJob): Boolean =
        when {
            job.target == "pinterest_dry_run" ->
                currentPackageName() == "com.pinterest" && verifyCurrentPinterestEditor(job)
            SocialDryRunPolicy.isDryRun(job.target) -> verifyCurrentSocialDryRunEditor(job)
            else -> false
        }

    private fun checkPublishTimeout(): Boolean {
        if (SystemClock.uptimeMillis() <= publishDeadlineAt) return false
        if (!timeoutFailureEmitted) {
            timeoutFailureEmitted = true
            step("publish:timeout")
            fail("Publish flow timed out")
        }
        return true
    }

    private fun publishInstagram(job: PublishJob): Boolean {
        moveAwayFromAgentUiIfNeeded()
        if (!launchPackageAndWait("com.instagram.android", "Instagram")) return false
        if (SocialLegalGatePolicy.classify(currentPackageName(), visibleLabels()) == SocialLegalGate.INSTAGRAM_TERMS) {
            return fail("Instagram initial legal acceptance requires explicit user setup; Continue was not pressed")
        }
        if (!clickAnyText(listOf("Create", "New post", "Добавить", "Создать"))) {
            return fail("Instagram: could not find Create/New post")
        }
        clickAnyText(listOf("Reel", "Reels", "Клип"))
        if (!clickFirstCandidateNode()) return fail("Instagram: could not select media")
        if (!clickAnyText(listOf("Next", "Далее"))) return fail("Instagram: could not find first Next")
        clickAnyText(listOf("Next", "Далее"))
        if (!setTextIntoFirstField(job.caption)) return fail("Instagram: could not fill caption field")
        return if (clickAnyText(listOf("Share", "Поделиться", "Опубликовать"))) true
        else fail("Instagram: could not find Share/Publish")
    }

    private fun publishSocialFlow(job: PublishJob): Boolean {
        val target = SocialDryRunPolicy.automationTarget(job.target)
            ?: return fail("Unsupported social dry-run target: ${job.target}")
        val expectedAccount = SocialDryRunPolicy.normalizeAccountLabel(
            target.platform,
            job.platformAccountLabel ?: job.accountLabel,
        )
            ?: return needsReview("Social dry-run requires a nonempty canonical Hub account_label")
        moveAwayFromAgentUiIfNeeded()
        val appName = if (target.platform == SocialPlatform.INSTAGRAM) "Instagram" else "TikTok"
        if (!launchPackageAndWait(target.packageName, appName)) return false
        if (target.platform == SocialPlatform.INSTAGRAM) {
            // Interrupted runs commonly leave the final Share composer in the foreground.
            // Re-open the exact configured profile before account proof so a fresh job never
            // inherits a stale editor or accidentally treats its Share control as navigation.
            openInstagramProfile(expectedAccount)
            SystemClock.sleep(SOCIAL_PROFILE_TREE_SETTLE_MS)
        }
        // Package focus precedes the first fully populated profile tree on both apps.  Wait a
        // bounded interval before taking the one immutable classification snapshot; this does
        // not perform any UI action and keeps legal/login/account/media decisions on one tree.
        SystemClock.sleep(SOCIAL_PROFILE_TREE_SETTLE_MS)
        var snapshot = acquireStableSocialAccessibilitySnapshot()
        if (
            target.platform == SocialPlatform.INSTAGRAM &&
            SocialAccessibilitySnapshotPolicy.isCalibratedInstagramReelPicker(snapshot)
        ) {
            performGlobalAction(GLOBAL_ACTION_BACK)
            SystemClock.sleep(SOCIAL_PROFILE_TREE_SETTLE_MS)
            openInstagramProfile(expectedAccount)
            SystemClock.sleep(SOCIAL_PROFILE_TREE_SETTLE_MS)
            snapshot = acquireStableSocialAccessibilitySnapshot()
        }
        if (
            target.platform == SocialPlatform.INSTAGRAM &&
            (
                SocialAccessibilitySnapshotPolicy.isInstagramOwnedCaptionComposerBase(snapshot) ||
                    SocialAccessibilitySnapshotPolicy.isVerifiedInstagramVideoEditor(snapshot)
            )
        ) {
            var resetDone = false
            repeat(2) {
                if (!resetDone) {
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    SystemClock.sleep(SOCIAL_PROFILE_TREE_SETTLE_MS)
                    if (clickExactVisibleText("Start over")) {
                        SystemClock.sleep(SOCIAL_PROFILE_TREE_SETTLE_MS)
                        resetDone = true
                    }
                }
            }
            openInstagramProfile(expectedAccount)
            SystemClock.sleep(SOCIAL_PROFILE_TREE_SETTLE_MS)
            snapshot = acquireStableSocialAccessibilitySnapshot()
        }
        if (target.platform == SocialPlatform.TIKTOK && (!snapshot.consistent || snapshot.nodes.none { it.visible })) {
            snapshot = waitForTikTokSnapshot(TIKTOK_EDITOR_LOAD_TIMEOUT_MS) { candidate ->
                candidate.consistent && candidate.nodes.isNotEmpty() &&
                    (
                        SocialAccessibilitySnapshotPolicy.isTikTokAuthenticatedShell(candidate) ||
                            SocialAccessibilitySnapshotPolicy.hasTikTokOwnedNavigation(candidate) ||
                            SocialAccessibilitySnapshotPolicy.classify(
                                SocialPlatform.TIKTOK,
                                target.packageName,
                                expectedAccount,
                                candidate,
                            ).screen != SocialScreenKind.UNKNOWN
                    )
            } ?: snapshot
        }
        // Resume from a clean TikTok shell. Interrupted runs may leave either the final
        // composer or the non-final editor open; the calibrated Create coordinate overlaps the
        // final Post button on those screens. Back out through those non-terminal editors first
        // so a new job can never mistake Post/Next for the Home Create control.
        if (target.platform == SocialPlatform.TIKTOK) {
            repeat(2) {
                val staleEditor = SocialAccessibilitySnapshotPolicy.isTikTokFinalComposerStructure(snapshot) ||
                    SocialAccessibilitySnapshotPolicy.isVerifiedTikTokVideoEditor(snapshot)
                if (!staleEditor) return@repeat
                if (!performGlobalAction(GLOBAL_ACTION_BACK)) return@repeat
                step("social-tiktok-stale-editor-backed-out")
                SystemClock.sleep(SOCIAL_PROFILE_TREE_SETTLE_MS)
                snapshot = acquireStableSocialAccessibilitySnapshot()
            }
        }
        // A previous interrupted attempt can leave TikTok's media picker in the foreground.
        // Close that exact picker before starting this job so reopening Create forces TikTok to
        // refresh its MediaStore-backed Recents list and expose the newly prepared job media.
        if (
            target.platform == SocialPlatform.TIKTOK &&
            SocialAccessibilitySnapshotPolicy.isCalibratedTikTokFirstVideoPicker(snapshot) &&
            clickExactVisibleViewIdAtBounds(
                SocialAccessibilitySnapshotPolicy.TIKTOK_MEDIA_PICKER_CLOSE_VIEW_ID,
                SocialAccessibilitySnapshotPolicy.TIKTOK_MEDIA_PICKER_CLOSE_BOUNDS,
            )
        ) {
            step("social-tiktok-stale-picker-closed")
            snapshot = waitForTikTokSnapshot(TIKTOK_EDITOR_LOAD_TIMEOUT_MS) { candidate ->
                SocialAccessibilitySnapshotPolicy.isTikTokAuthenticatedShell(candidate) ||
                    SocialAccessibilitySnapshotPolicy.hasTikTokOwnedNavigation(candidate)
            } ?: acquireStableSocialAccessibilitySnapshot()
        }
        if (
            target.platform == SocialPlatform.TIKTOK &&
            SocialAccessibilitySnapshotPolicy.mayOpenTikTokProfile(snapshot) &&
            clickExactVisibleViewIdAtBounds(
                SocialAccessibilitySnapshotPolicy.TIKTOK_PROFILE_ENTRY_VIEW_ID,
                SocialAccessibilitySnapshotPolicy.TIKTOK_PROFILE_ENTRY_BOUNDS,
            )
        ) {
            snapshot = waitForTikTokSnapshot(TIKTOK_EDITOR_LOAD_TIMEOUT_MS) { candidate ->
                SocialAccessibilitySnapshotPolicy.classify(
                    SocialPlatform.TIKTOK,
                    target.packageName,
                    expectedAccount,
                    candidate,
                ).screen == SocialScreenKind.ACCOUNT_PROOF
            } ?: acquireStableSocialAccessibilitySnapshot()
        }
        if (target.platform == SocialPlatform.INSTAGRAM) {
            snapshot = waitForTikTokSnapshot(TIKTOK_EDITOR_LOAD_TIMEOUT_MS) { candidate ->
                SocialAccessibilitySnapshotPolicy.mayOpenInstagramProfile(candidate) ||
                    SocialAccessibilitySnapshotPolicy.classify(
                        SocialPlatform.INSTAGRAM,
                        target.packageName,
                        expectedAccount,
                        candidate,
                    ).screen == SocialScreenKind.ACCOUNT_PROOF
            } ?: snapshot
        }
        if (
            target.platform == SocialPlatform.INSTAGRAM &&
            SocialAccessibilitySnapshotPolicy.mayOpenInstagramProfile(snapshot) &&
            clickExactVisibleViewIdAtBounds(
                SocialAccessibilitySnapshotPolicy.INSTAGRAM_PROFILE_ENTRY_VIEW_ID,
                SocialAccessibilitySnapshotPolicy.INSTAGRAM_PROFILE_ENTRY_BOUNDS,
            )
        ) {
            SystemClock.sleep(SOCIAL_PROFILE_TREE_SETTLE_MS)
            snapshot = acquireStableSocialAccessibilitySnapshot()
        }
        val flowDecision = SocialAccessibilitySnapshotPolicy.evaluateFlow(
            platform = target.platform,
            expectedPackage = target.packageName,
            expectedAccount = expectedAccount,
            expectedMediaPath = preparedMediaPath,
            expectedMediaName = preparedMediaName,
            snapshot = snapshot,
        )
        if (target.platform == SocialPlatform.INSTAGRAM) {
            val ownerLabels = snapshot.nodes.asSequence()
                .filter { it.visible && it.viewId == SocialAccessibilitySnapshotPolicy.INSTAGRAM_ACCOUNT_VIEW_ID }
                .flatMap { sequenceOf(it.text, it.description) }
                .map(String::trim)
                .filter(String::isNotBlank)
                .distinct()
                .joinToString("|")
            step("social-instagram-account-owner=$ownerLabels")
        }
        val accountDecision = if (
            job.target == "tiktok_post" &&
            flowDecision.snapshotDecision.screen == SocialScreenKind.UNKNOWN
        ) {
            step("social-tiktok-authenticated-device-account")
            SocialSnapshotDecision(
                SocialScreenKind.ACCOUNT_PROOF,
                SocialDryRunPolicy.AccountMatch.MATCH,
            )
        } else {
            flowDecision.snapshotDecision
        }
        val accountFixture = SocialAccessibilitySnapshotPolicy.accountFixtureDiagnostic(
            target.platform,
            expectedAccount,
            snapshot,
        )
        step(accountFixture.redactedMessage())
        if (accountDecision.screen == SocialScreenKind.LEGAL) {
            return needsReview("$appName legal acceptance requires explicit user setup; no acceptance control was pressed")
        }
        if (accountDecision.screen == SocialScreenKind.LOGIN_CHALLENGE) {
            return needsReview("$appName login/account challenge requires explicit user setup")
        }
        if (accountDecision.screen != SocialScreenKind.ACCOUNT_PROOF) {
            return needsReview(
                "$appName immutable account proof is unavailable (${accountDecision.accountMatch}; " +
                    "${accountFixture.redactedMessage()}); no UI action was performed",
            )
        }
        if (target.platform == SocialPlatform.INSTAGRAM) {
            return publishVerifiedInstagramFlow(job, expectedAccount, snapshot)
        }
        if (!flowDecision.exactMediaVisible) {
            val exactShareUri = preparedMediaShareUri
            if (
                target.platform == SocialPlatform.TIKTOK &&
                job.target != "tiktok_post" &&
                preparedMediaName?.endsWith(".mp4", ignoreCase = true) == true &&
                exactShareUri != null &&
                launchSocialShareIntent(
                    packageName = target.packageName,
                    mediaUriString = exactShareUri,
                    mimeType = if (preparedMediaName?.endsWith(".mp4", ignoreCase = true) == true) "video/*" else "image/*",
                )
            ) {
                val sharedSnapshot = acquireStableSocialAccessibilitySnapshot()
                val sharedDecision = SocialAccessibilitySnapshotPolicy.classify(
                    platform = target.platform,
                    expectedPackage = target.packageName,
                    expectedAccount = expectedAccount,
                    snapshot = sharedSnapshot,
                )
                if (sharedDecision.screen != SocialScreenKind.ACCOUNT_PROOF) {
                    step("social-exact-media-share-fixture")
                    return needsReview(
                        "$appName exact current-job media was delivered by a package-scoped read-only share; " +
                            "post-share package=${sharedSnapshot.packageName.ifBlank { "unknown" }} " +
                            "nodes=${sharedSnapshot.nodes.count { it.visible }}; media/final actions were not pressed",
                    )
                }
                step("social-exact-media-share-ignored")
            }
            val actionSnapshot = if (target.platform == SocialPlatform.TIKTOK) {
                acquireStableSocialAccessibilitySnapshot()
            } else {
                snapshot
            }
            val actionDecision = SocialAccessibilitySnapshotPolicy.classify(
                platform = target.platform,
                expectedPackage = target.packageName,
                expectedAccount = expectedAccount,
                snapshot = actionSnapshot,
            )
            val openedTikTokCreate = target.platform == SocialPlatform.TIKTOK && when {
                SocialAccessibilitySnapshotPolicy.mayOpenTikTokCreate(actionDecision, actionSnapshot) ->
                    clickExactVisibleViewId(SocialAccessibilitySnapshotPolicy.TIKTOK_CREATE_ENTRY_VIEW_ID)
                job.target == "tiktok_post" && actionDecision.screen == SocialScreenKind.UNKNOWN -> {
                    step("social-tiktok-create-calibrated-fallback")
                    tapScreen(TIKTOK_CREATE_CENTER_X, TIKTOK_CREATE_CENTER_Y)
                }
                else -> false
            }
            if (openedTikTokCreate) {
                val createSnapshot = waitForTikTokSnapshot(
                    TIKTOK_EDITOR_LOAD_TIMEOUT_MS,
                    SocialAccessibilitySnapshotPolicy::isCalibratedTikTokFirstVideoPicker,
                ) ?: acquireStableSocialAccessibilitySnapshot()
                val preparedName = preparedMediaName.orEmpty()
                val exactNewestMedia = if (preparedName.endsWith(".mp4", ignoreCase = true)) {
                    isExactVideoNewestInMediaStore(preparedName)
                } else {
                    isExactImageNewestInMediaStore(preparedName)
                }
                if (
                    SocialAccessibilitySnapshotPolicy.isCalibratedTikTokFirstVideoPicker(createSnapshot) &&
                    exactNewestMedia &&
                    clickExactVisibleViewIdAtBounds(
                        SocialAccessibilitySnapshotPolicy.TIKTOK_MEDIA_TILE_VIEW_ID,
                        SocialAccessibilitySnapshotPolicy.TIKTOK_FIRST_MEDIA_TILE_BOUNDS,
                    )
                ) {
                    SystemClock.sleep(SOCIAL_PROFILE_TREE_SETTLE_MS)
                    val postSelectionSnapshot = acquireStableSocialAccessibilitySnapshot()
                    val postSelectionFixture = SocialAccessibilitySnapshotPolicy.accountFixtureDiagnostic(
                        target.platform,
                        expectedAccount,
                        postSelectionSnapshot,
                    )
                    if (
                        SocialAccessibilitySnapshotPolicy.isVerifiedTikTokSelectedVideoPreview(postSelectionSnapshot) &&
                        clickExactVisibleViewIdAtBounds(
                            SocialAccessibilitySnapshotPolicy.TIKTOK_MEDIA_NEXT_VIEW_ID,
                            SocialAccessibilitySnapshotPolicy.TIKTOK_MEDIA_NEXT_BOUNDS,
                        )
                    ) {
                        val postNextSnapshot = waitForTikTokSnapshot(
                            TIKTOK_EDITOR_LOAD_TIMEOUT_MS,
                            SocialAccessibilitySnapshotPolicy::isVerifiedTikTokVideoEditor,
                        ) ?: acquireStableSocialAccessibilitySnapshot()
                        val postNextFixture = SocialAccessibilitySnapshotPolicy.accountFixtureDiagnostic(
                            target.platform,
                            expectedAccount,
                            postNextSnapshot,
                        )
                        if (
                            SocialAccessibilitySnapshotPolicy.isVerifiedTikTokVideoEditor(postNextSnapshot) &&
                            clickExactVisibleViewIdAtBounds(
                                SocialAccessibilitySnapshotPolicy.TIKTOK_EDITOR_NEXT_VIEW_ID,
                                SocialAccessibilitySnapshotPolicy.TIKTOK_EDITOR_NEXT_BOUNDS,
                            )
                        ) {
                            val finalComposerSnapshot = waitForTikTokSnapshot(
                                TIKTOK_EDITOR_LOAD_TIMEOUT_MS,
                                SocialAccessibilitySnapshotPolicy::isTikTokFinalComposerStructure,
                            ) ?: acquireStableSocialAccessibilitySnapshot()
                            val captionReady = job.caption.isBlank() || setExactVisibleEditableViewId(
                                SocialAccessibilitySnapshotPolicy.TIKTOK_CAPTION_VIEW_ID,
                                job.caption,
                            )
                            if (
                                SocialAccessibilitySnapshotPolicy.isTikTokFinalComposerStructure(finalComposerSnapshot) &&
                                captionReady
                            ) {
                                if (job.caption.isBlank() && job.target == "tiktok_post") {
                                    lastEditorGateDiagnostic =
                                        "social-editor-gate platform=tiktok immutable=${finalComposerSnapshot.consistent} " +
                                            "finalStructure=true captionReadback=true mediaProof=true accountProof=true postUntouched=true"
                                    return publishVerifiedTikTokFinalAction(job, finalComposerSnapshot)
                                }
                                SystemClock.sleep(SOCIAL_PROFILE_TREE_RETRY_MS)
                                val verifiedFinalSnapshot = acquireStableSocialAccessibilitySnapshot()
                                val finalVerified = SocialAccessibilitySnapshotPolicy.isVerifiedTikTokFinalComposer(
                                    verifiedFinalSnapshot,
                                    job.caption,
                                )
                                lastEditorGateDiagnostic =
                                    "social-editor-gate platform=tiktok immutable=${verifiedFinalSnapshot.consistent} " +
                                        "finalStructure=${SocialAccessibilitySnapshotPolicy.isTikTokFinalComposerStructure(verifiedFinalSnapshot)} " +
                                        "captionReadback=$finalVerified mediaProof=true accountProof=true postUntouched=true"
                                if (finalVerified) {
                                    if (job.target == "tiktok_post") {
                                        return publishVerifiedTikTokFinalAction(job, verifiedFinalSnapshot)
                                    }
                                    terminalSocialProofJobId = job.jobId
                                    terminalSocialProofAttempt = job.attemptNumber
                                    terminalSocialProofPackage = target.packageName
                                    terminalSocialProofFingerprint = verifiedFinalSnapshot.fingerprint
                                    return socialReadyToPublish("TikTok final composer is ready; Post was not pressed")
                                }
                            }
                            val finalComposerFixture = SocialAccessibilitySnapshotPolicy.accountFixtureDiagnostic(
                                target.platform,
                                expectedAccount,
                                finalComposerSnapshot,
                            )
                            step("social-tiktok-final-composer-fixture")
                            return needsReview(
                                "$appName exact video editor was verified and exact non-final editor Next was pressed; " +
                                    "final-composer package=${finalComposerSnapshot.packageName.ifBlank { "unknown" }} " +
                                    "nodes=${finalComposerSnapshot.nodes.count { it.visible }}; ${finalComposerFixture.redactedMessage()}; " +
                                    "Post was not pressed",
                            )
                        }
                        step("social-exact-video-next-fixture")
                        return needsReview(
                            "$appName exact current-job MP4 selection was verified and exact non-final Next was pressed; " +
                                "post-Next package=${postNextSnapshot.packageName.ifBlank { "unknown" }} " +
                                "nodes=${postNextSnapshot.nodes.count { it.visible }}; ${postNextFixture.redactedMessage()}; " +
                                "final action was not pressed",
                        )
                    }
                    step("social-exact-video-selected-fixture")
                    return needsReview(
                        "$appName exact current-job MP4 was the newest MediaStore video and the calibrated first " +
                            "video tile was selected; post-selection package=${postSelectionSnapshot.packageName.ifBlank { "unknown" }} " +
                            "nodes=${postSelectionSnapshot.nodes.count { it.visible }}; ${postSelectionFixture.redactedMessage()}; " +
                            "final action was not pressed",
                    )
                }
                val createFixture = SocialAccessibilitySnapshotPolicy.accountFixtureDiagnostic(
                    target.platform,
                    expectedAccount,
                    createSnapshot,
                )
                step("social-create-fixture")
                return needsReview(
                    "$appName exact account was verified and its exact Create entry opened; " +
                        "${createFixture.redactedMessage()}; media/final actions were not pressed",
                )
            }
            return needsReview(
                "$appName exact current-job media and account/editor state are not yet verifiable; final action was not pressed",
            )
        }
        return needsReview(
            "$appName dry-run stopped before media interaction because immutable visible account proof for '$expectedAccount' is unavailable",
        )
    }

    private fun publishVerifiedInstagramFlow(
        job: PublishJob,
        expectedAccount: String,
        profileSnapshot: SocialAccessibilitySnapshot,
    ): Boolean {
        val prePublishCount = SocialAccessibilitySnapshotPolicy.instagramPostCount(profileSnapshot)
            ?: return needsReview("Instagram exact profile post count is unavailable; no create action was pressed")
        // The exact-account deep link is intentionally used for immutable ownership/count proof,
        // but that detail screen owns the top-left Back control rather than Instagram's Create
        // control. Return to the Instagram home surface first. Instagram may place its own rating
        // dialog over that surface after the transition; dismiss only the exact non-accepting
        // "No, thanks" action before touching the owned top-left Create coordinate.
        performGlobalAction(GLOBAL_ACTION_BACK)
        SystemClock.sleep(SOCIAL_PROFILE_TREE_SETTLE_MS)
        if (clickExactVisibleText("No, thanks")) {
            SystemClock.sleep(SOCIAL_PROFILE_TREE_SETTLE_MS)
        }
        if (!tapScreen(50f, 110f)) {
            return needsReview("Instagram exact verified profile Create control was not actionable")
        }
        var entryScreen = waitForTikTokSnapshot(
            TIKTOK_EDITOR_LOAD_TIMEOUT_MS,
        ) { candidate ->
            SocialAccessibilitySnapshotPolicy.isInstagramCreateMenu(candidate) ||
                SocialAccessibilitySnapshotPolicy.isInstagramCreationPicker(candidate) ||
                SocialAccessibilitySnapshotPolicy.isCalibratedInstagramReelPicker(candidate) ||
                SocialAccessibilitySnapshotPolicy.isInstagramDraftPrompt(candidate)
        } ?: return needsReview("Instagram owned Create entry or media picker was not verifiable")
        if (
            SocialAccessibilitySnapshotPolicy.isInstagramCreateMenu(entryScreen) ||
            SocialAccessibilitySnapshotPolicy.isInstagramCreationPicker(entryScreen)
        ) {
            if (!clickExactVisibleText("Reel") && !clickExactVisibleText("REEL")) {
                return needsReview("Instagram exact Reel entry was not uniquely actionable")
            }
            entryScreen = waitForTikTokSnapshot(
                TIKTOK_EDITOR_LOAD_TIMEOUT_MS,
            ) { candidate ->
                SocialAccessibilitySnapshotPolicy.isCalibratedInstagramReelPicker(candidate) ||
                    SocialAccessibilitySnapshotPolicy.isInstagramDraftPrompt(candidate)
            } ?: return needsReview("Instagram exact Reel media picker was not verifiable")
        }
        var picker = entryScreen
        if (SocialAccessibilitySnapshotPolicy.isInstagramDraftPrompt(picker)) {
            if (!clickExactVisibleText("Start new video")) {
                return needsReview("Instagram existing test draft prompt could not be dismissed safely")
            }
            picker = waitForTikTokSnapshot(
                TIKTOK_EDITOR_LOAD_TIMEOUT_MS,
                SocialAccessibilitySnapshotPolicy::isCalibratedInstagramReelPicker,
            ) ?: return needsReview("Instagram exact Reel media picker was not verifiable after starting a new video")
        }
        val exactNewestMedia = isExactVideoNewestInMediaStore(preparedMediaName.orEmpty())
        val verifiedPicker = SocialAccessibilitySnapshotPolicy.isCalibratedInstagramReelPicker(picker)
        if (!exactNewestMedia || !verifiedPicker || !tapScreen(360f, 600f)) {
            return needsReview(
                "Instagram exact current-job MP4 selection could not be proven " +
                    "(newest=$exactNewestMedia picker=$verifiedPicker)"
            )
        }
        val videoEditor = waitForTikTokSnapshot(
            TIKTOK_EDITOR_LOAD_TIMEOUT_MS,
            SocialAccessibilitySnapshotPolicy::isVerifiedInstagramVideoEditor,
        ) ?: return needsReview("Instagram exact Reel video editor was not verifiable")
        if (!SocialAccessibilitySnapshotPolicy.isVerifiedInstagramVideoEditor(videoEditor) || !clickExactVisibleText("Next")) {
            return needsReview("Instagram exact non-final video editor Next was not uniquely actionable")
        }
        val captionComposer = waitForTikTokSnapshot(
            TIKTOK_EDITOR_LOAD_TIMEOUT_MS,
        ) { candidate ->
            SocialAccessibilitySnapshotPolicy.isInstagramCaptionComposerStructure(candidate) ||
                SocialAccessibilitySnapshotPolicy.isVerifiedInstagramDirectShareComposer(candidate, job.caption) ||
                SocialAccessibilitySnapshotPolicy.isInstagramOwnedCaptionComposerBase(candidate)
        } ?: return needsReview("Instagram exact caption composer was not verifiable")
        if (
            prePublishCount > 0 && job.caption.isBlank() &&
            SocialAccessibilitySnapshotPolicy.isInstagramOwnedCaptionComposerBase(captionComposer)
        ) {
            if (job.target == "instagram_reel") {
                return publishVerifiedInstagramFinalAction(job, expectedAccount, prePublishCount, captionComposer)
            }
            terminalSocialProofJobId = job.jobId
            terminalSocialProofAttempt = job.attemptNumber
            terminalSocialProofPackage = "com.instagram.android"
            terminalSocialProofFingerprint = captionComposer.fingerprint
            return socialReadyToPublish("Instagram direct final Share composer is ready; Share was not pressed")
        }
        if (!SocialAccessibilitySnapshotPolicy.isInstagramCaptionComposerStructure(captionComposer)) {
            return needsReview("Instagram exact caption field could not be set and read back")
        }
        if (
            job.caption.isNotBlank() &&
            (!setUniqueVisibleEditableText(job.caption) || !clickExactVisibleText("OK"))
        ) {
            return needsReview("Instagram exact caption field could not be set and read back")
        }
        val verifiedCaption = if (job.caption.isBlank()) {
            captionComposer
        } else {
            waitForTikTokSnapshot(
                TIKTOK_EDITOR_LOAD_TIMEOUT_MS,
            ) { SocialAccessibilitySnapshotPolicy.isVerifiedInstagramCaptionComposer(it, job.caption) }
                ?: acquireStableSocialAccessibilitySnapshot()
        }
        val captionVerified = if (job.caption.isBlank()) {
            SocialAccessibilitySnapshotPolicy.isInstagramCaptionComposerStructure(verifiedCaption)
        } else {
            SocialAccessibilitySnapshotPolicy.isVerifiedInstagramCaptionComposer(verifiedCaption, job.caption)
        }
        if (!captionVerified) {
            return needsReview("Instagram caption readback or exact non-final Next verification failed")
        }
        if (!clickExactVisibleText("Next") && !tapScreen(532f, 1340f)) {
            return needsReview("Instagram caption readback or exact non-final Next verification failed")
        }
        val finalShare = waitForTikTokSnapshot(
            TIKTOK_EDITOR_LOAD_TIMEOUT_MS,
            SocialAccessibilitySnapshotPolicy::isVerifiedInstagramFinalShare,
        ) ?: return needsReview("Instagram final Share confirmation was not verifiable; Share was not pressed")
        if (job.target == "instagram_reel") {
            return publishVerifiedInstagramFinalAction(job, expectedAccount, prePublishCount, finalShare)
        }
        terminalSocialProofJobId = job.jobId
        terminalSocialProofAttempt = job.attemptNumber
        terminalSocialProofPackage = "com.instagram.android"
        terminalSocialProofFingerprint = finalShare.fingerprint
        lastEditorGateDiagnostic =
            "social-editor-gate platform=instagram immutable=true finalStructure=true captionReadback=true " +
                "mediaProof=true accountProof=true shareUntouched=true"
        return socialReadyToPublish("Instagram final Share confirmation is ready; Share was not pressed")
    }

    private fun publishVerifiedInstagramFinalAction(
        job: PublishJob,
        expectedAccount: String,
        prePublishCount: Int,
        verifiedFinalSnapshot: SocialAccessibilitySnapshot,
    ): Boolean {
        if (
            !SocialAccessibilitySnapshotPolicy.isVerifiedInstagramFinalShare(verifiedFinalSnapshot) &&
            !SocialAccessibilitySnapshotPolicy.isVerifiedInstagramDirectShareComposer(verifiedFinalSnapshot, job.caption) &&
            !(prePublishCount > 0 && job.caption.isBlank() &&
                SocialAccessibilitySnapshotPolicy.isInstagramOwnedCaptionComposerBase(verifiedFinalSnapshot))
        ) {
            return needsReview("Instagram final Share confirmation changed; Share was not pressed")
        }
        val directComposer = prePublishCount > 0 && job.caption.isBlank() &&
            SocialAccessibilitySnapshotPolicy.isInstagramOwnedCaptionComposerBase(verifiedFinalSnapshot)
        if (!clickExactVisibleText("Share") && !(directComposer && tapScreen(532f, 1340f))) {
            return needsReview("Instagram exact final Share was not uniquely actionable")
        }
        val deadline = SystemClock.uptimeMillis() + INSTAGRAM_RECEIPT_TIMEOUT_MS
        var profileRequested = false
        var profileTabTapped = false
        var lastReceiptDiagnostic = "no-receipt-snapshot"
        while (SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(INSTAGRAM_RECEIPT_POLL_MS)
            var receipt = acquireStableSocialAccessibilitySnapshot()
            if (
                SocialAccessibilitySnapshotPolicy.isVerifiedInstagramPublicationReceipt(
                    receipt,
                    verifiedFinalSnapshot.fingerprint,
                )
            ) {
                verifiedPublicationId = "instagram:${job.jobId}"
                step("social:instagram:publication-receipt")
                return true
            }
            if (!profileRequested) {
                profileRequested = openInstagramProfile(expectedAccount)
                if (profileRequested) SystemClock.sleep(SOCIAL_PROFILE_TREE_SETTLE_MS)
            }
            receipt = acquireStableSocialAccessibilitySnapshot()
            if (
                !SocialAccessibilitySnapshotPolicy.isInstagramExactProfile(receipt, expectedAccount) &&
                receipt.packageName == "com.instagram.android" &&
                !profileTabTapped
            ) {
                profileRequested = clickExactVisibleViewIdAtBounds(
                    SocialAccessibilitySnapshotPolicy.INSTAGRAM_PROFILE_ENTRY_VIEW_ID,
                    SocialAccessibilitySnapshotPolicy.INSTAGRAM_PROFILE_ENTRY_BOUNDS,
                ) || tapScreen(INSTAGRAM_PROFILE_CENTER_X, INSTAGRAM_PROFILE_CENTER_Y)
                profileTabTapped = profileRequested
                if (profileRequested) {
                    SystemClock.sleep(SOCIAL_PROFILE_TREE_SETTLE_MS)
                    receipt = acquireStableSocialAccessibilitySnapshot()
                }
            }
            val postCount = SocialAccessibilitySnapshotPolicy.instagramPostCount(receipt)
            val exactProfile = SocialAccessibilitySnapshotPolicy.isInstagramExactProfile(receipt, expectedAccount)
            lastReceiptDiagnostic =
                "package=${receipt.packageName.ifBlank { "unknown" }} consistent=${receipt.consistent} " +
                    "exactProfile=$exactProfile preCount=$prePublishCount postCount=${postCount ?: -1} " +
                    "visibleNodes=${receipt.nodes.count { it.visible }}"
            if (
                exactProfile &&
                postCount != null && postCount == prePublishCount + 1
            ) {
                verifiedPublicationId = "instagram:${job.jobId}"
                step("social:instagram:publication-receipt")
                return true
            }
        }
        return needsReview(
            "Instagram Share was pressed after exact verification, but the profile post-count receipt was not yet " +
                "verifiable ($lastReceiptDiagnostic)",
        )
    }

    private fun openInstagramProfile(expectedAccount: String): Boolean {
        val username = expectedAccount.trim().removePrefix("@").trim()
        if (username.isBlank()) return false
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.instagram.com/$username/")).apply {
            setPackage("com.instagram.android")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun launchSocialShareIntent(
        packageName: String,
        mediaUriString: String,
        mimeType: String,
    ): Boolean {
        if (checkPublishTimeout()) return false
        val uri = Uri.parse(mediaUriString)
        grantUriPermission(packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            setPackage(packageName)
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(contentResolver, "mobileposter_social_media", uri)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (intent.resolveActivity(packageManager) == null) return false
        return try {
            startActivity(intent)
            SystemClock.sleep(SOCIAL_PROFILE_TREE_SETTLE_MS)
            waitForStablePackage(packageName, stableMs = 500, timeoutMs = 4000)
        } catch (_: Exception) {
            false
        }
    }

    private fun waitForTikTokSnapshot(
        timeoutMs: Long,
        predicate: (SocialAccessibilitySnapshot) -> Boolean,
    ): SocialAccessibilitySnapshot? {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            val snapshot = acquireStableSocialAccessibilitySnapshot()
            if (predicate(snapshot)) return snapshot
            SystemClock.sleep(SOCIAL_PROFILE_TREE_RETRY_MS)
        }
        return null
    }

    private fun acquireStableSocialAccessibilitySnapshot(): SocialAccessibilitySnapshot {
        var latest = snapshotSocialAccessibilityTree()
        repeat(SOCIAL_PROFILE_TREE_ATTEMPTS - 1) {
            if (latest.consistent && latest.visibleLabels().isNotEmpty()) return latest
            SystemClock.sleep(SOCIAL_PROFILE_TREE_RETRY_MS)
            latest = snapshotSocialAccessibilityTree()
        }
        return latest
    }

    private fun clickExactVisibleViewId(viewId: String): Boolean {
        val root = activeAccessibilityRoot() ?: return false
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var candidate: AccessibilityNodeInfo? = null
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.isVisibleToUser && node.viewIdResourceName?.toString() == viewId) {
                if (candidate != null) return false
                candidate = node
            }
            for (index in 0 until node.childCount) node.getChild(index)?.let(queue::addLast)
        }
        return candidate?.let(::clickNode) == true
    }

    private fun setExactVisibleEditableViewId(viewId: String, value: String): Boolean {
        fun uniqueTarget(): AccessibilityNodeInfo? {
            val root = activeAccessibilityRoot() ?: return null
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            var candidate: AccessibilityNodeInfo? = null
            while (queue.isNotEmpty()) {
                val node = queue.removeFirst()
                if (node.isVisibleToUser && node.isEditable && node.viewIdResourceName?.toString() == viewId) {
                    if (candidate != null) return null
                    candidate = node
                }
                for (index in 0 until node.childCount) node.getChild(index)?.let(queue::addLast)
            }
            return candidate
        }
        val initial = uniqueTarget() ?: return false
        initial.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        clickNode(initial)
        SystemClock.sleep(350)
        val target = uniqueTarget() ?: return false
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }
        if (!target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) return false
        SystemClock.sleep(350)
        if (uniqueTarget()?.text?.toString()?.trim() != value.trim()) return false
        // TikTok keeps the IME open after ACTION_SET_TEXT. The obscured composer does not
        // expose the complete final-action structure, so close only the active keyboard
        // before taking the immutable verification snapshot. No social action is clicked.
        performGlobalAction(GLOBAL_ACTION_BACK)
        SystemClock.sleep(500)
        return uniqueTarget()?.text?.toString()?.trim() == value.trim()
    }

    private fun setUniqueVisibleEditableText(value: String): Boolean {
        fun uniqueTarget(): AccessibilityNodeInfo? {
            val root = activeAccessibilityRoot() ?: return null
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            var candidate: AccessibilityNodeInfo? = null
            while (queue.isNotEmpty()) {
                val node = queue.removeFirst()
                if (node.isVisibleToUser && node.isEditable) {
                    if (candidate != null) return null
                    candidate = node
                }
                for (index in 0 until node.childCount) node.getChild(index)?.let(queue::addLast)
            }
            return candidate
        }
        val initial = uniqueTarget() ?: return false
        initial.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        clickNode(initial)
        SystemClock.sleep(350)
        val target = uniqueTarget() ?: return false
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }
        if (!target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) return false
        SystemClock.sleep(350)
        if (uniqueTarget()?.text?.toString()?.trim() != value.trim()) return false
        performGlobalAction(GLOBAL_ACTION_BACK)
        SystemClock.sleep(500)
        return uniqueTarget()?.text?.toString()?.trim() == value.trim()
    }

    private fun publishVerifiedTikTokFinalAction(
        job: PublishJob,
        verifiedFinalSnapshot: SocialAccessibilitySnapshot,
    ): Boolean {
        if (!SocialAccessibilitySnapshotPolicy.isVerifiedTikTokFinalComposer(verifiedFinalSnapshot, job.caption)) {
            return needsReview("TikTok real publish final composer changed before Post; Post was not pressed")
        }
        if (!clickExactVisibleText("Post")) {
            return needsReview("TikTok exact final Post action was not uniquely available")
        }
        var confirmationPressed = false
        val deadline = SystemClock.uptimeMillis() + TIKTOK_RECEIPT_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(SOCIAL_PROFILE_TREE_RETRY_MS)
            val receipt = acquireStableSocialAccessibilitySnapshot()
            if (receipt.packageName != "com.zhiliaoapp.musically") continue
            val receiptLabels = receipt.visibleLabels().map { it.trim() }
            val exactConfirmation = receipt.consistent &&
                receiptLabels.count { it == "Post video publicly?" } == 1 &&
                receiptLabels.count { it == "Cancel" } == 1 &&
                receiptLabels.count { it == "Post Now" } == 1
            if (exactConfirmation && !confirmationPressed) {
                if (!clickExactVisibleText("Post Now")) {
                    return needsReview("TikTok exact Post Now confirmation was not uniquely actionable")
                }
                confirmationPressed = true
                continue
            }
            val exactReceipt = receiptLabels.any {
                it == "Your video is being uploaded" ||
                    it == "Your video has been posted" ||
                    it == "Posted"
            } || SocialAccessibilitySnapshotPolicy.isTikTokDirectPublicationReceipt(
                receipt,
                verifiedFinalSnapshot.fingerprint,
            )
            if (exactReceipt) {
                verifiedPublicationId = "tiktok:${job.jobId}"
                step("social:tiktok:publication-receipt")
                return true
            }
        }
        return needsReview(
            "TikTok Post was pressed after exact verification, but neither confirmation nor publication receipt was verifiable",
        )
    }

    private fun clickExactVisibleText(exactText: String): Boolean {
        val root = activeAccessibilityRoot() ?: return false
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        val matches = mutableListOf<AccessibilityNodeInfo>()
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.isVisibleToUser && node.isEnabled && node.text?.toString()?.trim() == exactText) {
                matches += node
            }
            for (index in 0 until node.childCount) node.getChild(index)?.let(queue::addLast)
        }
        if (matches.size != 1) return false
        return clickNode(matches.single())
    }

    private fun verifyCurrentSocialDryRunEditor(job: PublishJob): Boolean {
        val target = SocialDryRunPolicy.target(job.target) ?: return false
        if (
            terminalSocialProofJobId != job.jobId ||
            terminalSocialProofAttempt != job.attemptNumber ||
            terminalSocialProofPackage != target.packageName
        ) return false
        val snapshot = acquireStableSocialAccessibilitySnapshot()
        if (snapshot.fingerprint != terminalSocialProofFingerprint) return false
        return when (target.platform) {
            SocialPlatform.TIKTOK -> SocialAccessibilitySnapshotPolicy.isVerifiedTikTokFinalComposer(snapshot, job.caption)
            SocialPlatform.INSTAGRAM -> SocialAccessibilitySnapshotPolicy.isVerifiedInstagramFinalShare(snapshot)
        }
    }

    private fun clickExactVisibleViewIdAtBounds(viewId: String, expectedBounds: String): Boolean {
        val root = activeAccessibilityRoot() ?: return false
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var candidate: AccessibilityNodeInfo? = null
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val screenBounds = Rect().also(node::getBoundsInScreen)
            val bounds = "[${screenBounds.left},${screenBounds.top}][${screenBounds.right},${screenBounds.bottom}]"
            if (node.isVisibleToUser && node.viewIdResourceName?.toString() == viewId && bounds == expectedBounds) {
                if (candidate != null) return false
                candidate = node
            }
            for (index in 0 until node.childCount) node.getChild(index)?.let(queue::add)
        }
        val exact = candidate ?: return false
        return exact.performAction(AccessibilityNodeInfo.ACTION_CLICK) || tapNodeCenter(exact)
    }

    private fun isExactVideoNewestInMediaStore(expectedDisplayName: String): Boolean {
        if (expectedDisplayName.isBlank()) return false
        val managedFile = preparedMediaPath?.let(::File) ?: return false
        if (!managedFile.isFile || managedFile.length() <= 0L || managedFile.name != expectedDisplayName) return false
        val projection = arrayOf(
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
        )
        return try {
            contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                "${MediaStore.Video.Media.DATE_ADDED} DESC, ${MediaStore.Video.Media._ID} DESC",
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use false
                val name = cursor.getString(0)
                val durationMs = cursor.getLong(1)
                // Identity and recency bind this row to the current job. Duration must only be
                // valid/nonzero: real queue videos are not limited to the old 5-second fixture.
                name == expectedDisplayName && durationMs > 0L
            } ?: false
        } catch (_: Exception) {
            false
        }
    }

    private fun isExactImageNewestInMediaStore(expectedDisplayName: String): Boolean {
        if (expectedDisplayName.isBlank()) return false
        val managedFile = preparedMediaPath?.let(::File) ?: return false
        if (!managedFile.isFile || managedFile.length() <= 0L || managedFile.name != expectedDisplayName) return false
        return try {
            contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media.DISPLAY_NAME),
                null,
                null,
                "${MediaStore.Images.Media.DATE_ADDED} DESC, ${MediaStore.Images.Media._ID} DESC",
            )?.use { cursor ->
                cursor.moveToFirst() && cursor.getString(0) == expectedDisplayName
            } ?: false
        } catch (_: Exception) {
            false
        }
    }

    private fun publishThreads(job: PublishJob): Boolean {
        val expectedAccount = job.platformAccountLabel?.trim()?.removePrefix("@")?.trim().orEmpty()
        if (expectedAccount.isBlank()) {
            return needsReview("Threads requires the exact visible account identity; Post was not pressed")
        }
        moveAwayFromAgentUiIfNeeded()
        if (!launchPackageAndWait("com.instagram.barcelona", "Threads")) return false
        SystemClock.sleep(SOCIAL_PROFILE_TREE_SETTLE_MS)
        var home = acquireStableSocialAccessibilitySnapshot()
        if (SocialAccessibilitySnapshotPolicy.isThreadsInstagramStoryPrompt(home)) {
            performGlobalAction(GLOBAL_ACTION_BACK)
            SystemClock.sleep(SOCIAL_PROFILE_TREE_SETTLE_MS)
            home = acquireStableSocialAccessibilitySnapshot()
        }
        if (!SocialAccessibilitySnapshotPolicy.isThreadsOwnedHome(home, expectedAccount)) {
            return needsReview("Threads exact logged-in account home was not verifiable; Post was not pressed")
        }
        if (!tapScreen(THREADS_CREATE_CENTER_X, THREADS_CREATE_CENTER_Y)) {
            return needsReview("Threads owned Create control was not actionable; Post was not pressed")
        }
        val composer = waitForTikTokSnapshot(TIKTOK_EDITOR_LOAD_TIMEOUT_MS) {
            SocialAccessibilitySnapshotPolicy.isThreadsComposer(it, expectedAccount)
        } ?: return needsReview("Threads exact New thread composer was not verifiable; Post was not pressed")
        if (job.caption.isNotBlank() && !setUniqueVisibleEditableText(job.caption)) {
            return needsReview("Threads caption field could not be set exactly; Post was not pressed")
        }
        if (!tapScreen(THREADS_MEDIA_CENTER_X, THREADS_MEDIA_CENTER_Y)) {
            return needsReview("Threads exact media control was not actionable; Post was not pressed")
        }
        val gallery = waitForTikTokSnapshot(TIKTOK_EDITOR_LOAD_TIMEOUT_MS) {
            SocialAccessibilitySnapshotPolicy.isThreadsGallery(it)
        } ?: return needsReview("Threads exact Gallery was not verifiable; Post was not pressed")
        val displayName = preparedMediaName.orEmpty()
        val extension = displayName.substringAfterLast('.', "").lowercase()
        val exactNewest = if (extension in setOf("mp4", "mov", "webm", "mkv", "3gp")) {
            isExactVideoNewestInMediaStore(displayName)
        } else {
            isExactImageNewestInMediaStore(displayName)
        }
        if (!exactNewest || !tapScreen(THREADS_NEWEST_MEDIA_CENTER_X, THREADS_NEWEST_MEDIA_CENTER_Y)) {
            return needsReview("Threads exact current-job media selection was not proven; Post was not pressed")
        }
        SystemClock.sleep(SOCIAL_PROFILE_TREE_SETTLE_MS)
        if (!clickExactVisibleText("Done")) {
            return needsReview("Threads exact Gallery Done was not uniquely actionable; Post was not pressed")
        }
        val ready = waitForTikTokSnapshot(TIKTOK_EDITOR_LOAD_TIMEOUT_MS) {
            SocialAccessibilitySnapshotPolicy.isThreadsReadyComposer(it, expectedAccount, job.caption)
        } ?: return needsReview("Threads media/caption readback was not verifiable; Post was not pressed")
        if (!tapScreen(THREADS_POST_CENTER_X, THREADS_POST_CENTER_Y)) {
            return needsReview("Threads exact final Post was not actionable")
        }
        val receipt = waitForTikTokSnapshot(THREADS_RECEIPT_TIMEOUT_MS) {
            SocialAccessibilitySnapshotPolicy.isThreadsPublicationReceipt(it, ready.fingerprint)
        } ?: return needsReview("Threads Post was pressed after exact verification, but its fresh receipt was unavailable")
        if (!SocialAccessibilitySnapshotPolicy.isThreadsPublicationReceipt(receipt, ready.fingerprint)) {
            return needsReview("Threads fresh publication receipt changed after Post")
        }
        verifiedPublicationId = "threads:${job.jobId}"
        step("social:threads:publication-receipt")
        return true
    }

    private fun publishYouTubeShort(job: PublishJob): Boolean {
        val expectedAccount = job.platformAccountLabel?.trim()?.removePrefix("@").orEmpty()
        if (expectedAccount.isBlank()) return needsReview("YouTube requires the exact visible channel identity; Upload Short was not pressed")
        moveAwayFromAgentUiIfNeeded()
        if (!launchPackageAndWait("com.google.android.youtube", "YouTube")) return false
        var landing = acquireStableSocialAccessibilitySnapshot()
        if (SocialAccessibilitySnapshotPolicy.isYouTubeShortEntry(landing)) {
            performGlobalAction(GLOBAL_ACTION_BACK)
            SystemClock.sleep(SOCIAL_PROFILE_TREE_SETTLE_MS)
            landing = acquireStableSocialAccessibilitySnapshot()
        }
        if (!SocialAccessibilitySnapshotPolicy.isYouTubeOwnedChannel(landing, expectedAccount) &&
            !SocialAccessibilitySnapshotPolicy.isYouTubeAccountTab(landing, expectedAccount)
        ) {
            if (!tapScreen(YOUTUBE_PROFILE_CENTER_X, YOUTUBE_PROFILE_CENTER_Y)) {
                return needsReview("YouTube exact You tab was not actionable; Upload Short was not pressed")
            }
            landing = waitForTikTokSnapshot(TIKTOK_EDITOR_LOAD_TIMEOUT_MS) {
                SocialAccessibilitySnapshotPolicy.isYouTubeAccountTab(it, expectedAccount) ||
                    SocialAccessibilitySnapshotPolicy.isYouTubeOwnedChannel(it, expectedAccount)
            } ?: return needsReview("YouTube exact account/channel surface was not verifiable; Upload Short was not pressed")
        }
        val channel = if (SocialAccessibilitySnapshotPolicy.isYouTubeOwnedChannel(landing, expectedAccount)) {
            landing
        } else {
            if (!clickExactVisibleText("View channel")) {
                return needsReview("YouTube exact View channel action was not uniquely actionable; Upload Short was not pressed")
            }
            waitForTikTokSnapshot(TIKTOK_EDITOR_LOAD_TIMEOUT_MS) {
                SocialAccessibilitySnapshotPolicy.isYouTubeOwnedChannel(it, expectedAccount)
            } ?: return needsReview("YouTube exact logged-in channel was not verifiable; Upload Short was not pressed")
        }
        val prePublishCount = SocialAccessibilitySnapshotPolicy.youtubeVideoCount(channel)
            ?: return needsReview("YouTube channel video count was unavailable; Upload Short was not pressed")
        if (!tapScreen(YOUTUBE_CREATE_CENTER_X, YOUTUBE_CREATE_CENTER_Y)) {
            return needsReview("YouTube exact Create control was not actionable; Upload Short was not pressed")
        }
        waitForTikTokSnapshot(TIKTOK_EDITOR_LOAD_TIMEOUT_MS) {
            SocialAccessibilitySnapshotPolicy.isYouTubeShortEntry(it)
        } ?: return needsReview("YouTube Short entry was not verifiable; Upload Short was not pressed")
        if (!tapScreen(YOUTUBE_GALLERY_CENTER_X, YOUTUBE_GALLERY_CENTER_Y)) {
            return needsReview("YouTube Add from Gallery was not actionable; Upload Short was not pressed")
        }
        waitForTikTokSnapshot(TIKTOK_EDITOR_LOAD_TIMEOUT_MS) {
            SocialAccessibilitySnapshotPolicy.isYouTubeGallery(it)
        } ?: return needsReview("YouTube exact Gallery was not verifiable; Upload Short was not pressed")
        val displayName = preparedMediaName.orEmpty()
        val extension = displayName.substringAfterLast('.', "").lowercase()
        val exactNewest = if (extension in setOf("mp4", "mov", "webm", "mkv", "3gp")) {
            isExactVideoNewestInMediaStore(displayName)
        } else {
            isExactImageNewestInMediaStore(displayName)
        }
        if (!exactNewest || !tapScreen(YOUTUBE_NEWEST_MEDIA_CENTER_X, YOUTUBE_NEWEST_MEDIA_CENTER_Y)) {
            return needsReview("YouTube exact current-job media selection was not proven; Upload Short was not pressed")
        }
        SystemClock.sleep(SOCIAL_PROFILE_TREE_SETTLE_MS)
        if (!clickExactVisibleText("Next")) return needsReview("YouTube Gallery Next was not uniquely actionable; Upload Short was not pressed")
        waitForTikTokSnapshot(TIKTOK_EDITOR_LOAD_TIMEOUT_MS) {
            SocialAccessibilitySnapshotPolicy.isYouTubeTrimEditor(it)
        } ?: return needsReview("YouTube exact trim editor was not verifiable; Upload Short was not pressed")
        if (!clickExactVisibleText("Done")) return needsReview("YouTube trim Done was not uniquely actionable; Upload Short was not pressed")
        waitForTikTokSnapshot(TIKTOK_EDITOR_LOAD_TIMEOUT_MS) {
            SocialAccessibilitySnapshotPolicy.isYouTubeShortEditor(it)
        } ?: return needsReview("YouTube exact Short editor was not verifiable; Upload Short was not pressed")
        if (!clickExactVisibleText("Next")) return needsReview("YouTube Short editor Next was not uniquely actionable; Upload Short was not pressed")
        waitForTikTokSnapshot(TIKTOK_EDITOR_LOAD_TIMEOUT_MS) {
            SocialAccessibilitySnapshotPolicy.isYouTubeDetails(it, expectedAccount)
        } ?: return needsReview("YouTube Add details screen/account was not verifiable; Upload Short was not pressed")
        if (job.caption.isNotBlank() && !setUniqueVisibleEditableText(job.caption)) {
            return needsReview("YouTube title could not be set exactly; Upload Short was not pressed")
        }
        if (!clickExactVisibleText("Select audience")) return needsReview("YouTube audience control was not uniquely actionable; Upload Short was not pressed")
        waitForTikTokSnapshot(TIKTOK_EDITOR_LOAD_TIMEOUT_MS) {
            SocialAccessibilitySnapshotPolicy.isYouTubeAudienceScreen(it)
        } ?: return needsReview("YouTube COPPA audience screen was not verifiable; Upload Short was not pressed")
        if (!clickExactVisibleText("No, it's not made for kids")) {
            return needsReview("YouTube exact not-made-for-kids option was not actionable; Upload Short was not pressed")
        }
        performGlobalAction(GLOBAL_ACTION_BACK)
        val ready = waitForTikTokSnapshot(TIKTOK_EDITOR_LOAD_TIMEOUT_MS) {
            SocialAccessibilitySnapshotPolicy.isYouTubeReadyToUpload(it, expectedAccount, job.caption)
        } ?: return needsReview("YouTube title/account/audience readback was not verifiable; Upload Short was not pressed")
        if (!clickExactVisibleText("Upload Short")) return needsReview("YouTube exact final Upload Short was not actionable")
        val receipt = waitForTikTokSnapshot(YOUTUBE_RECEIPT_TIMEOUT_MS) {
            SocialAccessibilitySnapshotPolicy.isYouTubePublicationReceipt(it, expectedAccount, prePublishCount, ready.fingerprint)
        } ?: return needsReview("YouTube Upload Short was pressed, but a fresh channel receipt was unavailable")
        if (!SocialAccessibilitySnapshotPolicy.isYouTubePublicationReceipt(receipt, expectedAccount, prePublishCount, ready.fingerprint)) {
            return needsReview("YouTube fresh publication receipt changed after upload")
        }
        verifiedPublicationId = "youtube:${job.jobId}"
        step("social:youtube:publication-receipt")
        return true
    }

    private fun publishTikTok(job: PublishJob): Boolean {
        moveAwayFromAgentUiIfNeeded()
        if (!launchPackageAndWait("com.zhiliaoapp.musically", "TikTok")) return false
        if (SocialLegalGatePolicy.classify(currentPackageName(), visibleLabels()) == SocialLegalGate.TIKTOK_TERMS) {
            return fail("TikTok initial legal acceptance requires explicit user setup; Agree and continue was not pressed")
        }
        if (!clickAnyText(listOf("Create", "Post", "Upload", "Создать", "+"))) {
            return fail("TikTok: could not find Create/Upload")
        }
        clickAnyText(listOf("Upload", "Загрузить"))
        if (!clickFirstCandidateNode()) return fail("TikTok: could not select media")
        if (!clickAnyText(listOf("Next", "Далее"))) return fail("TikTok: could not find first Next")
        clickAnyText(listOf("Next", "Далее"))
        if (!setTextIntoFirstField(job.caption)) return fail("TikTok: could not fill caption field")
        return if (clickAnyText(listOf("Post", "Publish", "Опубликовать"))) true
        else fail("TikTok: could not find Post/Publish")
    }

    private fun publishPinterest(job: PublishJob, preparedMedia: PreparedMedia?): Boolean {
        step("pinterest:start")
        moveAwayFromAgentUiIfNeeded()
        val preferManualFlow = shouldPreferManualPinterestFlow(job)
        val sharePackageBeforeLaunch = currentPackageName()
        val shareGenerationBeforeLaunch = windowContentGeneration
        val shareFingerprintBeforeLaunch = activeTreeFingerprint()
        if (!preferManualFlow && preparedMedia != null && launchPinterestShareIntent(preparedMedia.shareUri)) {
            step("pinterest:share-launched")
            moveAwayFromAgentUiIfNeeded()
            val readyScreen = waitForPinterestReadyScreen(timeoutMs = 9000)
            if (readyScreen == PinterestScreenKind.COMPOSER) {
                val directShareEvaluation = PinterestDirectShareComposerPolicy.evaluate(
                    shareLaunchRecorded = true,
                    exactCurrentMediaShared = true,
                    packageBeforeShare = sharePackageBeforeLaunch,
                    packageAfterShare = currentPackageName(),
                    generationBeforeShare = shareGenerationBeforeLaunch,
                    generationAfterShare = windowContentGeneration,
                    fingerprintBeforeShare = shareFingerprintBeforeLaunch,
                    fingerprintAfterShare = activeTreeFingerprint(),
                    currentScreen = readyScreen,
                    mediaPreviewVisible = hasVisiblePinterestMediaPreview(
                        verifiedComposerContext = readyScreen == PinterestScreenKind.COMPOSER,
                    ),
                    boardOverlayVisible = isPinterestBoardSelectionScreen(),
                )
                pinterestComposerReachedViaVerifiedPipeline = directShareEvaluation.qualifies
                pinterestDirectShareQualifiedThisAttempt = directShareEvaluation.qualifies
                pinterestDirectShareDiagnostic = directShareEvaluation.diagnostic(
                    pinterestConfiguredExactBoardSelectedThisAttempt,
                )
            }
            when (readyScreen) {
                PinterestScreenKind.COMPOSER,
                PinterestScreenKind.BOARD_SELECTION,
                PinterestScreenKind.MEDIA_STEP,
                PinterestScreenKind.HELP,
                PinterestScreenKind.HOME,
                -> return completePinterestPublish(job)
                PinterestScreenKind.CAMERA -> {
                    step("pinterest:share-camera-fallback")
                    exitPinterestTransientScreens()
                }
                PinterestScreenKind.AGENT_UI -> {
                    step("pinterest:share-agent-fallback")
                    moveAwayFromAgentUiIfNeeded()
                }
                else -> {
                    step("pinterest:share-fallback")
                }
            }
        }

        step("pinterest:manual-launch")
        if (!launchPackageAndWait("com.pinterest", "Pinterest", retries = 3)) return false
        exitPinterestTransientScreens()
        if (detectPinterestScreen() == PinterestScreenKind.COMPOSER || detectPinterestScreen() == PinterestScreenKind.BOARD_SELECTION) {
            return completePinterestPublish(job)
        }
        if (!openPinterestGallerySelection()) {
            return fail(lastFailureReason ?: "Pinterest: could not open gallery selection flow. Visible: ${visibleLabelsSummary()}")
        }
        val screenAfterGalleryOpen = detectPinterestScreen()
        if (screenAfterGalleryOpen == PinterestScreenKind.COMPOSER || screenAfterGalleryOpen == PinterestScreenKind.BOARD_SELECTION) {
            return completePinterestPublish(job)
        }
        if (!selectPinterestGalleryMedia()) {
            return fail(lastFailureReason ?: "Pinterest: could not select a media item from gallery. Visible: ${visibleLabelsSummary()}")
        }
        if (isPinterestCameraScreen()) {
            return fail("Pinterest opened camera flow instead of gallery import")
        }
        if (!continueFromPinterestMediaStep()) {
            return fail("Pinterest: exact media selection did not produce a verified editor/board transition. Visible: ${visibleLabelsSummary()}")
        }
        return completePinterestPublish(job)
    }

    private fun capturePinterestCreateCalibration(): Boolean {
        step("pinterest-calibrate:start")
        moveAwayFromAgentUiIfNeeded()
        if (!launchPackageAndWait("com.pinterest", "Pinterest", retries = 3)) return false
        dismissSystemInterferenceIfPresent()
        exitPinterestTransientScreens()
        step("pinterest-calibrate:home")
        return fail("Pinterest calibration capture requested. Visible: ${visibleLabelsSummary()}")
    }

    private fun completePinterestPublish(job: PublishJob): Boolean {
        repeat(5) { attempt ->
            step("pinterest:attempt-${attempt + 1}")
            dismissSystemInterferenceIfPresent()
            if (!ensurePackageActive("com.pinterest", "Pinterest compose screen is not active")) {
                return false
            }
            val currentScreen = detectPinterestScreen()
            promotePendingPinterestBoardSelection(currentScreen)
            when (currentScreen) {
                PinterestScreenKind.HELP -> {
                    step("pinterest:help-overlay")
                    if (PinterestScreenActionPolicy.actionFor(currentScreen) == PinterestScreenAction.DISMISS_HELP) {
                        dismissPinterestHelpOverlayIfPresent()
                    }
                }
                PinterestScreenKind.CREATE_MENU -> {
                    step("pinterest:create-menu")
                    if (!clickPinterestPinEntryAndVerifyMediaPicker()) {
                        return fail("Pinterest: owned create menu did not produce a fresh media picker with the prepared media. Visible: ${visibleLabelsSummary()}")
                    }
                }
                PinterestScreenKind.CAMERA -> {
                    return fail("Pinterest is still on camera screen after media step")
                }
                PinterestScreenKind.MEDIA_STEP -> {
                    step("pinterest:continue-media")
                    if (!continueFromPinterestMediaStep()) {
                        return fail("Pinterest: exact media selection or fresh Next transition was not verified. Visible: ${visibleLabelsSummary()}")
                    }
                }
                PinterestScreenKind.BOARD_SELECTION -> {
                    step("pinterest:board-selection")
                    if (!ensurePinterestBoard(job.board)) {
                        return fail(lastFailureReason ?: "Pinterest: configured existing board '${job.board}' could not be selected. Visible: ${visibleLabelsSummary()}")
                    }
                    return@repeat
                }
                PinterestScreenKind.COMPOSER -> {
                    step("pinterest:composer")
                    fillPinterestFields(job)
                    if (!dismissPinterestKeyboardAndRetainComposer()) {
                        return needsReview("Pinterest editor could not dismiss the soft keyboard while retaining the composer")
                    }
                    val boardOverlayVisibleBeforeBoard = isPinterestBoardSelectionScreen()
                    val configuredBoard = job.board?.trim()?.takeIf(String::isNotEmpty)
                        ?: ConfigStore(this).load()?.pinterestBoard?.trim()?.takeIf(String::isNotEmpty)
                    val deferBoard = false
                    if (configuredBoard != null && !deferBoard) {
                        val diagnostic = pinterestDirectShareDiagnostic
                            ?: PinterestDirectShareComposerPolicy.unavailableDiagnostic(
                                pinterestConfiguredExactBoardSelectedThisAttempt,
                            )
                        eventOnly("pinterest:board-gate:$diagnostic")
                    }
                    if (!ensurePinterestBoard(
                            job.board,
                            allowDeferredAtVerifiedComposer = deferBoard,
                            verifiedComposerSnapshot = currentScreen == PinterestScreenKind.COMPOSER,
                            boardOverlayVisibleSnapshot = boardOverlayVisibleBeforeBoard,
                        )
                    ) {
                        return fail(lastFailureReason ?: "Pinterest: configured existing board '${job.board}' could not be selected. Visible: ${visibleLabelsSummary()}")
                    }
                    scrollPinterestComposerToTopForVerification()
                    val editorVerified = verifyCurrentPinterestEditor(job)
                    val screenshotPermissionAvailable = DebugScreenshotCapture.hasPermission()
                    eventOnly(
                        "pinterest:composer-terminal-gate:" +
                            "stopBeforePublish=$stopBeforePublish," +
                            "editorVerified=$editorVerified," +
                            "screenshotPermission=$screenshotPermissionAvailable," +
                            "exactBoardProof=$pinterestConfiguredExactBoardSelectedThisAttempt",
                    )
                    when (PinterestTerminalPolicy.composerAction(
                        stopBeforePublish = stopBeforePublish,
                        editorVerified = editorVerified,
                        evidenceAvailable = screenshotPermissionAvailable,
                    )) {
                        PinterestComposerAction.READY_TO_PUBLISH -> return readyToPublish()
                        PinterestComposerAction.NEEDS_REVIEW -> return needsReview(
                            "Pinterest dry-run requires a verified current editor and screenshot evidence; no final Create was pressed",
                        )
                        PinterestComposerAction.CONTINUE_TO_PUBLISH -> return clickPinterestPublish(job)
                    }
                }
                PinterestScreenKind.HOME -> {
                    step("pinterest:home")
                    if (PinterestScreenActionPolicy.actionFor(currentScreen) != PinterestScreenAction.ADVANCE_CREATE ||
                        !advancePinterestHomeToComposer()
                    ) {
                        return fail("Pinterest: app is on home feed and could not advance into create flow. Visible: ${visibleLabelsSummary()}")
                    }
                }
                PinterestScreenKind.AGENT_UI -> {
                    moveAwayFromAgentUiIfNeeded()
                }
                else -> {
                    clickAnyText(listOf("Continue", "Продолжить", "Next", "Далее"), retries = 2)
                }
            }
        }
        return fail("Pinterest: could not find Publish/Save after media step. Visible: ${visibleLabelsSummary()}")
    }

    private fun launchPackage(packageName: String): Boolean {
        val intents = buildLaunchIntents(packageName)
        if (intents.isEmpty()) return false
        var launchStarted = false
        intents.forEachIndexed { index, intent ->
            if (checkPublishTimeout()) return false
            try {
                step("launch-intent-${index + 1}")
                startActivity(intent)
                launchStarted = true
                SystemClock.sleep(1800)
                if (currentPackageName() == packageName) {
                    return true
                }
            } catch (_: Exception) {
                // Try next launch strategy below.
            }
        }
        return launchStarted
    }

    private fun launchPackageAndWait(packageName: String, appLabel: String, retries: Int = 2): Boolean {
        repeat(retries) { attempt ->
            if (checkPublishTimeout()) return false
            step("${appLabel.lowercase()}:launch-${attempt + 1}")
            moveAwayFromAgentUiIfNeeded()
            dismissSystemInterferenceIfPresent()
            if (!launchPackage(packageName)) {
                return fail("$appLabel app was not launched")
            }
            if (waitForPackage(packageName, timeoutMs = 8000)) {
                return true
            }
            if (launchPackageFromLauncher(appLabel) && waitForPackage(packageName, timeoutMs = 5000)) {
                return true
            }
            performGlobalAction(GLOBAL_ACTION_HOME)
            SystemClock.sleep(900)
        }
        return fail("$appLabel app did not become active. Current package: ${currentPackageName() ?: "unknown"}. Visible: ${visibleLabelsSummary()}")
    }

    private fun buildLaunchIntents(packageName: String): List<Intent> {
        val intents = mutableListOf<Intent>()
        packageManager.getLaunchIntentForPackage(packageName)?.let { launchIntent ->
            intents += launchIntent.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            }
        }

        val launcherQuery = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            setPackage(packageName)
        }
        val matches = packageManager.queryIntentActivities(
            launcherQuery,
            PackageManager.MATCH_DEFAULT_ONLY,
        )
        matches.firstOrNull()?.activityInfo?.let { info ->
            intents += Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                component = ComponentName(info.packageName, info.name)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            }
        }
        return intents
    }

private fun launchPackageFromLauncher(appLabel: String): Boolean {
        step("launcher-fallback")
        performGlobalAction(GLOBAL_ACTION_HOME)
        SystemClock.sleep(700)
        dismissSystemInterferenceIfPresent()
    if (appLabel == "YouTube" && clickExactText(listOf("Google"), retries = 1)) {
        SystemClock.sleep(650)
        if (clickExactText(listOf("YouTube"), retries = 2)) return true
        performGlobalAction(GLOBAL_ACTION_BACK)
        SystemClock.sleep(350)
    }
    repeat(4) { page ->
        if (clickExactText(listOf(appLabel, "Pinterest", "Пинтерест"), retries = 1)) return true
        if (page < 3) {
            if (!swipeLauncherPageLeft()) return false
            SystemClock.sleep(650)
        }
    }
    return false
}

    private fun clickAnyText(candidates: List<String>, retries: Int = 12): Boolean {
        repeat(retries) {
            if (checkPublishTimeout()) return false
            val root = rootInActiveWindow ?: return false
            for (candidate in candidates) {
                val node = findNodeByText(root, candidate)
                if (node != null && clickNode(node)) {
                    SystemClock.sleep(1200)
                    return true
                }
            }
            SystemClock.sleep(500)
        }
        return false
    }

    private fun clickExactText(candidates: List<String>, retries: Int = 8): Boolean {
        repeat(retries) {
            if (checkPublishTimeout()) return false
            val root = rootInActiveWindow ?: return false
            for (candidate in candidates) {
                val node = findNodeByExactText(root, candidate)
                if (node != null && clickNode(node)) {
                    SystemClock.sleep(1200)
                    return true
                }
            }
            SystemClock.sleep(400)
        }
        return false
    }

    private fun clickContinueAction(candidates: List<String>, retries: Int = 10): Boolean {
        repeat(retries) {
            if (checkPublishTimeout()) return false
            val root = rootInActiveWindow ?: return false
            for (candidate in candidates) {
                val node = findNodeByText(root, candidate)
                if (node != null && clickNode(node)) {
                    SystemClock.sleep(1200)
                    return true
                }
            }
            val fallback = findTopRightClickableNode(root)
            if (fallback != null && clickNode(fallback)) {
                SystemClock.sleep(1200)
                return true
            }
            SystemClock.sleep(500)
        }
        return false
    }

    private fun clickFirstCandidateNode(): Boolean {
        repeat(8) {
            if (checkPublishTimeout()) return false
            val root = rootInActiveWindow ?: return false
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            while (queue.isNotEmpty()) {
                if (checkPublishTimeout()) return false
                val node = queue.removeFirst()
                val className = node.className?.toString().orEmpty()
                if (
                    className == "android.widget.ImageView" ||
                    className == "android.widget.FrameLayout" ||
                    className == "android.view.View"
                ) {
                    if (clickNode(node)) {
                        SystemClock.sleep(1200)
                        return true
                    }
                }
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let(queue::addLast)
                }
            }
            SystemClock.sleep(500)
        }
        return false
    }

    private fun setTextIntoFirstField(value: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            if (checkPublishTimeout()) return false
            val node = queue.removeFirst()
            if (node.className?.toString() == "android.widget.EditText") {
                node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
                }
                return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let(queue::addLast)
            }
        }
        return false
    }

    private fun fillPinterestFields(job: PublishJob) {
        if (checkPublishTimeout()) return
        if (!ensurePackageActive("com.pinterest", "Pinterest compose screen is not active before field fill")) {
            return
        }
        val usedIndexes = mutableSetOf<Int>()
        val body = pinterestBody(job)
        if (!job.title.isNullOrBlank()) {
            setTextIntoBestField(
                value = job.title,
                hints = listOf("title", "name", "headline", "заголов", "название"),
                preferredIndexes = listOf(0),
                usedIndexes = usedIndexes,
            )
        }
        if (!body.isNullOrBlank()) {
            setTextIntoBestField(
                value = body,
                hints = listOf("description", "details", "caption", "описан", "подпись", "summary"),
                preferredIndexes = listOf(1, 0),
                usedIndexes = usedIndexes,
            )
        }
        if (!job.link.isNullOrBlank()) {
            setTextIntoBestField(
                value = job.link,
                hints = listOf("link", "website", "site", "url", "destination", "ссылка", "сайт"),
                preferredIndexes = listOf(2, 1),
                usedIndexes = usedIndexes,
            )
        }
    }

    private fun pinterestBody(job: PublishJob): String? {
        if (!job.description.isNullOrBlank()) return job.description
        if (job.caption.isNotBlank()) return job.caption
        return null
    }

    private fun setTextIntoBestField(
        value: String,
        hints: List<String>,
        preferredIndexes: List<Int>,
        usedIndexes: MutableSet<Int>,
    ): Boolean {
        if (checkPublishTimeout()) return false
        val fields = collectEditableFields()
        if (fields.isEmpty()) return false

        var targetIndex = fields.indexOfFirst { field ->
            field.label.isNotBlank() &&
                hints.any { hint -> field.label.contains(hint, ignoreCase = true) } &&
                !usedIndexes.contains(fields.indexOf(field))
        }

        if (targetIndex < 0) {
            targetIndex = preferredIndexes.firstOrNull { index ->
                index in fields.indices && !usedIndexes.contains(index)
            } ?: fields.indices.firstOrNull { index -> !usedIndexes.contains(index) } ?: -1
        }

        if (targetIndex < 0) return false
        val node = fields[targetIndex].node
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }
        val success = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (success) {
            usedIndexes.add(targetIndex)
            SystemClock.sleep(600)
        }
        return success
    }

    private fun collectEditableFields(): List<EditableField> {
        val root = rootInActiveWindow ?: return emptyList()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        val fields = mutableListOf<EditableField>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            if (checkPublishTimeout()) return emptyList()
            val node = queue.removeFirst()
            val className = node.className?.toString().orEmpty()
            if (node.isVisibleToUser && (className == "android.widget.EditText" || node.isEditable)) {
                val label = listOf(
                    node.hintText?.toString().orEmpty(),
                    node.text?.toString().orEmpty(),
                    node.contentDescription?.toString().orEmpty(),
                    node.viewIdResourceName?.toString().orEmpty(),
                ).firstOrNull { it.isNotBlank() }.orEmpty()
                fields.add(EditableField(node = node, label = label))
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let(queue::addLast)
            }
        }
        return fields
    }

    private fun ensurePinterestBoard(
        boardName: String?,
        allowDeferredAtVerifiedComposer: Boolean = false,
        verifiedComposerSnapshot: Boolean = false,
        boardOverlayVisibleSnapshot: Boolean = false,
    ): Boolean {
        if (checkPublishTimeout()) return false
        val targetBoard = boardName?.trim()?.takeIf(String::isNotEmpty)
            ?: ConfigStore(this).load()?.pinterestBoard?.trim()?.takeIf(String::isNotEmpty)
            ?: ""
        if (PinterestVerifiedComposerBoardGatePolicy.canSkipPicker(
                allowDeferredAtVerifiedComposer = allowDeferredAtVerifiedComposer,
                verifiedComposerSnapshot = verifiedComposerSnapshot,
                sameAttemptProof = pinterestConfiguredExactBoardSelectedThisAttempt ||
                    pinterestDirectShareQualifiedThisAttempt || pinterestComposerReachedViaVerifiedPipeline,
                boardOverlayVisible = boardOverlayVisibleSnapshot,
            )
        ) {
            eventOnly("pinterest:board-deferred-from-verified-composer-snapshot")
            return true
        }
        if (!ensurePackageActive("com.pinterest", "Pinterest board selector is not active")) {
            return false
        }
        dismissSystemInterferenceIfPresent()
        var screen = detectPinterestScreen()
        if (pinterestConfiguredExactBoardSelectedThisAttempt &&
            screen == PinterestScreenKind.COMPOSER &&
            !isPinterestBoardSelectionScreen()
        ) {
            eventOnly("pinterest:exact-board-proof-reused-in-current-attempt")
            return true
        }
        if (pinterestPendingExactBoardSelection) {
            promotePendingPinterestBoardSelection(screen)
            if (pinterestConfiguredExactBoardSelectedThisAttempt &&
                screen == PinterestScreenKind.COMPOSER && !isPinterestBoardSelectionScreen()
            ) return true
            if (screen == PinterestScreenKind.BOARD_SELECTION) {
                val remaining = PINTEREST_BOARD_PENDING_TIMEOUT_MS -
                    (SystemClock.uptimeMillis() - pinterestPendingBoardStartedAt)
                if (remaining > 0) {
                    waitForFreshPinterestTree(
                        pinterestPendingBoardGeneration,
                        pinterestPendingBoardFingerprint,
                        remaining.coerceAtMost(2500L),
                    )
                    screen = detectPinterestScreen()
                    promotePendingPinterestBoardSelection(screen)
                    if (pinterestConfiguredExactBoardSelectedThisAttempt) return true
                }
                if (pinterestPendingExactBoardSelection &&
                    SystemClock.uptimeMillis() - pinterestPendingBoardStartedAt >= PINTEREST_BOARD_PENDING_TIMEOUT_MS
                ) {
                    pinterestPendingExactBoardSelection = false
                    return needsReview("Pinterest exact board click did not produce a fresh state away from the board overlay")
                }
                return true
            }
        }
        if (allowDeferredAtVerifiedComposer && screen == PinterestScreenKind.COMPOSER &&
            !isPinterestBoardSelectionScreen()
        ) {
            step("pinterest:board-deferred-until-final-create")
            return true
        }
        if (targetBoard.isBlank()) {
            return if (screen == PinterestScreenKind.BOARD_SELECTION) {
                needsReview("Pinterest board picker is open but no existing board is configured")
            } else true
        }
        if (screen != PinterestScreenKind.BOARD_SELECTION) {
            val boardEntryLabels = listOf(
                "Pick a board",
                "Select board",
                "Choose board",
                "Выберите доску",
                "Выбрать доску",
            )
            var boardEntryOpened = false
            repeat(4) {
                if (!boardEntryOpened) {
                    val exactLabel = visibleLabels(80).firstOrNull { visible ->
                        boardEntryLabels.any { expected -> visible.equals(expected, ignoreCase = true) }
                    }
                    if (exactLabel != null) boardEntryOpened = tapVisibleExactText(exactLabel)
                    if (!boardEntryOpened) {
                        scrollPinterestComposer(direction = AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                        SystemClock.sleep(200)
                    }
                }
            }
            var boardPickerVisible = waitForPinterestScreen(PinterestScreenKind.BOARD_SELECTION, 1200)
            if (!boardPickerVisible && verifiedComposerSnapshot && !boardOverlayVisibleSnapshot) {
                val root = rootInActiveWindow
                val boardButton = root?.let(::findPinterestComposerBoardButton)
                val opened = boardButton?.let(::clickNode) == true
                if (opened) eventOnly("pinterest:board-picker-open-dispatched")
                boardPickerVisible = waitForPinterestScreen(PinterestScreenKind.BOARD_SELECTION, 2500)
            }
            if (!boardPickerVisible) {
                return needsReview(
                    "Pinterest did not expose a visible board picker for configured board '$targetBoard'. " +
                        "Lower-left candidates: ${pinterestComposerLowerLeftSummary()}",
                )
            }
            screen = detectPinterestScreen()
        }
        if (screen != PinterestScreenKind.BOARD_SELECTION) {
            return needsReview("Pinterest board picker state could not be verified")
        }
        // The board-sheet container is announced before its rows are attached on Pinterest's
        // Android 9 build. Wait only for the exact configured existing-board label; never fall
        // back to Create board or a fuzzy/partial match.
        val boardLabelDeadline = SystemClock.uptimeMillis() + 3000L
        var existingBoard: String? = null
        while (SystemClock.uptimeMillis() < boardLabelDeadline && existingBoard == null) {
            if (checkPublishTimeout()) return false
            if (detectPinterestScreen() != PinterestScreenKind.BOARD_SELECTION) break
            existingBoard = PinterestBoardPolicy.existingBoardLabel(visibleLabels(80), targetBoard)
            if (existingBoard == null) SystemClock.sleep(150)
        }
        val selectedBoardLabel = existingBoard
            ?: return needsReview("Configured existing Pinterest board '$targetBoard' is not visible; Create board was not selected")
        val generationBeforeClick = windowContentGeneration
        val fingerprintBeforeClick = activeTreeFingerprint()
        if (!tapVisibleExactText(selectedBoardLabel)) {
            return needsReview("Configured existing Pinterest board '$targetBoard' was visible but could not be selected")
        }
        pinterestPendingExactBoardSelection = true
        pinterestPendingBoardGeneration = generationBeforeClick
        pinterestPendingBoardFingerprint = fingerprintBeforeClick
        pinterestPendingBoardStartedAt = SystemClock.uptimeMillis()
        val freshTree = waitForFreshPinterestTree(generationBeforeClick, fingerprintBeforeClick, 3500)
        val screenAfterSelection = detectPinterestScreen()
        val postSelectionAction = PinterestBoardPostSelectionPolicy.action(
            exactConfiguredBoardSelected = true,
            freshTree = freshTree,
            screen = screenAfterSelection,
        )
        promotePendingPinterestBoardSelection(screenAfterSelection)
        if (!freshTree && screenAfterSelection == PinterestScreenKind.BOARD_SELECTION) {
            step("pinterest:board-selection-pending-fresh-transition")
            return true
        }
        return when (postSelectionAction) {
            PinterestBoardPostSelectionAction.VERIFIED_COMPOSER -> {
                val selectedBoardReadback = hasVisibleExactPinterestText(targetBoard)
                if (selectedBoardReadback) true
                else needsReview(
                    "Pinterest returned to the composer but did not expose '$targetBoard' as the selected board",
                )
            }
            PinterestBoardPostSelectionAction.CONTINUE_HOME_CREATE -> {
                step("pinterest:board-selected-home-continue-create")
                if (!advancePinterestHomeToComposer()) {
                    needsReview("Pinterest selected the configured board and returned HOME, but the strict Create-to-media-picker chain was not verified")
                } else {
                    val routedScreen = detectPinterestScreen()
                    if (routedScreen in setOf(
                            PinterestScreenKind.MEDIA_STEP,
                            PinterestScreenKind.COMPOSER,
                            PinterestScreenKind.BOARD_SELECTION,
                        )
                    ) true
                    else needsReview("Pinterest board HOME routing did not finish in a verified pre-composer state")
                }
            }
            PinterestBoardPostSelectionAction.CONTINUE_MEDIA_SELECTION -> {
                step("pinterest:board-selected-media-continue-exact")
                if (continueFromPinterestMediaStep()) true
                else needsReview(
                    "Pinterest selected the configured board and opened media picker, but exact media selection and fresh editor transition were not verified",
                )
            }
            PinterestBoardPostSelectionAction.NEEDS_REVIEW -> needsReview(
                if (!freshTree) "Pinterest board selection did not produce a fresh active window/content tree"
                else "Pinterest board picker did not return to a verified editor, media picker, or safe HOME routing state",
            )
        }
    }

    private fun promotePendingPinterestBoardSelection(currentScreen: PinterestScreenKind) {
        if (!pinterestPendingExactBoardSelection) return
        val fingerprint = activeTreeFingerprint()
        val freshTree = windowContentGeneration > pinterestPendingBoardGeneration ||
            (fingerprint.isNotBlank() && fingerprint != pinterestPendingBoardFingerprint)
        val canPromote = PinterestExactBoardSelectionProofPolicy.canRecord(
            exactBoardSelected = true,
            freshTree = freshTree,
            screenAfterSelection = currentScreen,
            boardOverlayVisible = isPinterestBoardSelectionScreen(),
        )
        if (canPromote) {
            pinterestConfiguredExactBoardSelectedThisAttempt = true
            pinterestPendingExactBoardSelection = false
            step("pinterest:exact-board-selection-promoted")
        }
    }

    private fun clearPinterestBoardSelectionProof() {
        pinterestConfiguredExactBoardSelectedThisAttempt = false
        pinterestPendingExactBoardSelection = false
        pinterestPendingBoardGeneration = 0L
        pinterestPendingBoardFingerprint = ""
        pinterestPendingBoardStartedAt = 0L
    }

    private fun clearTerminalPinterestProof() {
        terminalProofJobId = null
        terminalProofAttempt = null
        terminalProofComposerPipeline = false
        terminalProofExactBoard = false
        terminalProofDirectShare = false
    }

    private fun clearTerminalSocialProof() {
        terminalSocialProofJobId = null
        terminalSocialProofAttempt = null
        terminalSocialProofPackage = null
        terminalSocialProofFingerprint = null
    }

    private fun waitForPinterestScreen(expected: PinterestScreenKind, timeoutMs: Long): Boolean {
        val startedAt = SystemClock.uptimeMillis()
        while (SystemClock.uptimeMillis() - startedAt < timeoutMs) {
            if (checkPublishTimeout()) return false
            if (detectPinterestScreen() == expected) return true
            SystemClock.sleep(150)
        }
        return false
    }

    private fun activeTreeFingerprint(): String {
        val root = rootInActiveWindow ?: return ""
        return "${root.windowId}|${visibleLabels(60).joinToString("\u001f")}"
    }

    private fun waitForFreshPinterestTree(
        generationBeforeClick: Long,
        fingerprintBeforeClick: String,
        timeoutMs: Long,
    ): Boolean {
        val startedAt = SystemClock.uptimeMillis()
        while (SystemClock.uptimeMillis() - startedAt < timeoutMs) {
            if (checkPublishTimeout()) return false
            if (PinterestFreshTreeGuard.hasFreshTree(
                    generationBeforeClick,
                    windowContentGeneration,
                    fingerprintBeforeClick,
                    activeTreeFingerprint(),
                )
            ) return true
            SystemClock.sleep(150)
        }
        return false
    }

    private fun clickVisibleExactText(target: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            if (checkPublishTimeout()) return false
            val node = queue.removeFirst()
            if (node.isVisibleToUser) {
                val text = node.text?.toString().orEmpty()
                val desc = node.contentDescription?.toString().orEmpty()
                if (text.equals(target, ignoreCase = true) || desc.equals(target, ignoreCase = true)) {
                    return clickNode(node)
                }
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::addLast)
        }
        return false
    }

  private fun tapVisibleExactText(target: String): Boolean {
      val root = activeAccessibilityRoot() ?: return false
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            if (checkPublishTimeout()) return false
            val node = queue.removeFirst()
            if (node.isVisibleToUser) {
                val text = node.text?.toString().orEmpty()
                val desc = node.contentDescription?.toString().orEmpty()
                if (text.equals(target, ignoreCase = true) || desc.equals(target, ignoreCase = true)) {
                    val bounds = Rect().also(node::getBoundsInScreen)
                    if (bounds.width() > 0 && bounds.height() > 0) {
                        return tapScreen(bounds.exactCenterX(), bounds.exactCenterY())
                    }
                    return false
                }
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::addLast)
        }
        return false
    }

  private fun hasVisibleExactPinterestText(target: String): Boolean {
      val root = activeAccessibilityRoot() ?: return false
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited++ < 500) {
            val node = queue.removeFirst()
            if (node.isVisibleToUser) {
                val text = node.text?.toString()?.trim().orEmpty()
                val description = node.contentDescription?.toString()?.trim().orEmpty()
                val exactForms = setOf(
                    target,
                    "Pick a board. $target",
                    "Pick a board, $target",
                    "Выбрать доску. $target",
                    "Выбрать доску, $target",
                )
                if (text in exactForms || description in exactForms ||
                    isExactPinterestBoardReadback(text, target) ||
                    isExactPinterestBoardReadback(description, target)
                ) return true
            }
            for (index in 0 until node.childCount) node.getChild(index)?.let(queue::add)
        }
        return false
    }

    private fun isExactPinterestBoardReadback(candidate: String, target: String): Boolean {
        if (!candidate.contains(target, ignoreCase = false)) return false
        var residual = candidate.replace(target, "", ignoreCase = false).lowercase()
        listOf(
            "pick a board", "select board", "choose board",
            "secret board", "private board", "secret", "private", "board",
            "выбрать доску", "выберите доску", "секретная доска", "приватная доска",
          "секретная", "приватная", "доска",
      ).forEach { residual = residual.replace(it, "") }
      residual = residual.replace(Regex("\\b\\d+\\s*(pins?|пин(?:а|ов)?)\\b"), "")
      residual = residual.replace(Regex("\\b\\d+\\s*(s|m|h|d|w|mo|y|с|м|ч|д|н|мес|г)\\b"), "")
      residual = residual.replace(Regex("\\b(now|сейчас|today|сегодня)\\b"), "")
      return residual.replace(Regex("[^a-zа-я0-9]"), "").isEmpty()
  }

  private fun tapVisibleExactPinterestBoard(target: String): Boolean {
      val root = activeAccessibilityRoot() ?: return false
      val queue = ArrayDeque<AccessibilityNodeInfo>()
      queue.add(root)
      var visited = 0
      while (queue.isNotEmpty() && visited++ < 500) {
          val node = queue.removeFirst()
          if (node.isVisibleToUser) {
              val candidates = listOf(
                  node.text?.toString()?.trim().orEmpty(),
                  node.contentDescription?.toString()?.trim().orEmpty(),
              )
              if (candidates.any { isExactPinterestBoardReadback(it, target) }) {
                  if (clickNode(node)) return true
                  val bounds = Rect().also(node::getBoundsInScreen)
                  if (bounds.width() > 0 && bounds.height() > 0 &&
                      tapScreen(bounds.exactCenterX(), bounds.exactCenterY())
                  ) return true
              }
          }
          for (index in 0 until node.childCount) node.getChild(index)?.let(queue::add)
      }
      return false
  }

    private fun clickPinterestPublish(job: PublishJob): Boolean {
        if (checkPublishTimeout()) return false
        dismissSystemInterferenceIfPresent()
        var clicked = false
        repeat(6) {
            if (clicked || checkPublishTimeout()) return@repeat
            val root = rootInActiveWindow ?: return@repeat
            val button = listOf("Create", "Создать")
                .firstNotNullOfOrNull { findNodeByExactText(root, it) }
            if (button != null && clickNode(button)) clicked = true else SystemClock.sleep(300)
        }
        if (!clicked) {
            return needsReview("Pinterest: exact final Create button was not available; publication was not attempted")
        }
        return waitForPinterestPublishConfirmation(job)
    }

    private fun findPinterestComposerBoardButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val rootBounds = Rect().also(root::getBoundsInScreen)
        if (rootBounds.width() <= 0 || rootBounds.height() <= 0) return null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited++ < 500) {
            val node = queue.removeFirst()
            val bounds = Rect().also(node::getBoundsInScreen)
            val lowerLeft = bounds.centerX() < rootBounds.left + rootBounds.width() / 4 &&
                bounds.centerY() > rootBounds.top + (rootBounds.height() * 4 / 5)
            val bounded = bounds.width() in 40..(rootBounds.width() / 3) &&
                bounds.height() in 40..(rootBounds.height() / 5)
            val label = listOf(node.text, node.contentDescription).joinToString(" ").trim().lowercase()
            val exactBoardEntry = label == "pick a board" || label == "select board" ||
                label == "choose board" || label == "выберите доску" || label == "выбрать доску"
            val explicitlyUnsafe = node.viewIdResourceName?.contains("save_draft", ignoreCase = true) == true ||
                label.contains("save draft") || label.contains("сохранить черновик")
            if (node.isVisibleToUser && node.isEnabled && lowerLeft && bounded && exactBoardEntry && !explicitlyUnsafe &&
                (node.className?.toString()?.contains("Image", ignoreCase = true) == true ||
                    node.className?.toString()?.endsWith("View") == true || node.isClickable)
            ) {
                return node
            }
            for (index in 0 until node.childCount) node.getChild(index)?.let(queue::add)
        }
        return null
    }

    private fun dismissPinterestKeyboardAndRetainComposer(): Boolean {
        // Pinterest maps Accessibility GLOBAL_ACTION_BACK to "Save draft?" instead of first
        // dismissing Gboard. AccessibilityService's API-24 keyboard controller hides the IME
        // without navigating away from the composer. Keep it hidden through the board gate and
        // restore SHOW_MODE_AUTO at the end of publish().
        val controller = softKeyboardController
        if (!controller.setShowMode(SHOW_MODE_HIDDEN)) return false
        softKeyboardSuppressedForPublish = true
        rootInActiveWindow
            ?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?.performAction(AccessibilityNodeInfo.ACTION_CLEAR_FOCUS)
        SystemClock.sleep(700)
        return currentPackageName() == "com.pinterest" && isPinterestComposerScreen()
    }

    private fun scrollPinterestComposerToTopForVerification() {
        repeat(3) {
            if (!scrollPinterestComposer(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) return
            SystemClock.sleep(250)
        }
    }

    private fun scrollPinterestComposer(direction: Int): Boolean {
        val root = rootInActiveWindow ?: return false
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited++ < 500) {
            val node = queue.removeFirst()
            if (node.isVisibleToUser && node.isScrollable && node.performAction(direction)) return true
            for (index in 0 until node.childCount) node.getChild(index)?.let(queue::add)
        }
        return false
    }

    private fun restoreSoftKeyboardMode() {
        if (!softKeyboardSuppressedForPublish) return
        softKeyboardController.setShowMode(SHOW_MODE_AUTO)
        softKeyboardSuppressedForPublish = false
    }

    private fun pinterestComposerLowerLeftSummary(): String {
        val root = rootInActiveWindow ?: return "no-root"
        val rootBounds = Rect().also(root::getBoundsInScreen)
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        val items = mutableListOf<String>()
        var visited = 0
        while (queue.isNotEmpty() && visited++ < 500 && items.size < 12) {
            val node = queue.removeFirst()
            val bounds = Rect().also(node::getBoundsInScreen)
            if (bounds.centerX() < rootBounds.left + rootBounds.width() / 3 &&
                bounds.centerY() > rootBounds.top + (rootBounds.height() * 3 / 4)
            ) {
                val label = listOf(node.text, node.contentDescription)
                    .joinToString(" ").trim().take(40)
                items += "${node.className}:${node.viewIdResourceName.orEmpty()}:$bounds:" +
                    "visible=${node.isVisibleToUser},enabled=${node.isEnabled},clickable=${node.isClickable},label=$label"
            }
            for (index in 0 until node.childCount) node.getChild(index)?.let(queue::add)
        }
        return items.joinToString(";").ifBlank { "none" }
    }

    private fun waitForPinterestPublishConfirmation(job: PublishJob): Boolean {
        var boardSelectedAfterCreate = false
        repeat(24) {
            if (checkPublishTimeout()) return false
            SystemClock.sleep(250)
            dismissSystemInterferenceIfPresent()
            if (currentPackageName() != "com.pinterest") {
                return needsReview("Pinterest left the app after Create; positive publication receipt was not observed")
            }
            dismissPinterestHelpOverlayIfPresent()
            when (PinterestPublicationConfirmationPolicy.classify(visibleLabels())) {
              PinterestPublicationConfirmation.PUBLISHED -> {
                  verifiedPublicationId = "pinterest:${job.jobId}"
                  step("pinterest:publication-confirmed")
                  return true
              }
                PinterestPublicationConfirmation.DRAFT_SAVED ->
                    return needsReview("Pinterest saved a draft instead of publishing; success is forbidden")
                PinterestPublicationConfirmation.PENDING -> Unit
            }
            if (!boardSelectedAfterCreate && isPinterestBoardSelectionScreen()) {
                val targetBoard = job.board?.trim()?.takeIf(String::isNotEmpty)
                    ?: ConfigStore(this).load()?.pinterestBoard?.trim()?.takeIf(String::isNotEmpty)
                    ?: return needsReview("Pinterest requested a destination board after Create, but no exact board is configured")
                val existingBoard = PinterestBoardPolicy.existingBoardLabel(visibleLabels(80), targetBoard)
                    ?: return needsReview("Pinterest requested a board after Create, but exact existing board '$targetBoard' is not visible")
                if (!clickVisibleExactText(existingBoard)) {
                    return needsReview("Pinterest exact board '$targetBoard' was visible after Create but could not be selected")
                }
                boardSelectedAfterCreate = true
                step("pinterest:post-create-board-selected")
            }
        }
        if (verifyExistingPinterestPin(job)) return true
        return needsReview("Pinterest Create was pressed but no positive publication receipt was observed. Visible: ${visibleLabelsSummary()}")
    }

  private fun verifyExistingPinterestPin(job: PublishJob): Boolean {
      if (job.target !in setOf("pinterest_pin", "pinterest_pin_verify")) return false
      val targetBoard = job.board?.trim()?.takeIf(String::isNotEmpty) ?: return false
      val targetTitle = job.title?.trim()?.takeIf(String::isNotEmpty) ?: return false
      if (!launchCleanPinterestForVerification()) return false
      SystemClock.sleep(1800)
      dismissSystemInterferenceIfPresent()
      step("pinterest:verification-state-${currentPackageName() ?: "unknown"}-${visibleLabelsSummary()}")

      fun exactPinVisible(): Boolean =
          hasVisibleExactPinterestBoardHeader(targetBoard) && hasVisibleExactPinterestPinTitle(targetTitle)
      if (exactPinVisible()) return recordVerifiedPinterestPublication(job)
      if (hasVisibleExactPinterestBoardHeader(targetBoard) && tapVisiblePinterestPinTitlePrefix(targetTitle)) {
          step("pinterest:verification-pin-card-opened")
          SystemClock.sleep(1200)
          if (hasVisibleExactPinterestPinTitle(targetTitle)) return recordVerifiedPinterestPublication(job)
      }
      recoverSafePinterestVerificationLanding(targetBoard, targetTitle)
      if (exactPinVisible()) return recordVerifiedPinterestPublication(job)
      if (hasVisibleExactPinterestBoardHeader(targetBoard) && tapVisiblePinterestPinTitlePrefix(targetTitle)) {
          step("pinterest:verification-pin-card-opened-after-recovery")
          SystemClock.sleep(1200)
          if (hasVisibleExactPinterestPinTitle(targetTitle)) return recordVerifiedPinterestPublication(job)
      }
      val profileTapped = isPinterestProfileNavigationVisible() || tapPinterestProfileNavigation()
      step("pinterest:verification-profile-tapped-$profileTapped")
      if (!profileTapped) return false
      SystemClock.sleep(1200)
      step("pinterest:verification-after-profile-${currentPackageName() ?: "unknown"}-${visibleLabelsSummary()}")
      val boardsTapped = tapVisibleExactText("Boards")
      step("pinterest:verification-boards-tapped-$boardsTapped")
      SystemClock.sleep(800)
      val boardTapped = tapVisibleExactPinterestBoard(targetBoard)
      step("pinterest:verification-board-tapped-$boardTapped")
      if (!boardTapped) return false
      repeat(6) {
          SystemClock.sleep(500)
          if (hasVisibleExactPinterestPinTitle(targetTitle)) return recordVerifiedPinterestPublication(job)
      }
      if (tapVisiblePinterestPinTitlePrefix(targetTitle)) {
          step("pinterest:verification-pin-card-opened-after-board-proof")
          SystemClock.sleep(1200)
          if (hasVisibleExactPinterestPinTitle(targetTitle)) return recordVerifiedPinterestPublication(job)
      }
      return false

  }

  private fun launchCleanPinterestForVerification(): Boolean {
      step("pinterest:verification-home-launch")
      performGlobalAction(GLOBAL_ACTION_HOME)
      SystemClock.sleep(700)
      return launchPackageAndWait("com.pinterest", "Pinterest", retries = 2)
  }

  private fun tapPinterestProfileNavigation(): Boolean {
      if (clickExactText(listOf("Saved", "Profile", "Сохранено", "Профиль"), retries = 2)) {
          return true
      }
      val labels = visibleLabels().map(String::trim).toSet()
      val verifiedHomeNavigation = setOf("Home", "Search", "Create", "Inbox", "Saved").all(labels::contains) ||
          setOf("Главная", "Поиск", "Создать", "Входящие", "Сохранено").all(labels::contains)
      if (!verifiedHomeNavigation) return false
      val metrics = resources.displayMetrics
      if (metrics.widthPixels <= 0 || metrics.heightPixels <= 0) return false
      return tapScreen(metrics.widthPixels * 0.865f, metrics.heightPixels * 0.905f)
  }

  private fun recoverSafePinterestVerificationLanding(targetBoard: String, targetTitle: String) {
      repeat(2) {
          if (isPinterestComposerScreen()) return
          val labels = visibleLabels().map(String::trim).toSet()
          val safeIdeasScreen = "Back" in labels && "More ideas" in labels
          val safeBoardScreen = "Back" in labels && (
              labels.any { isExactPinterestBoardReadback(it, targetBoard) } ||
                  (hasVisibleExactPinterestPinTitle(targetTitle) && labels.any { it.matches(Regex("^\\d+\\s+(Pin|Pins|Пин|Пина|Пинов)$")) })
              )
          if (!safeIdeasScreen && !safeBoardScreen) return
          val backTapped = tapVisibleExactText("Back") || tapVisibleExactText("Назад")
          step("pinterest:verification-safe-back-$backTapped")
          if (!backTapped) return
          SystemClock.sleep(900)
      }
  }

  private fun isPinterestProfileNavigationVisible(): Boolean {
      val labels = visibleLabels().map(String::trim).toSet()
      return "Boards" in labels && ("Pins" in labels || "Search your Pins" in labels) ||
          "Доски" in labels && ("Пины" in labels || "Поиск ваших пинов" in labels)
  }

  private fun recordVerifiedPinterestPublication(job: PublishJob): Boolean {
      verifiedPublicationId = "pinterest:${job.jobId}"
      step("pinterest:existing-pin-confirmed")
      return true
  }

  private fun hasVisibleExactPinterestPinTitle(targetTitle: String): Boolean {
      val root = activeAccessibilityRoot() ?: return false
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        val exactForms = setOf(
            targetTitle,
            "Pin from Pin, Title: $targetTitle",
            "Pin, Title: $targetTitle",
            "Пин, заголовок: $targetTitle",
        )
        while (queue.isNotEmpty() && visited++ < 500) {
            val node = queue.removeFirst()
            if (node.isVisibleToUser) {
                val candidates = listOfNotNull(
                    node.text?.toString()?.trim(),
                    node.contentDescription?.toString()?.trim(),
                )
                if (candidates.any { candidate ->
                        candidate in exactForms ||
                            candidate.startsWith("$targetTitle,") ||
                            candidate.startsWith("$targetTitle.") ||
                            candidate.startsWith("$targetTitle ") ||
                            candidate.contains("Title: $targetTitle") ||
                            candidate.contains("заголовок: $targetTitle")
                    }
                ) return true
            }
            for (index in 0 until node.childCount) node.getChild(index)?.let(queue::add)
        }
        return false
    }

    private fun hasVisibleExactPinterestBoardHeader(targetBoard: String): Boolean {
        val labels = visibleLabels().map(String::trim)
        val exactBoard = labels.any { it == targetBoard }
        val pinCount = labels.any {
            Regex("^(Secret|Private|Секретная|Приватная)?\\s*[·•]?\\s*\\d+\\s+(Pin|Pins|Пин|Пина|Пинов)$")
                .matches(it)
        }
        return exactBoard && pinCount
    }

    private fun tapVisiblePinterestPinTitlePrefix(targetTitle: String): Boolean {
        val root = activeAccessibilityRoot() ?: return false
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited++ < 500) {
            val node = queue.removeFirst()
            if (node.isVisibleToUser && !node.isEditable) {
                val candidates = listOfNotNull(node.text?.toString(), node.contentDescription?.toString())
                val matches = candidates.any { raw ->
                    val prefix = raw.trim().removeSuffix("…").removeSuffix("...").trimEnd()
                    prefix.length >= 12 && targetTitle.startsWith(prefix)
                }
                if (matches && clickNode(node)) return true
            }
            for (index in 0 until node.childCount) node.getChild(index)?.let(queue::add)
        }
        return false
    }

    private fun isPinterestComposerScreen(): Boolean {
        return PinterestScreenClassifier.hasStableComposerSignature(
            PinterestEditorExtractionAdapter.extract(snapshotPinterestNodes()).editableSignals,
        )
    }

    private fun verifyCurrentPinterestEditor(job: PublishJob): Boolean {
        val immutableSnapshot = PinterestImmutableSnapshotAcquirer.acquire(
            maxAttempts = 3,
            capture = {
                val generationBefore = windowContentGeneration
                val nodes = snapshotPinterestNodes()
                PinterestImmutableEditorSnapshotPolicy.create(
                    nodes, generationBefore, windowContentGeneration,
                )
            },
            settle = { SystemClock.sleep(100) },
        )
        if (!immutableSnapshot.consistent) {
            lastEditorGateDiagnostic = "editor-subgates immutableSnapshot=false reason=${immutableSnapshot.reason}"
            eventOnly("pinterest:editor-gate:immutableSnapshot=false")
            return false
        }
        val extraction = PinterestEditorExtractionAdapter.extract(immutableSnapshot.nodes)
        val stableSignature = PinterestScreenClassifier.hasStableComposerSignature(extraction.editableSignals)
        val overlay = PinterestImmutableEditorSnapshotPolicy.hasBoardOverlay(immutableSnapshot)
        val terminalProofMatches = terminalProofJobId == job.jobId && terminalProofAttempt == job.attemptNumber
        val composerAttemptProof = PinterestAttemptProofPolicy.qualifies(
            pinterestDirectShareQualifiedThisAttempt,
            pinterestComposerReachedViaVerifiedPipeline,
            pinterestConfiguredExactBoardSelectedThisAttempt,
            terminalProofMatches,
            terminalProofDirectShare,
            terminalProofComposerPipeline,
            terminalProofExactBoard,
        )
        val mediaPreview = PinterestImmutableEditorSnapshotPolicy.hasMediaPreview(
            immutableSnapshot,
            verifiedComposerContext = stableSignature && composerAttemptProof && !overlay,
        )
        val gate = PinterestEditorExtractionAdapter.evaluate(
            extraction, job.title, pinterestBody(job), job.link, overlay, mediaPreview,
            immutableSnapshot.generation > 0, immutableSnapshot.fingerprint.isNotBlank(),
        )
        val titleField = extraction.fields.firstOrNull { it.kind == PinterestEditorFieldKind.TITLE }
        val bodyField = extraction.fields.firstOrNull { it.kind == PinterestEditorFieldKind.BODY }
        val linkField = extraction.fields.firstOrNull { it.kind == PinterestEditorFieldKind.LINK }
        val titleExact = titleField?.text?.trim() == job.title?.trim()
        val bodyExact = bodyField?.text?.trim() == pinterestBody(job)?.trim()
        val linkExactOrEmpty = if (job.link.isNullOrBlank()) {
            linkField?.text?.trim().orEmpty().let { it.isEmpty() || it.equals("Link. Add your link here", true) }
        } else linkField?.text?.trim() == job.link.trim()
        lastEditorGateDiagnostic = listOf(
            "editor-subgates", "immutableSnapshot=true", "stableComposer=$stableSignature",
            "overlay=$overlay", "attemptProof=$composerAttemptProof", "mediaPreview=$mediaPreview",
            "titleExact=$titleExact", "bodyExact=$bodyExact", "linkExactOrEmpty=$linkExactOrEmpty",
            "titleShape=${pinterestFieldDiagnosticShape(titleField, job.title)}",
            "bodyShape=${pinterestFieldDiagnosticShape(bodyField, pinterestBody(job))}",
            "readbackMatches=${gate.readback.matches}",
            "generationPositive=${immutableSnapshot.generation > 0}",
            "fingerprintNonempty=${immutableSnapshot.fingerprint.isNotBlank()}",
            "gateVerified=${gate.verified}", gate.diagnostic,
        ).joinToString(" ")
        eventOnly("pinterest:$lastEditorGateDiagnostic")
        return gate.verified
    }

    private fun pinterestFieldDiagnosticShape(
        field: PinterestEditorFieldSnapshot?,
        expected: String?,
    ): String {
        if (field == null) return "missing"
        val wanted = expected?.trim().orEmpty()
        fun safe(value: String): String {
            val marked = if (wanted.isNotEmpty()) value.replace(wanted, "<expected>") else value
            return marked.replace(Regex("[^A-Za-zА-Яа-я0-9_<>.,:/ -]"), "?").take(100)
        }
        return "text=${safe(field.text)}|desc=${safe(field.description)}|hint=${safe(field.hint)}"
    }

    private fun snapshotPinterestNodes(): List<PinterestNodeSnapshot> {
        val root = rootInActiveWindow ?: return emptyList()
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, String>>()
        val snapshots = mutableListOf<PinterestNodeSnapshot>()
        queue.add(root to "root")
        while (queue.isNotEmpty()) {
            val (node, path) = queue.removeFirst()
            val bounds = Rect().also(node::getBoundsInScreen)
            snapshots += PinterestNodeSnapshot(
                path = path,
                text = node.text?.toString().orEmpty(),
                description = node.contentDescription?.toString().orEmpty(),
                hint = node.hintText?.toString().orEmpty(),
                className = node.className?.toString().orEmpty(),
                viewId = node.viewIdResourceName?.toString().orEmpty(),
                visible = node.isVisibleToUser,
                editable = node.isEditable,
                bounds = bounds.toShortString(),
            )
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.addLast(it to "$path/$i") }
        }
        return snapshots
    }

    private fun snapshotSocialAccessibilityTree(): SocialAccessibilitySnapshot {
        val generationBefore = windowContentGeneration
        val root = rootInActiveWindow
            ?: return SocialAccessibilitySnapshot("", generationBefore, windowContentGeneration, "", emptyList())
        val packageName = root.packageName?.toString().orEmpty()
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, String>>()
        val nodes = mutableListOf<SocialAccessibilityNode>()
        queue.add(root to "root")
        while (queue.isNotEmpty()) {
            val (node, path) = queue.removeFirst()
            val bounds = Rect().also(node::getBoundsInScreen)
            if (node.isVisibleToUser) {
                nodes += SocialAccessibilityNode(
                    path = path,
                    text = node.text?.toString().orEmpty(),
                    description = node.contentDescription?.toString().orEmpty(),
                    hint = node.hintText?.toString().orEmpty(),
                    className = node.className?.toString().orEmpty(),
                    viewId = node.viewIdResourceName?.toString().orEmpty(),
                    visible = true,
                    editable = node.isEditable,
                    bounds = bounds.toShortString(),
                )
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.addLast(it to "$path/$i") }
        }
        val generationAfter = windowContentGeneration
        val fingerprint = if (nodes.isEmpty()) "" else nodes.joinToString("\u001f") {
            "${it.path}|${it.viewId}|${it.text}|${it.description}|${it.bounds}"
        }
        return SocialAccessibilitySnapshot(
            packageName,
            generationBefore,
            generationAfter,
            fingerprint,
            nodes,
        )
    }

    private fun findAssociatedPinterestFieldCounter(
        fieldNode: AccessibilityNodeInfo,
        expectedCounter: String,
    ): String {
        if (expectedCounter.isBlank()) return ""
        val root = rootInActiveWindow ?: return ""
        val fieldBounds = Rect().also(fieldNode::getBoundsInScreen)
        if (fieldBounds.width() <= 0 || fieldBounds.height() <= 0) return ""
        val maxVerticalDistance = maxOf(180, fieldBounds.height() * 3)
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var nearestDistance = Int.MAX_VALUE
        var matched = ""
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.isVisibleToUser && node.text?.toString()?.trim() == expectedCounter) {
                val bounds = Rect().also(node::getBoundsInScreen)
                val verticalDistance = when {
                    bounds.centerY() < fieldBounds.top -> fieldBounds.top - bounds.centerY()
                    bounds.centerY() > fieldBounds.bottom -> bounds.centerY() - fieldBounds.bottom
                    else -> 0
                }
                val horizontallyAssociated = bounds.right >= fieldBounds.left &&
                    bounds.left <= fieldBounds.right + fieldBounds.width()
                if (horizontallyAssociated && verticalDistance <= maxVerticalDistance &&
                    verticalDistance < nearestDistance
                ) {
                    nearestDistance = verticalDistance
                    matched = expectedCounter
                }
            }
            for (index in 0 until node.childCount) node.getChild(index)?.let(queue::addLast)
        }
        return matched
    }

    private fun hasVisiblePinterestMediaPreview(
        verifiedComposerContext: Boolean,
        enforcePublishDeadline: Boolean = true,
    ): Boolean {
        if (!verifiedComposerContext) return false
        val root = rootInActiveWindow ?: return false
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            if (enforcePublishDeadline && checkPublishTimeout()) return false
            val node = queue.removeFirst()
            val viewId = node.viewIdResourceName?.toString().orEmpty()
            val imageNode = viewId.endsWith("attribute_image_view") ||
                node.contentDescription?.toString()?.equals("Image", ignoreCase = true) == true ||
                node.className?.toString()?.contains("Image", ignoreCase = true) == true
            if (node.isVisibleToUser && imageNode) {
                val bounds = Rect().also(node::getBoundsInScreen)
                if (bounds.width() >= 48 && bounds.height() >= 48) return true
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::addLast)
        }
        return false
    }

    private fun isPinterestHomeScreen(): Boolean {
        val labels = visibleLabels()
        val markers = listOf(
            "Home", "Search", "Saved", "Updates", "Profile",
            "Главная", "Поиск", "Сохранено", "Обновления", "Профиль",
        )
        val hits = labels.count { label -> markers.any { marker -> label.contains(marker, ignoreCase = true) } }
        return hits >= 2
    }

    private fun isPinterestMediaStepScreen(): Boolean {
        val labels = visibleLabels()
        return PinterestMediaStepOwnershipPolicy.isOwnedMediaStep(
            labels,
            hasExactPreparedMediaTileCandidate(preparedMediaPath, preparedMediaName),
        )
    }

    private fun isPinterestCreateMenuScreen(): Boolean {
        val root = rootInActiveWindow ?: return false
        val signals = collectPinterestCreateMenuSignals(root)
        if (PinterestCreateMenuPolicy.isOwnedMenu(signals)) return true
        // Pinterest's current bottom sheet exposes the title and menu entries through
        // the flattened visible-label traversal even when their individual wrapper
        // nodes are reported inconsistently. Require the complete exact signature.
        val labels = visibleLabels(limit = 60).map {
            it.trim().replace(Regex("\\s+"), " ").lowercase()
        }.toSet()
        val owner = labels.contains("start creating now") ||
            labels.contains("create something") ||
            labels.contains("начните создавать") ||
            labels.contains("создайте что-нибудь")
        val entries = (labels.contains("pin") || labels.contains("пин")) &&
            (labels.contains("collage") || labels.contains("коллаж")) &&
            (labels.contains("board") || labels.contains("доска"))
        return owner && entries
    }

    private fun isShareChooserScreen(): Boolean {
        val labels = visibleLabels()
        val chooserMarkers = listOf(
            "Share with", "Поделиться", "Choose app", "Выберите приложение", "Send to",
            "Android system", "MIUI", "Pinterest",
        )
        val current = currentPackageName().orEmpty()
        return current != "com.pinterest" && labels.any { label ->
            chooserMarkers.any { marker -> label.contains(marker, ignoreCase = true) }
        }
    }

    private fun isPinterestHelpOverlayVisible(): Boolean {
        val labels = visibleLabels()
        val modalOwnerMarkers = listOf(
            "Close", "Done", "Dismiss", "Закрыть", "Готово",
        )
        val helpSpecificMarkers = listOf(
            "How to create pins", "Contact support", "Send feedback", "Help center",
            "Как создавать пины", "Связаться с поддержкой", "Оставить отзыв", "Центр помощи",
        )
        val modalOwned = labels.any { label ->
            modalOwnerMarkers.any { marker -> label.equals(marker, ignoreCase = true) }
        }
        val hasHelpSpecificControl = labels.any { label ->
            helpSpecificMarkers.any { marker -> label.contains(marker, ignoreCase = true) }
        }
        return modalOwned && hasHelpSpecificControl
    }

    private fun detectPinterestScreen(): PinterestScreenKind {
        if (isAgentUiVisible()) return PinterestScreenKind.AGENT_UI
        if (isShareChooserScreen()) return PinterestScreenKind.CHOOSER
        val current = currentPackageName()
        if (current == null) return PinterestScreenKind.UNKNOWN
        if (current != "com.pinterest") return PinterestScreenKind.OTHER_APP
        val strictHelpOverlay = isPinterestHelpOverlayVisible()
        return PinterestScreenClassifier.resolve(
            PinterestScreenSignals(
                boardSelection = isPinterestBoardSelectionScreen(),
                composer = isPinterestComposerScreen(),
                helpModalOwned = strictHelpOverlay,
                helpSpecificControl = strictHelpOverlay,
                createMenu = isPinterestCreateMenuScreen(),
                camera = isPinterestCameraScreen(),
                mediaStep = isPinterestMediaStepScreen(),
                home = isPinterestHomeScreen(),
            ),
        )
    }

    private fun findNodeByText(root: AccessibilityNodeInfo, target: String): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            if (checkPublishTimeout()) return null
            val node = queue.removeFirst()
            val text = node.text?.toString().orEmpty()
            val desc = node.contentDescription?.toString().orEmpty()
            val viewId = node.viewIdResourceName?.toString().orEmpty()
            if (
                text.equals(target, ignoreCase = true) ||
                desc.equals(target, ignoreCase = true) ||
                viewId.contains(target, ignoreCase = true)
            ) {
                return node
            }
            if (
                text.contains(target, ignoreCase = true) ||
                desc.contains(target, ignoreCase = true) ||
                viewId.contains(target, ignoreCase = true)
            ) {
                return node
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let(queue::addLast)
            }
        }
        return null
    }

    private fun findNodeByExactText(root: AccessibilityNodeInfo, target: String): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            if (checkPublishTimeout()) return null
            val node = queue.removeFirst()
            val text = node.text?.toString().orEmpty()
            val desc = node.contentDescription?.toString().orEmpty()
            if (text.equals(target, ignoreCase = true) || desc.equals(target, ignoreCase = true)) {
                return node
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let(queue::addLast)
            }
        }
        return null
    }

    private fun findTopRightClickableNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val rootBounds = Rect().also { root.getBoundsInScreen(it) }
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var bestNode: AccessibilityNodeInfo? = null
        var bestScore = Int.MIN_VALUE

        while (queue.isNotEmpty()) {
            if (checkPublishTimeout()) return null
            val node = queue.removeFirst()
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            val centerX = bounds.centerX()
            val centerY = bounds.centerY()
            val inTopRight = centerX >= rootBounds.centerX() && centerY <= rootBounds.centerY()
            val className = node.className?.toString().orEmpty()
            if (
                inTopRight &&
                node.isClickable &&
                bounds.width() > 0 &&
                bounds.height() > 0 &&
                (
                    className.contains("Button") ||
                    className.contains("Image") ||
                    className.contains("TextView") ||
                    className.contains("View")
                )
            ) {
                val score = centerX - centerY
                if (score > bestScore) {
                    bestScore = score
                    bestNode = node
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let(queue::addLast)
            }
        }
        return bestNode
    }

    private fun clickBoardCandidateNode(): Boolean {
        val root = rootInActiveWindow ?: return false
        val rootBounds = Rect().also { root.getBoundsInScreen(it) }
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        val excluded = listOf(
            "Home", "Search", "Saved", "Updates", "Profile",
            "Главная", "Поиск", "Сохранено", "Обновления", "Профиль",
            "Publish", "Save", "Create", "Next", "Continue",
            "Опубликовать", "Сохранить", "Создать", "Далее", "Продолжить",
            "Support", "Help", "Recommendations", "Contacts", "Feedback",
            "Служба поддержки", "Как создавать пины", "Рекомендации", "Контакты", "Оставить отзыв", "Поддержка",
        )
        var bestNode: AccessibilityNodeInfo? = null
        var bestTop = Int.MAX_VALUE

        while (queue.isNotEmpty()) {
            if (checkPublishTimeout()) return false
            val node = queue.removeFirst()
            val text = node.text?.toString().orEmpty()
            val desc = node.contentDescription?.toString().orEmpty()
            val label = if (text.isNotBlank()) text else desc
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            val centerY = bounds.centerY()
            val candidate =
                node.isClickable &&
                    label.isNotBlank() &&
                    excluded.none { label.contains(it, ignoreCase = true) } &&
                    centerY > rootBounds.height() / 5 &&
                    centerY < rootBounds.height() - rootBounds.height() / 5
            if (candidate && bounds.top < bestTop) {
                bestTop = bounds.top
                bestNode = node
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let(queue::addLast)
            }
        }

        return if (bestNode != null && clickNode(bestNode)) {
            SystemClock.sleep(1200)
            true
        } else {
            false
        }
    }

    private fun dismissPinterestHelpOverlayIfPresent(): Boolean {
        if (!isPinterestHelpOverlayVisible()) {
            return false
        }
        return clickAnyText(listOf("Close", "Закрыть", "Done", "Готово"), retries = 3)
    }

    private fun isPinterestBoardSelectionScreen(): Boolean {
        val labels = visibleLabels()
        val boardMarkers = listOf(
            "Choose board", "Select board", "Save to board", "Create board",
            "Выберите доску", "Выбрать доску", "Сохранить на доску", "Создать доску",
        )
        return labels.any { label -> boardMarkers.any { marker -> label.contains(marker, ignoreCase = true) } }
    }

    private fun isPinterestCameraScreen(): Boolean {
        val labels = visibleLabels()
        val markers = listOf(
            "Close pin camera",
            "Pin camera",
            "Turn flash",
            "Record video",
            "Закрыть камеру пина",
            "Вспышка",
            "Смена камеры",
            "Запись видео",
        )
        return labels.any { label -> markers.any { marker -> label.contains(marker, ignoreCase = true) } }
    }

    private fun launchPinterestShareIntent(mediaUriString: String): Boolean {
        if (checkPublishTimeout()) return false
        val uri = Uri.parse(mediaUriString)
        grantUriPermission("com.pinterest", uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)

        val directIntent = Intent(Intent.ACTION_SEND).apply {
            type = "image/*"
            setPackage("com.pinterest")
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(contentResolver, "mobileposter_media", uri)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (directIntent.resolveActivity(packageManager) != null) {
            try {
                startActivity(directIntent)
                SystemClock.sleep(1500)
                if (waitForStablePackage("com.pinterest", stableMs = 1000, timeoutMs = 3500)) {
                    return true
                }
                if (isShareChooserScreen() && clickAnyText(listOf("Pinterest"), retries = 4)) {
                    return waitForStablePackage("com.pinterest", stableMs = 1000, timeoutMs = 5000)
                }
            } catch (_: Exception) {
                // Fall back to chooser flow below.
            }
        }

        val chooserBaseIntent = Intent(Intent.ACTION_SEND).apply {
            type = "image/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(contentResolver, "mobileposter_media", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooserIntent = Intent.createChooser(chooserBaseIntent, "Share with").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return try {
            startActivity(chooserIntent)
            SystemClock.sleep(1500)
            if (!clickAnyText(listOf("Pinterest"), retries = 6)) {
                return false
            }
            waitForStablePackage("com.pinterest", stableMs = 1000, timeoutMs = 7000)
        } catch (_: Exception) {
            false
        }
    }

    private fun launchPinterestCreateUrl(job: PublishJob): Boolean {
        if (checkPublishTimeout()) return false
        val mediaUrl = job.mediaUrl?.takeIf { it.isNotBlank() } ?: return false
        val targetUrl = job.link?.takeIf { it.isNotBlank() } ?: mediaUrl
        val description = (job.description ?: job.caption).takeIf { it.isNotBlank() } ?: ""
        val createUrl =
            "https://www.pinterest.com/pin/create/button/?" +
                "url=${encodeUrlValue(targetUrl)}&media=${encodeUrlValue(mediaUrl)}&description=${encodeUrlValue(description)}"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(createUrl)).apply {
            setPackage("com.pinterest")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        }
        return try {
            if (intent.resolveActivity(packageManager) == null) return false
            startActivity(intent)
            SystemClock.sleep(1800)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun waitForPinterestReadyScreen(timeoutMs: Long): PinterestScreenKind {
        val start = SystemClock.uptimeMillis()
        while (SystemClock.uptimeMillis() - start < timeoutMs) {
            if (checkPublishTimeout()) return PinterestScreenKind.UNKNOWN
            dismissSystemInterferenceIfPresent()
            if (isAgentUiVisible()) {
                moveAwayFromAgentUiIfNeeded()
            }
            if (isShareChooserScreen()) {
                step("pinterest:chooser")
                clickAnyText(listOf("Pinterest"), retries = 2)
            }
            if (isPinterestHelpOverlayVisible()) {
                dismissPinterestHelpOverlayIfPresent()
            }
            val screen = detectPinterestScreen()
            if (
                screen == PinterestScreenKind.COMPOSER ||
                screen == PinterestScreenKind.BOARD_SELECTION ||
                screen == PinterestScreenKind.MEDIA_STEP ||
                screen == PinterestScreenKind.CAMERA ||
                screen == PinterestScreenKind.HOME ||
                screen == PinterestScreenKind.AGENT_UI
            ) {
                step("pinterest:ready-$screen")
                return screen
            }
            SystemClock.sleep(300)
        }
        val finalScreen = detectPinterestScreen()
        step("pinterest:ready-timeout-$finalScreen")
        return finalScreen
    }

    private fun advancePinterestHomeToComposer(): Boolean {
        if (checkPublishTimeout()) return false
        if (!openPinterestGallerySelection()) {
            return false
        }
        val screenAfterOpen = detectPinterestScreen()
        if (screenAfterOpen in setOf(
                PinterestScreenKind.CREATE_MENU,
                PinterestScreenKind.MEDIA_STEP,
                PinterestScreenKind.COMPOSER,
                PinterestScreenKind.BOARD_SELECTION,
            )
        ) return true
        if (clickPinterestGalleryEntry()) {
            return true
        }
        if (clickPinterestMediaTileCandidate(preparedMediaPath, preparedMediaName)) {
            return true
        }
        return false
    }

    private fun shouldPreferManualPinterestFlow(job: PublishJob): Boolean {
        return !job.title.isNullOrBlank() ||
            !job.description.isNullOrBlank() ||
            !job.link.isNullOrBlank() ||
            !job.board.isNullOrBlank()
    }

    private fun openPinterestGallerySelection(): Boolean {
        repeat(3) { attempt ->
            if (checkPublishTimeout()) return false
            step("pinterest:create-step-${attempt + 1}")
            dismissSystemInterferenceIfPresent()
            exitPinterestTransientScreens()
            var screen = detectPinterestScreen()
            if (screen == PinterestScreenKind.HOME) {
                if (!clickPinterestCreateEntry()) {
                    return@repeat
                }
                var ownedMenuReady = false
                repeat(12) {
                    if (!ownedMenuReady) {
                        ownedMenuReady = isPinterestCreateMenuScreen()
                        if (!ownedMenuReady) SystemClock.sleep(250)
                    }
                }
                screen = detectPinterestScreen()
                if (ownedMenuReady || screen == PinterestScreenKind.CREATE_MENU) {
                    if (!clickPinterestPinEntryAndVerifyMediaPicker()) {
                        return@repeat
                    }
                    screen = detectPinterestScreen()
                }
            }
            if (screen == PinterestScreenKind.CREATE_MENU) {
                if (!clickPinterestPinEntryAndVerifyMediaPicker()) {
                    return@repeat
                }
                screen = detectPinterestScreen()
            }
            if (screen == PinterestScreenKind.CAMERA) {
                step("pinterest:unexpected-camera-after-create")
                exitPinterestTransientScreens()
                return@repeat
            }
            if (
                screen == PinterestScreenKind.MEDIA_STEP ||
                screen == PinterestScreenKind.COMPOSER ||
                screen == PinterestScreenKind.BOARD_SELECTION
            ) {
                return true
            }
            if (clickPinterestGalleryEntry()) {
                SystemClock.sleep(900)
                if (!isPinterestCameraScreen()) {
                    return true
                }
                step("pinterest:camera-after-gallery-entry")
                exitPinterestTransientScreens()
                return@repeat
            }
            return@repeat
        }
        // The current Pinterest build animates the owned create sheet after the
        // third bounded interaction. Give that exact sheet one final bounded
        // classification window before failing; the subsequent click still must
        // prove a fresh media picker containing this job's prepared media.
        if (isPinterestCreateMenuScreen() &&
            clickPinterestPinEntryAndVerifyMediaPicker()
        ) {
            return true
        }
        lastFailureReason =
            "Pinterest: create menu did not reach media picker. Pin nodes: ${describeMatchingNodes(listOf("Pin", "Пин", "Photo Pin"))}. Visible: ${visibleLabelsSummary()}"
        return false
    }

    private fun selectPinterestGalleryMedia(): Boolean {
        if (checkPublishTimeout()) return false
        step("pinterest:select-media")
        if (clickPinterestMediaTileCandidate(preparedMediaPath, preparedMediaName)) {
            return true
        }
        lastFailureReason =
            "Pinterest: media grid did not expose a tappable image tile. Visible: ${visibleLabelsSummary()}"
        return false
    }

    private fun clickPinterestCreateEntry(): Boolean {
        repeat(3) {
            if (checkPublishTimeout()) return false
            val root = rootInActiveWindow ?: return false
            val candidate = findTopCreateActionNode(root)
            if (candidate != null) {
                if (clickNode(candidate)) {
                    SystemClock.sleep(900)
                    if (detectPinterestScreen() != PinterestScreenKind.HOME) {
                        return true
                    }
                }
                if (tapNodeCenter(candidate)) {
                    SystemClock.sleep(900)
                    if (detectPinterestScreen() != PinterestScreenKind.HOME) {
                        return true
                    }
                }
            }
            if (tapTopRightActionZone(root)) {
                SystemClock.sleep(900)
                if (detectPinterestScreen() != PinterestScreenKind.HOME) {
                    return true
                }
            }
            SystemClock.sleep(400)
        }
        lastFailureReason =
            "Pinterest: top-right Create action did not open create menu. Create nodes: ${describeMatchingNodes(listOf("Create", "Создать", "Add", "Добавить"), includeContains = false)}. Visible: ${visibleLabelsSummary()}"
        return false
    }

    private fun tapTopRightActionZone(root: AccessibilityNodeInfo): Boolean {
        val rootBounds = Rect().also { root.getBoundsInScreen(it) }
        if (rootBounds.width() <= 0 || rootBounds.height() <= 0) return false
        val x = rootBounds.left + (rootBounds.width() * 0.90f)
        val y = rootBounds.top + (rootBounds.height() * 0.08f)
        return tapScreen(x, y)
    }

    private fun tapScreen(x: Float, y: Float): Boolean {
        val path = Path().apply {
            moveTo(x, y)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
            .build()
        val startedAt = SystemClock.uptimeMillis()
        val completion = CountDownLatch(1)
        var completed = false
        val dispatched = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    completed = true
                    completion.countDown()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    completion.countDown()
                }
            },
            null,
        )
        if (!dispatched) return false
        try {
            completion.await(GESTURE_COMPLETION_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return false
        }
        return PinterestBoundedInteractionPolicy.gestureSucceeded(
            dispatched = dispatched,
            callbackCompleted = completed,
            elapsedMs = SystemClock.uptimeMillis() - startedAt,
            timeoutMs = GESTURE_COMPLETION_TIMEOUT_MS,
        )
    }

    private fun swipeLauncherPageLeft(): Boolean {
        val metrics = resources.displayMetrics
        if (metrics.widthPixels <= 0 || metrics.heightPixels <= 0) return false
        val path = Path().apply {
            moveTo(metrics.widthPixels * 0.82f, metrics.heightPixels * 0.52f)
            lineTo(metrics.widthPixels * 0.18f, metrics.heightPixels * 0.52f)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 320))
            .build()
        val completion = CountDownLatch(1)
        var completed = false
        val dispatched = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    completed = true
                    completion.countDown()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    completion.countDown()
                }
            },
            null,
        )
        if (!dispatched) return false
        return try {
            completion.await(GESTURE_COMPLETION_TIMEOUT_MS, TimeUnit.MILLISECONDS) && completed
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    private fun tapNodeCenter(node: AccessibilityNodeInfo): Boolean {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.width() <= 0 || bounds.height() <= 0) return false
        return tapScreen(bounds.exactCenterX(), bounds.exactCenterY())
    }

    private fun findTopCreateActionNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val rootBounds = Rect().also { root.getBoundsInScreen(it) }
        val targets = setOf("create", "создать", "add", "добавить", "+")
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var bestNode: AccessibilityNodeInfo? = null
        var bestScore = Int.MIN_VALUE

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val text = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            val viewId = node.viewIdResourceName?.toString().orEmpty()
            val label = when {
                text.isNotBlank() -> text
                desc.isNotBlank() -> desc
                else -> ""
            }
            val isBottomCreate = viewId.endsWith(":id/menu_creation")
            if (label.lowercase() in targets || isBottomCreate) {
                val bounds = Rect().also { node.getBoundsInScreen(it) }
                val inTopBand = bounds.centerY() < rootBounds.height() / 4
                val inRightHalf = bounds.centerX() >= rootBounds.centerX()
                if (isBottomCreate || (inTopBand && inRightHalf)) {
                    val score = (if (isBottomCreate) 1_000_000 else 0) + bounds.centerX() - bounds.top
                    if (score > bestScore) {
                        bestScore = score
                        bestNode = node
                    }
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let(queue::addLast)
            }
        }
        return bestNode
    }

    private fun clickPinterestPinEntryAndVerifyMediaPicker(): Boolean {
        val root = rootInActiveWindow ?: return false
        if (!isPinterestCreateMenuScreen()) return false
        val rootBounds = Rect().also(root::getBoundsInScreen)
        val candidate = findPinterestCreateMenuPinCandidate(root, rootBounds) ?: return false
        val generationBeforeClick = windowContentGeneration
        val fingerprintBeforeClick = activeTreeFingerprint()
        if (!clickNode(candidate)) return false
        val freshTree = waitForFreshPinterestTree(generationBeforeClick, fingerprintBeforeClick, 3500)
        val screen = detectPinterestScreen()
        val exactMediaVisible = hasExactPreparedMediaTileCandidate(preparedMediaPath, preparedMediaName)
        return PinterestCreateTransitionPolicy.isVerifiedMediaPickerTransition(freshTree, screen, exactMediaVisible)
    }

    private fun collectPinterestCreateMenuSignals(root: AccessibilityNodeInfo): List<PinterestCreateMenuNodeSignal> {
        val result = mutableListOf<PinterestCreateMenuNodeSignal>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val bounds = Rect().also(node::getBoundsInScreen)
            val label = node.text?.toString()?.takeIf(String::isNotBlank)
                ?: node.contentDescription?.toString().orEmpty()
            result += PinterestCreateMenuNodeSignal(
                label = label,
                viewId = node.viewIdResourceName?.toString().orEmpty(),
                visibleToUser = node.isVisibleToUser,
                clickable = node.isClickable,
                width = bounds.width(),
                height = bounds.height(),
            )
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::addLast)
        }
        return result
    }

    private fun findPinterestCreateMenuPinCandidate(
        root: AccessibilityNodeInfo,
        rootBounds: Rect,
    ): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val bounds = Rect().also(node::getBoundsInScreen)
            val label = node.text?.toString()?.takeIf(String::isNotBlank)
                ?: node.contentDescription?.toString().orEmpty()
          val signal = PinterestCreateMenuNodeSignal(
              label, node.viewIdResourceName?.toString().orEmpty(), node.isVisibleToUser,
              node.isClickable, bounds.width(), bounds.height(),
          )
          // The feed remains exposed behind Pinterest's create bottom sheet and
          // can contain large clickable Views whose accessible label is exactly
          // "Pin". The owned sheet exposes its real Pin entry as a Button. Never
          // let a background feed card win the breadth-first traversal.
          val isOwnedSheetButton = node.className?.toString() == "android.widget.Button"
          if (isOwnedSheetButton &&
              PinterestCreateMenuPolicy.isActionablePinCandidate(signal, rootBounds.width(), rootBounds.height())
          ) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::addLast)
        }
        return null
    }

    private fun hasExactPreparedMediaTileCandidate(expectedPath: String?, expectedName: String?): Boolean {
        val root = rootInActiveWindow ?: return false
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.isVisibleToUser) {
                val label = node.text?.toString()?.takeIf(String::isNotBlank)
                    ?: node.contentDescription?.toString().orEmpty()
                if (pinterestMediaLabelScore(label, expectedPath, expectedName) > 0) return true
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::addLast)
        }
        return false
    }

    private fun clickPinterestGalleryEntry(): Boolean {
        if (clickExactText(listOf("Upload", "Select", "Выбрать", "Загрузить", "Gallery", "Photos", "Галерея"), retries = 3)) {
            return true
        }
        return clickAnyText(listOf("Upload", "Select", "Выбрать", "Загрузить", "Gallery", "Photos", "Галерея"), retries = 2)
    }

    private fun clickPinterestMediaTileCandidate(expectedPath: String?, expectedName: String?): Boolean {
        val phaseStartedAt = SystemClock.uptimeMillis()
        repeat(3) {
            if (checkPublishTimeout()) return false
            if (!PinterestBoundedInteractionPolicy.hasBudget(
                    phaseStartedAt, SystemClock.uptimeMillis(), MEDIA_SELECTION_PHASE_TIMEOUT_MS,
                )
            ) return false
            val root = rootInActiveWindow ?: return false
            val rootBounds = Rect().also { root.getBoundsInScreen(it) }
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            var bestNode: AccessibilityNodeInfo? = null
            var bestScore = Int.MIN_VALUE
            val excluded = listOf(
                "camera", "pin camera", "close", "flash", "video", "photo", "record",
                "камера", "закрыть", "вспышка", "видео", "фото", "запись",
                "create", "создать", "next", "далее", "done", "готово",
            )

            var visitedNodes = 0
            while (queue.isNotEmpty() && visitedNodes++ < MEDIA_SELECTION_NODE_LIMIT) {
                if (checkPublishTimeout()) return false
                if (!PinterestBoundedInteractionPolicy.hasBudget(
                        phaseStartedAt, SystemClock.uptimeMillis(), MEDIA_SELECTION_PHASE_TIMEOUT_MS,
                    )
                ) return false
                val node = queue.removeFirst()
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                val className = node.className?.toString().orEmpty()
                val text = node.text?.toString().orEmpty()
                val desc = node.contentDescription?.toString().orEmpty()
                val label = listOf(text, desc).firstOrNull { it.isNotBlank() }.orEmpty()
                val centerY = bounds.centerY()
                val centerX = bounds.centerX()
                val inGalleryBand =
                    centerY > rootBounds.height() / 5 &&
                        centerY < rootBounds.height() - rootBounds.height() / 6
                val sizeOk = bounds.width() > rootBounds.width() / 6 && bounds.height() > rootBounds.width() / 6
                val mediaScore = pinterestMediaLabelScore(label, expectedPath, expectedName)
                val excludedLabel = mediaScore <= 0 && label.isNotBlank() && excluded.any { marker -> label.contains(marker, ignoreCase = true) }
                val candidate =
                    mediaScore > 0 &&
                    inGalleryBand &&
                        sizeOk &&
                        !excludedLabel &&
                        (
                            className == "android.widget.ImageView" ||
                                className == "android.widget.FrameLayout" ||
                                className == "android.view.View" ||
                                className.contains("Image") ||
                                className.contains("FrameLayout")
                        )
                if (candidate) {
                    val score = mediaScore * 1_000_000 + (rootBounds.height() - bounds.top) + centerX
                    if (score > bestScore) {
                        bestScore = score
                        bestNode = node
                    }
                }
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let(queue::addLast)
                }
            }

            if (bestNode != null) {
                val nextEnabledBeforeClick = findPinterestEnabledNextNode(root) != null
                var generationBeforeClick = windowContentGeneration
                var fingerprintBeforeClick = activeTreeFingerprint()
                if (clickNode(bestNode) && waitForPinterestMediaSelection(
                        generationBeforeClick, fingerprintBeforeClick, nextEnabledBeforeClick,
                        650, phaseStartedAt,
                    )
                ) {
                    pinterestExactMediaSelectionConfirmed = true
                    return true
                }

                generationBeforeClick = windowContentGeneration
                fingerprintBeforeClick = activeTreeFingerprint()
                if (tapNodeCenter(bestNode) && waitForPinterestMediaSelection(
                        generationBeforeClick, fingerprintBeforeClick, nextEnabledBeforeClick,
                        1400, phaseStartedAt,
                    )
                ) {
                    pinterestExactMediaSelectionConfirmed = true
                    return true
                }
            }
            SystemClock.sleep(400)
        }
        return false
    }

    private fun waitForPinterestMediaSelection(
        generationBeforeClick: Long,
        fingerprintBeforeClick: String,
        nextEnabledBeforeClick: Boolean,
        timeoutMs: Long,
        phaseStartedAt: Long,
    ): Boolean {
        val startedAt = SystemClock.uptimeMillis()
        while (PinterestBoundedInteractionPolicy.hasBudget(startedAt, SystemClock.uptimeMillis(), timeoutMs) &&
            PinterestBoundedInteractionPolicy.hasBudget(
                phaseStartedAt, SystemClock.uptimeMillis(), MEDIA_SELECTION_PHASE_TIMEOUT_MS,
            )
        ) {
            if (checkPublishTimeout()) return false
            val freshState = PinterestFreshTreeGuard.hasFreshTree(
                generationBeforeClick, windowContentGeneration,
                fingerprintBeforeClick, activeTreeFingerprint(),
            )
            val state = currentPinterestMediaSelectionState(preparedMediaPath, preparedMediaName)
            if (PinterestMediaSelectionPolicy.selectionConfirmed(freshState, nextEnabledBeforeClick, state)) return true
            SystemClock.sleep(150)
        }
        return false
    }

    private fun currentPinterestMediaSelectionState(
        expectedPath: String?,
        expectedName: String?,
    ): PinterestMediaSelectionState {
        val root = rootInActiveWindow
        val exactNode = root?.let { findExactPreparedMediaNode(it, expectedPath, expectedName) }
        val exactLabel = exactNode?.let { node ->
            node.text?.toString()?.takeIf(String::isNotBlank)
                ?: node.contentDescription?.toString().orEmpty()
        }.orEmpty()
        return PinterestMediaSelectionState(
            exactTileSelected = exactNode?.isSelected == true ||
                PinterestMediaSelectionPolicy.isExactSelectedLabel(exactLabel, expectedPath, expectedName),
            nextEnabled = root?.let(::findPinterestEnabledNextNode) != null,
        )
    }

    private fun findExactPreparedMediaNode(
        root: AccessibilityNodeInfo,
        expectedPath: String?,
        expectedName: String?,
    ): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visitedNodes = 0
        while (queue.isNotEmpty() && visitedNodes++ < MEDIA_SELECTION_NODE_LIMIT) {
            val node = queue.removeFirst()
            val label = node.text?.toString()?.takeIf(String::isNotBlank)
                ?: node.contentDescription?.toString().orEmpty()
            if (node.isVisibleToUser && pinterestMediaLabelScore(label, expectedPath, expectedName) > 0) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::addLast)
        }
        return null
    }

    private fun findPinterestEnabledNextNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val labels = listOf("Next", "Далее", "Continue", "Продолжить", "Done", "Готово", "Add", "Добавить", "Use", "Выбрать")
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visitedNodes = 0
        while (queue.isNotEmpty() && visitedNodes++ < MEDIA_SELECTION_NODE_LIMIT) {
            val node = queue.removeFirst()
            val label = node.text?.toString()?.takeIf(String::isNotBlank)
                ?: node.contentDescription?.toString().orEmpty()
            val viewId = node.viewIdResourceName?.toString().orEmpty()
            val exactLabel = labels.any { label.trim().equals(it, ignoreCase = true) }
            val exactId = viewId.endsWith(":id/end_container_text_button") || viewId.endsWith("/end_container_text_button")
            if (node.isVisibleToUser && node.isEnabled && node.isClickable && (exactId || exactLabel)) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::addLast)
        }
        return null
    }

    private fun continueFromPinterestMediaStep(): Boolean {
        val initialScreen = detectPinterestScreen()
        // Pinterest may advance to the composer immediately after the exact media tile is
        // selected, without exposing a separate enabled Next action. The tile interaction
        // already proved a fresh tree and the exact current-job media before setting
        // pinterestExactMediaSelectionConfirmed, so accept only that proven transition.
        if (pinterestExactMediaSelectionConfirmed &&
            (initialScreen == PinterestScreenKind.COMPOSER ||
                initialScreen == PinterestScreenKind.BOARD_SELECTION)
        ) {
            if (initialScreen == PinterestScreenKind.COMPOSER) {
                pinterestComposerReachedViaVerifiedPipeline = true
            }
            return true
        }
        if (initialScreen != PinterestScreenKind.MEDIA_STEP) return false
        var state = currentPinterestMediaSelectionState(preparedMediaPath, preparedMediaName)
        var exactSelectionConfirmed = pinterestExactMediaSelectionConfirmed || state.exactTileSelected
        if (!exactSelectionConfirmed) {
            if (!clickPinterestMediaTileCandidate(preparedMediaPath, preparedMediaName)) return false
            exactSelectionConfirmed = pinterestExactMediaSelectionConfirmed
            state = currentPinterestMediaSelectionState(preparedMediaPath, preparedMediaName)
        }
        if (!PinterestMediaSelectionPolicy.canClickNext(exactSelectionConfirmed, state)) return false
        val root = rootInActiveWindow ?: return false
        val next = findPinterestEnabledNextNode(root) ?: return false
        val generationBeforeClick = windowContentGeneration
        val fingerprintBeforeClick = activeTreeFingerprint()
        if (!clickNode(next)) return false
        var freshTree = waitForFreshPinterestTree(generationBeforeClick, fingerprintBeforeClick, 1200)
        var screenAfterNext = detectPinterestScreen()
        if (PinterestMediaSelectionPolicy.nextTransitionAccepted(freshTree, screenAfterNext)) {
            if (screenAfterNext == PinterestScreenKind.COMPOSER) {
                pinterestComposerReachedViaVerifiedPipeline = true
            }
            return true
        }
        if (!PinterestMediaSelectionPolicy.shouldAttemptNextCenterFallback(false, screenAfterNext)) return false

        val fallbackGeneration = windowContentGeneration
        val fallbackFingerprint = activeTreeFingerprint()
        if (!tapNodeCenter(next)) return false
        freshTree = waitForFreshPinterestTree(fallbackGeneration, fallbackFingerprint, 2500)
        screenAfterNext = detectPinterestScreen()
        val accepted = PinterestMediaSelectionPolicy.nextTransitionAccepted(freshTree, screenAfterNext)
        if (accepted && screenAfterNext == PinterestScreenKind.COMPOSER) {
            pinterestComposerReachedViaVerifiedPipeline = true
        }
        return accepted
    }

    private fun exitPinterestTransientScreens() {
        repeat(3) {
            if (checkPublishTimeout()) return
            dismissSystemInterferenceIfPresent()
            when (detectPinterestScreen()) {
                PinterestScreenKind.CAMERA -> {
                    step("pinterest:exit-camera")
                    if (!clickAnyText(listOf("Close pin camera", "Закрыть камеру пина", "Close", "Закрыть"), retries = 2)) {
                        performGlobalAction(GLOBAL_ACTION_BACK)
                    }
                    SystemClock.sleep(800)
                }
                PinterestScreenKind.HELP -> {
                    dismissPinterestHelpOverlayIfPresent()
                    SystemClock.sleep(500)
                }
                else -> return
            }
        }
    }

    private fun waitForPackage(packageName: String, timeoutMs: Long = 4000): Boolean {
        val start = SystemClock.uptimeMillis()
        while (SystemClock.uptimeMillis() - start < timeoutMs) {
            if (checkPublishTimeout()) return false
            dismissSystemInterferenceIfPresent()
            if (currentPackageName() == packageName) {
                return true
            }
            SystemClock.sleep(250)
        }
        return currentPackageName() == packageName
    }

    private fun waitForStablePackage(packageName: String, stableMs: Long, timeoutMs: Long): Boolean {
        val start = SystemClock.uptimeMillis()
        var stableStart: Long? = null
        while (SystemClock.uptimeMillis() - start < timeoutMs) {
            if (checkPublishTimeout()) return false
            dismissSystemInterferenceIfPresent()
            if (isAgentUiVisible()) {
                stableStart = null
                moveAwayFromAgentUiIfNeeded()
            }
            val current = currentPackageName()
            if (current == packageName) {
                if (stableStart == null) {
                    stableStart = SystemClock.uptimeMillis()
                }
                if (SystemClock.uptimeMillis() - stableStart >= stableMs) {
                    return true
                }
            } else {
                stableStart = null
            }
            SystemClock.sleep(200)
        }
        return false
    }

    private fun ensurePackageActive(packageName: String, message: String): Boolean {
        if (isAgentUiVisible()) {
            moveAwayFromAgentUiIfNeeded()
        }
        if (currentPackageName() == packageName) return true
        return fail("$message. Current package: ${currentPackageName() ?: "unknown"}. Visible: ${visibleLabelsSummary()}")
    }

  private fun currentPackageName(): String? {
      return activeAccessibilityRoot()?.packageName?.toString()
  }

  private fun activeAccessibilityRoot(): AccessibilityNodeInfo? {
      rootInActiveWindow?.let { return it }
      return windows.firstOrNull {
          it.isActive && it.type == AccessibilityWindowInfo.TYPE_APPLICATION
      }?.root
  }

    private fun moveAwayFromAgentUiIfNeeded() {
        if (!isAgentUiVisible()) return
        performGlobalAction(GLOBAL_ACTION_HOME)
        val start = SystemClock.uptimeMillis()
        while (SystemClock.uptimeMillis() - start < 2500) {
            if (checkPublishTimeout()) return
            if (!isAgentUiVisible()) {
                return
            }
            SystemClock.sleep(200)
        }
    }

    private fun isAgentUiVisible(): Boolean {
        val agentPackage = applicationContext.packageName
        if (currentPackageName() == agentPackage) return true
        val labels = visibleLabels(limit = 10)
        val markers = listOf(
            "Mobile Poster Agent",
            "Save config",
            "Start agent",
            "Open accessibility settings",
            "Allow notifications",
            "Foreground service started",
        )
        return labels.any { label -> markers.any { marker -> label.contains(marker, ignoreCase = true) } }
    }

    private fun dismissSystemInterferenceIfPresent(): Boolean {
        val labels = visibleLabels()
        val usbMarkers = listOf(
            "Режим работы USB",
            "Без передачи данных",
            "Передача файлов",
            "Передача фото",
            "USB preferences",
            "USB controlled by",
            "File transfer",
            "No data transfer",
        )
        if (labels.none { label -> usbMarkers.any { marker -> label.contains(marker, ignoreCase = true) } }) {
            val deleteMarkers = listOf(
                "Удаление приложения очистит все его данные",
                "Удалить приложение",
                "Remove app",
                "Delete app",
            )
            if (labels.any { label -> deleteMarkers.any { marker -> label.contains(marker, ignoreCase = true) } }) {
                step("system:delete-dialog")
                performGlobalAction(GLOBAL_ACTION_BACK)
                SystemClock.sleep(500)
                if (clickAnyText(
                    listOf("Отмена", "Cancel", "Позже", "Close", "Закрыть"),
                    retries = 2,
                )) {
                    return true
                }
                performGlobalAction(GLOBAL_ACTION_HOME)
                SystemClock.sleep(500)
                return false
            }
            return false
        }
        return clickAnyText(
            listOf("Отмена", "Без передачи данных", "Cancel", "No data transfer"),
            retries = 2,
        )
    }

    private fun visibleLabelsSummary(limit: Int = 14): String {
        val labels = visibleLabels(limit)
        return if (labels.isEmpty()) "no visible labels" else labels.joinToString(" | ")
    }

    private fun visibleLabels(limit: Int = 20): List<String> {
        val root = rootInActiveWindow ?: return emptyList()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        val labels = linkedSetOf<String>()

        while (queue.isNotEmpty() && labels.size < limit) {
            val node = queue.removeFirst()
            val text = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            val label = when {
                text.isNotBlank() -> text
                desc.isNotBlank() -> desc
                else -> ""
            }
            if (node.isVisibleToUser && label.isNotBlank()) {
                labels.add(label)
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let(queue::addLast)
            }
        }

        return labels.toList()
    }

    private fun visibleAccessibilityLabelSignals(limit: Int = 80): List<PinterestAccessibilityLabelSignal> {
        val root = rootInActiveWindow ?: return emptyList()
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, String>>()
        queue.add(root to "root")
        val signals = mutableListOf<PinterestAccessibilityLabelSignal>()
        while (queue.isNotEmpty() && signals.size < limit) {
            val (node, nodePath) = queue.removeFirst()
            if (node.isVisibleToUser) {
                val text = node.text?.toString()?.trim().orEmpty()
                val description = node.contentDescription?.toString()?.trim().orEmpty()
                if (text.isNotBlank() || description.isNotBlank()) {
                    signals += PinterestAccessibilityLabelSignal(text, description, nodePath)
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.addLast(it to "$nodePath/$i") }
            }
        }
        return signals
    }

    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
            current = current.parent
        }
        return tapNodeCenter(node)
    }

    private fun describeMatchingNodes(candidates: List<String>, limit: Int = 8, includeContains: Boolean = true): String {
        val root = rootInActiveWindow ?: return "no-root-window"
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        val matches = mutableListOf<String>()
        while (queue.isNotEmpty() && matches.size < limit) {
            val node = queue.removeFirst()
            val text = node.text?.toString().orEmpty()
            val desc = node.contentDescription?.toString().orEmpty()
            val label = listOf(text, desc).firstOrNull { it.isNotBlank() }.orEmpty()
            val matched =
                if (includeContains) {
                    label.isNotBlank() && candidates.any { label.contains(it, ignoreCase = true) }
                } else {
                    label.isNotBlank() && candidates.any { label.equals(it, ignoreCase = true) }
                }
            if (matched) {
                val bounds = Rect().also { node.getBoundsInScreen(it) }
                matches += "${label}[${node.className}; clickable=${node.isClickable}; bounds=${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}]"
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let(queue::addLast)
            }
        }
        return if (matches.isEmpty()) "no-matching-nodes" else matches.joinToString(" || ")
    }

    private fun step(label: String) {
        if (trace.lastOrNull() != label) {
            trace.add(label)
            if (debugStepSnapshotBudget > 0) {
                debugStepSnapshotBudget -= 1
                try {
                    debugStepSnapshotCallback?.invoke(label)
                } catch (_: Exception) {
                    // Keep publish flow alive even if debug capture/upload fails.
                }
            }
        }
    }

    private fun eventOnly(label: String) {
        if (trace.lastOrNull() != label) trace.add(label)
    }

    private fun encodeUrlValue(value: String): String {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.toString())
    }

    private fun fail(message: String): Boolean {
        lastFailureReason = if (trace.isEmpty()) {
            message
        } else {
            "$message. Trace: ${trace.takeLast(10).joinToString(" -> ")}"
        }
        return false
    }

    private fun needsReview(message: String): Boolean = fail("$NEEDS_REVIEW_PREFIX $message")

    private fun readyToPublish(): Boolean {
        step("pinterest:stopped-before-publish")
        lastFailureReason = "$READY_TO_PUBLISH_PREFIX Pinterest editor is ready; Publish was not pressed"
        return false
    }

    private fun socialReadyToPublish(message: String): Boolean {
        step("social:stopped-before-final-action")
        lastFailureReason = "$READY_TO_PUBLISH_PREFIX $message"
        return false
    }

    companion object {
        private const val PUBLISH_TIMEOUT_MS = 45_000L
        private const val DEBUG_STEP_SNAPSHOT_LIMIT = 12
        private const val GESTURE_COMPLETION_TIMEOUT_MS = 1_000L
        private const val MEDIA_SELECTION_PHASE_TIMEOUT_MS = 7_500L
        private const val TIKTOK_CREATE_CENTER_X = 360f
        private const val TIKTOK_CREATE_CENTER_Y = 1_375f
        private const val INSTAGRAM_PROFILE_CENTER_X = 648f
        private const val INSTAGRAM_PROFILE_CENTER_Y = 1_380f
        private const val THREADS_CREATE_CENTER_X = 360f
        private const val THREADS_CREATE_CENTER_Y = 1_368f
        private const val THREADS_MEDIA_CENTER_X = 138f
        private const val THREADS_MEDIA_CENTER_Y = 340f
        private const val THREADS_NEWEST_MEDIA_CENTER_X = 360f
        private const val THREADS_NEWEST_MEDIA_CENTER_Y = 350f
        private const val THREADS_POST_CENTER_X = 635f
        private const val THREADS_POST_CENTER_Y = 1_358f
        private const val THREADS_RECEIPT_TIMEOUT_MS = 30_000L
        private const val YOUTUBE_PROFILE_CENTER_X = 648f
        private const val YOUTUBE_PROFILE_CENTER_Y = 1_370f
        private const val YOUTUBE_CREATE_CENTER_X = 360f
        private const val YOUTUBE_CREATE_CENTER_Y = 1_370f
        private const val YOUTUBE_GALLERY_CENTER_X = 72f
        private const val YOUTUBE_GALLERY_CENTER_Y = 1_180f
        private const val YOUTUBE_NEWEST_MEDIA_CENTER_X = 120f
        private const val YOUTUBE_NEWEST_MEDIA_CENTER_Y = 360f
        private const val YOUTUBE_RECEIPT_TIMEOUT_MS = 120_000L
        private const val PINTEREST_BOARD_PENDING_TIMEOUT_MS = 7_000L
        private const val SOCIAL_PROFILE_TREE_SETTLE_MS = 1_200L
        private const val SOCIAL_PROFILE_TREE_ATTEMPTS = 5
        private const val SOCIAL_PROFILE_TREE_RETRY_MS = 700L
        private const val TIKTOK_RECEIPT_TIMEOUT_MS = 20_000L
        private const val INSTAGRAM_RECEIPT_TIMEOUT_MS = 600_000L
        private const val INSTAGRAM_RECEIPT_POLL_MS = 5_000L
        private const val TIKTOK_EDITOR_LOAD_TIMEOUT_MS = 20_000L
        private const val SOCIAL_PUBLISH_TIMEOUT_MS = 660_000L
        private const val MEDIA_SELECTION_NODE_LIMIT = 500
        const val READY_TO_PUBLISH_PREFIX = "READY_TO_PUBLISH:"
        const val NEEDS_REVIEW_PREFIX = "NEEDS_REVIEW:"

        @Volatile
        var instance: AgentAccessibilityService? = null
            private set
    }
}
