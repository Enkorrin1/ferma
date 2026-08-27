package com.elevium.mobileposteragent.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PinterestScreenClassifierTest {
    @Test
    fun homeFeedAddLikeTextCannotOwnMediaStep() {
        assertFalse(
            PinterestMediaStepOwnershipPolicy.isOwnedMediaStep(
                listOf("Home", "Create", "Add your interests", "All recommended Pins"),
                exactPreparedMediaVisible = false,
            ),
        )
        assertFalse(
            PinterestMediaStepOwnershipPolicy.isOwnedMediaStep(
                listOf("Next", "Home", "Create"),
                exactPreparedMediaVisible = false,
            ),
        )
        assertTrue(
            PinterestMediaStepOwnershipPolicy.isOwnedMediaStep(
                listOf("Photos", "Next"),
                exactPreparedMediaVisible = true,
            ),
        )
    }
    private val composerFields = listOf(
        PinterestEditableSignal("Title. Tell everyone what your Pin is about", true),
        PinterestEditableSignal("Description. Add a description, mention, or hashtags to your Pin", true),
        PinterestEditableSignal("Link. Add your link here", true),
    )

    @Test fun createMenuWithStableComposerNodesResolvesComposer() {
        assertTrue(PinterestScreenClassifier.hasStableComposerSignature(composerFields))
        assertEquals(PinterestScreenKind.COMPOSER, PinterestScreenClassifier.resolve(
            PinterestScreenSignals(composer = true, createMenu = true),
        ))
    }

    @Test fun boardOverlayOverridesUnderlyingComposer() {
        assertEquals(PinterestScreenKind.BOARD_SELECTION, PinterestScreenClassifier.resolve(
            PinterestScreenSignals(composer = true, boardSelection = true),
        ))
    }

    @Test fun hiddenEditableFieldsDoNotFormComposerSignature() {
        assertFalse(PinterestScreenClassifier.hasStableComposerSignature(listOf(
            PinterestEditableSignal("Title", false),
            PinterestEditableSignal("Description", false),
            PinterestEditableSignal("Link", false),
        )))
    }

    @Test fun composerPrecedenceSurvivesShareFallbackSignals() {
        assertEquals(PinterestScreenKind.COMPOSER, PinterestScreenClassifier.resolve(
            PinterestScreenSignals(
                composer = PinterestScreenClassifier.hasStableComposerSignature(composerFields),
                helpModalOwned = true, helpSpecificControl = true,
                createMenu = true, mediaStep = true, home = true,
            ),
        ))
    }

    @Test fun ordinaryForYouFeedResolvesHome() {
        assertEquals(PinterestScreenKind.HOME, PinterestScreenClassifier.resolve(
            PinterestScreenSignals(home = true),
        ))
    }

    @Test fun genericOrUnownedHelpSignalsAreIgnored() {
        assertEquals(PinterestScreenKind.HOME, PinterestScreenClassifier.resolve(
            PinterestScreenSignals(home = true, helpSpecificControl = true),
        ))
        assertEquals(PinterestScreenKind.HOME, PinterestScreenClassifier.resolve(
            PinterestScreenSignals(home = true, helpModalOwned = true),
        ))
    }

    @Test fun strictHelpModalOverridesHome() {
        assertEquals(PinterestScreenKind.HELP, PinterestScreenClassifier.resolve(
            PinterestScreenSignals(home = true, helpModalOwned = true, helpSpecificControl = true),
        ))
    }

    @Test fun homeFallbackAdvancesCreateAndHelpOnlyDismissesStrictModal() {
        assertEquals(PinterestScreenAction.ADVANCE_CREATE,
            PinterestScreenActionPolicy.actionFor(PinterestScreenKind.HOME))
        assertEquals(PinterestScreenAction.DISMISS_HELP,
            PinterestScreenActionPolicy.actionFor(PinterestScreenKind.HELP))
    }

    @Test fun ambiguousCreateLabelIsNotComposerReady() {
        assertFalse(PinterestScreenClassifier.hasStableComposerSignature(
            listOf(
                PinterestEditableSignal("Create", true),
                PinterestEditableSignal("Untitled editable field", true),
            ),
        ))
        assertEquals(PinterestScreenKind.UNKNOWN, PinterestScreenClassifier.resolve(PinterestScreenSignals()))
    }

    @Test fun existingBoardTransitionRequiresFreshTree() {
        assertFalse(PinterestFreshTreeGuard.hasFreshTree(7, 7, "board", "composer"))
        assertFalse(PinterestFreshTreeGuard.hasFreshTree(7, 8, "board", "board"))
        assertTrue(PinterestFreshTreeGuard.hasFreshTree(7, 8, "board", "composer"))
    }

    @Test fun createBoardIsNeverSelectedAsExistingBoard() {
        assertEquals(null, PinterestBoardPolicy.existingBoardLabel(
            listOf("Create board", "Создать доску"), "Create board",
        ))
        assertEquals("Farm", PinterestBoardPolicy.existingBoardLabel(
            listOf("Create board", "Farm"), "Farm",
        ))
    }

    @Test fun verifiedEditorCanBecomeReady() {
        assertTrue(PinterestEditorVerification.isVerified(
            stableComposerSignature = true,
            boardOverlayVisible = false,
            mediaPreviewVisible = true,
            requiredFieldReadbackMatches = true,
        ))
        assertEquals(PinterestComposerAction.READY_TO_PUBLISH,
            PinterestTerminalPolicy.composerAction(true, editorVerified = true, evidenceAvailable = true))
        assertEquals(PinterestComposerAction.NEEDS_REVIEW,
            PinterestTerminalPolicy.composerAction(true, editorVerified = false, evidenceAvailable = true))
        assertEquals(PinterestComposerAction.NEEDS_REVIEW,
            PinterestTerminalPolicy.composerAction(true, editorVerified = true, evidenceAvailable = false))
    }

    @Test fun dryRunStopsReadyWithoutFinalPublishAction() {
        assertEquals(PinterestComposerAction.READY_TO_PUBLISH,
            PinterestTerminalPolicy.composerAction(stopBeforePublish = true))
        assertEquals(PinterestComposerAction.CONTINUE_TO_PUBLISH,
            PinterestTerminalPolicy.composerAction(stopBeforePublish = false))
    }

    @Test fun pinCloseupIsNotAnOwnedCreateMenu() {
        val nodes = listOf(
            PinterestCreateMenuNodeSignal("Back", "com.pinterest:id/closeup_back_button", true, true, 96, 96),
            PinterestCreateMenuNodeSignal("Pin", "com.pinterest:id/pin_image_view", true, true, 704, 1314),
            PinterestCreateMenuNodeSignal("Search image", "com.pinterest:id/flashlight_search_button", true, true, 88, 41),
        )
        assertFalse(PinterestCreateMenuPolicy.isOwnedMenu(nodes))
        assertFalse(PinterestCreateMenuPolicy.isActionablePinCandidate(nodes[1], 720, 1440))
    }

    @Test fun ownedCreateMenuHasBoundedActionablePin() {
        val nodes = listOf(
            PinterestCreateMenuNodeSignal("Create something", "title", true, false, 500, 80),
            PinterestCreateMenuNodeSignal("Pin", "menu_pin", true, true, 300, 120),
            PinterestCreateMenuNodeSignal("Collage", "menu_collage", true, true, 300, 120),
            PinterestCreateMenuNodeSignal("Board", "menu_board", true, true, 300, 120),
        )
        assertTrue(PinterestCreateMenuPolicy.isOwnedMenu(nodes))
        assertTrue(PinterestCreateMenuPolicy.isActionablePinCandidate(nodes[1], 720, 1440))
    }

    @Test fun mediaPickerTransitionRequiresFreshTreeAndExactMedia() {
        assertFalse(PinterestCreateTransitionPolicy.isVerifiedMediaPickerTransition(false, PinterestScreenKind.MEDIA_STEP, true))
        assertFalse(PinterestCreateTransitionPolicy.isVerifiedMediaPickerTransition(true, PinterestScreenKind.CREATE_MENU, true))
        assertFalse(PinterestCreateTransitionPolicy.isVerifiedMediaPickerTransition(true, PinterestScreenKind.MEDIA_STEP, false))
        assertTrue(PinterestCreateTransitionPolicy.isVerifiedMediaPickerTransition(true, PinterestScreenKind.MEDIA_STEP, true))
    }

    @Test fun exactBoardFreshHomeContinuesCreateWithoutClaimingSuccess() {
        assertEquals(PinterestBoardPostSelectionAction.CONTINUE_HOME_CREATE,
            PinterestBoardPostSelectionPolicy.action(true, true, PinterestScreenKind.HOME))
        assertEquals(PinterestBoardPostSelectionAction.VERIFIED_COMPOSER,
            PinterestBoardPostSelectionPolicy.action(true, true, PinterestScreenKind.COMPOSER))
    }

    @Test fun staleHomeOrWrongBoardNeedsReview() {
        assertEquals(PinterestBoardPostSelectionAction.NEEDS_REVIEW,
            PinterestBoardPostSelectionPolicy.action(true, false, PinterestScreenKind.HOME))
        assertEquals(PinterestBoardPostSelectionAction.NEEDS_REVIEW,
            PinterestBoardPostSelectionPolicy.action(false, true, PinterestScreenKind.HOME))
        assertEquals(PinterestBoardPostSelectionAction.NEEDS_REVIEW,
            PinterestBoardPostSelectionPolicy.action(true, true, PinterestScreenKind.UNKNOWN))
    }

    @Test fun exactBoardFreshMediaStepContinuesSelectionButIsNeverReady() {
        assertEquals(PinterestBoardPostSelectionAction.CONTINUE_MEDIA_SELECTION,
            PinterestBoardPostSelectionPolicy.action(true, true, PinterestScreenKind.MEDIA_STEP))
        assertEquals(PinterestBoardPostSelectionAction.NEEDS_REVIEW,
            PinterestBoardPostSelectionPolicy.action(true, false, PinterestScreenKind.MEDIA_STEP))
        assertEquals(PinterestBoardPostSelectionAction.NEEDS_REVIEW,
            PinterestBoardPostSelectionPolicy.action(false, true, PinterestScreenKind.MEDIA_STEP))
        assertFalse(PinterestMediaSelectionPolicy.canClickNext(false, PinterestMediaSelectionState(false, true)))
        assertEquals(PinterestComposerAction.NEEDS_REVIEW,
            PinterestTerminalPolicy.composerAction(true, editorVerified = false, evidenceAvailable = true))
    }

    @Test fun accessibilitySelectedLabelRequiresExactCurrentJobMedia() {
        val path = "/storage/emulated/0/Pictures/MobilePosterAgent/mobileposter_ed4b.png"
        assertTrue(PinterestMediaSelectionPolicy.isExactSelectedLabel(
            "$path selected, double tap to delete, drag to reorder", path, "mobileposter_ed4b.png"))
        assertFalse(PinterestMediaSelectionPolicy.isExactSelectedLabel(
            "/storage/emulated/0/Pictures/MobilePosterAgent/mobileposter_other.png selected, double tap to delete",
            path, "mobileposter_ed4b.png"))
        assertFalse(PinterestMediaSelectionPolicy.isExactSelectedLabel(path, path, "mobileposter_ed4b.png"))
    }

    @Test fun ignoredNextFallsBackOnlyWhileFreshMediaTransitionIsStillMissing() {
        assertTrue(PinterestMediaSelectionPolicy.shouldAttemptNextCenterFallback(false, PinterestScreenKind.MEDIA_STEP))
        assertFalse(PinterestMediaSelectionPolicy.shouldAttemptNextCenterFallback(true, PinterestScreenKind.MEDIA_STEP))
        assertFalse(PinterestMediaSelectionPolicy.shouldAttemptNextCenterFallback(false, PinterestScreenKind.HOME))
        assertFalse(PinterestMediaSelectionPolicy.nextTransitionAccepted(false, PinterestScreenKind.COMPOSER))
        assertTrue(PinterestMediaSelectionPolicy.nextTransitionAccepted(true, PinterestScreenKind.COMPOSER))
    }

    @Test fun configuredBoardCanDeferOnlyAtFreshVerifiedComposerWithoutOverlay() {
        assertTrue(PinterestDeferredBoardPolicy.canDeferUntilFinalCreate(
            "FARM E2E TEST", PinterestScreenKind.COMPOSER, true, true, false))
        assertFalse(PinterestDeferredBoardPolicy.canDeferUntilFinalCreate(
            "FARM E2E TEST", PinterestScreenKind.COMPOSER, false, true, false))
        assertFalse(PinterestDeferredBoardPolicy.canDeferUntilFinalCreate(
            "FARM E2E TEST", PinterestScreenKind.COMPOSER, true, false, false))
        assertFalse(PinterestDeferredBoardPolicy.canDeferUntilFinalCreate(
            "FARM E2E TEST", PinterestScreenKind.COMPOSER, true, true, true))
        assertFalse(PinterestDeferredBoardPolicy.canDeferUntilFinalCreate(
            "FARM E2E TEST", PinterestScreenKind.BOARD_SELECTION, true, true, false))
    }

    @Test fun verifiedComposerSnapshotWithSameAttemptProofSkipsPickerBeforeRefetch() {
        assertTrue(PinterestVerifiedComposerBoardGatePolicy.canSkipPicker(
            true, true, true, false))
        assertFalse(PinterestVerifiedComposerBoardGatePolicy.canSkipPicker(
            true, false, true, false))
        assertFalse(PinterestVerifiedComposerBoardGatePolicy.canSkipPicker(
            true, true, false, false))
        assertFalse(PinterestVerifiedComposerBoardGatePolicy.canSkipPicker(
            true, true, true, true))
    }

    @Test fun attempt21ExactDirectShareTupleIsWiredToBoardGateSuccess() {
        val evaluation = PinterestDirectShareComposerPolicy.evaluate(
            shareLaunchRecorded = true,
            exactCurrentMediaShared = true,
            packageBeforeShare = "com.miui.home",
            packageAfterShare = "com.pinterest",
            generationBeforeShare = 20,
            generationAfterShare = 21,
            fingerprintBeforeShare = "before",
            fingerprintAfterShare = "after",
            currentScreen = PinterestScreenKind.COMPOSER,
            mediaPreviewVisible = true,
            boardOverlayVisible = false,
        )
        assertTrue(evaluation.qualifies)
        assertTrue(PinterestVerifiedComposerBoardGatePolicy.canSkipFromAttemptState(
            "FARM E2E TEST", evaluation.qualifies, false, false,
            PinterestScreenKind.COMPOSER, false))
    }

    @Test fun directShareBoardWiringStillFailsWithoutComposerOrWithOverlay() {
        assertFalse(PinterestVerifiedComposerBoardGatePolicy.canSkipFromAttemptState(
            "FARM E2E TEST", true, false, false, PinterestScreenKind.UNKNOWN, false))
        assertFalse(PinterestVerifiedComposerBoardGatePolicy.canSkipFromAttemptState(
            "FARM E2E TEST", true, false, false, PinterestScreenKind.COMPOSER, true))
        assertFalse(PinterestVerifiedComposerBoardGatePolicy.canSkipFromAttemptState(
            null, true, false, false, PinterestScreenKind.COMPOSER, false))
    }

    @Test fun terminalScreenshotFailureStillNeedsReviewAfterBoardGate() {
        assertEquals(PinterestComposerAction.NEEDS_REVIEW,
            PinterestTerminalPolicy.composerAction(
                stopBeforePublish = true, editorVerified = true, evidenceAvailable = false))
    }

    @Test fun exactBoardSelectionProofAllowsSameAttemptComposerWithoutSecondPicker() {
        assertTrue(PinterestExactBoardSelectionProofPolicy.canRecord(
            true, true, PinterestScreenKind.COMPOSER, false))
        assertTrue(PinterestExactBoardSelectionProofPolicy.allowsComposerWithoutSecondPicker(
            true, PinterestScreenKind.COMPOSER, false))
        assertFalse(PinterestExactBoardSelectionProofPolicy.allowsComposerWithoutSecondPicker(
            false, PinterestScreenKind.COMPOSER, false))
    }

    @Test fun exactBoardProofRejectsWrongBoardStaleTransitionAndVisibleOverlay() {
        assertFalse(PinterestExactBoardSelectionProofPolicy.canRecord(
            false, true, PinterestScreenKind.COMPOSER, false))
        assertFalse(PinterestExactBoardSelectionProofPolicy.canRecord(
            true, false, PinterestScreenKind.COMPOSER, false))
        assertFalse(PinterestExactBoardSelectionProofPolicy.canRecord(
            true, true, PinterestScreenKind.BOARD_SELECTION, true))
        assertFalse(PinterestExactBoardSelectionProofPolicy.allowsComposerWithoutSecondPicker(
            true, PinterestScreenKind.COMPOSER, true))
        assertFalse(PinterestExactBoardSelectionProofPolicy.allowsComposerWithoutSecondPicker(
            true, PinterestScreenKind.HOME, false))
    }

    @Test fun delayedNextLoopBoardTransitionPromotesOnlyPendingExactClick() {
        assertTrue(PinterestExactBoardSelectionProofPolicy.canRecord(
            true, true, PinterestScreenKind.COMPOSER, false))
        assertFalse(PinterestExactBoardSelectionProofPolicy.canRecord(
            true, false, PinterestScreenKind.BOARD_SELECTION, true))
        assertFalse(PinterestExactBoardSelectionProofPolicy.canRecord(
            false, true, PinterestScreenKind.COMPOSER, false))
        assertFalse(PinterestExactBoardSelectionProofPolicy.allowsComposerWithoutSecondPicker(
            false, PinterestScreenKind.COMPOSER, false))
    }

    @Test fun unavailableDirectShareDiagnosticIsStillEmittedWithoutSensitiveValues() {
        assertEquals(
            "shareRecorded=false,prePackageCategory=null,postPackagePinterest=false," +
                "generationAdvanced=false,fingerprintChanged=false,postFingerprintNonempty=false," +
                "exactMediaPreview=false,composer=false,boardOverlay=false,currentAttemptBoardProof=true",
            PinterestDirectShareComposerPolicy.unavailableDiagnostic(true),
        )
    }

    @Test fun emptyJobFieldsRequireEmptyEditorAndNonemptyValuesRequireReadback() {
        assertTrue(PinterestFieldReadbackPolicy.matches(listOf(null, "", null), emptyList()))
        assertFalse(PinterestFieldReadbackPolicy.matches(listOf(null, "", null), listOf("stale title")))
        assertTrue(PinterestFieldReadbackPolicy.matches(
            listOf("Farm title", "Farm description", null),
            listOf("Farm title", "Farm description")))
        assertFalse(PinterestFieldReadbackPolicy.matches(
            listOf("Farm title", "Farm description", null),
            listOf("Farm title", "wrong description")))
    }

    @Test fun attempt22PlaceholdersAndZeroCountersAreEffectiveEmptyValues() {
        val fields = listOf(
            PinterestEditorFieldSnapshot(PinterestEditorFieldKind.TITLE,
                "Title. Tell everyone what your Pin is about", "", "0/100"),
            PinterestEditorFieldSnapshot(PinterestEditorFieldKind.BODY,
                "Description. Tell everyone more about your Pin", "", "0/800"),
            PinterestEditorFieldSnapshot(PinterestEditorFieldKind.LINK,
                "Link. Add your link here", ""),
        )
        val result = PinterestHintAwareEditorReadbackPolicy.evaluate(
            null, "", null, fields, listOf("Create Pin", "0/100", "0/800"))
        assertTrue(result.titleReadbackEmpty)
        assertTrue(result.bodyReadbackEmpty)
        assertTrue(result.linkReadbackEmpty)
        assertTrue(result.matches)
    }

    @Test fun associatedZeroTitleCounterOverridesLocalizedPlaceholderOnlyForEmptyExpected() {
        val localized = listOf(PinterestEditorFieldSnapshot(
            PinterestEditorFieldKind.TITLE, "Локализованный текст подсказки", "", "0/100"))
        val empty = PinterestHintAwareEditorReadbackPolicy.evaluate(
            null, "Body", "https://example.com",
            localized + listOf(
                PinterestEditorFieldSnapshot(PinterestEditorFieldKind.BODY, "Body", ""),
                PinterestEditorFieldSnapshot(PinterestEditorFieldKind.LINK, "https://example.com", ""),
            ), listOf("Create Pin", "0/100", "0/800"))
        assertTrue(empty.titleReadbackEmpty)
        assertTrue(empty.matches)
        val missingCounter = localized.map { it.copy(associatedCounter = "") }
        assertFalse(PinterestHintAwareEditorReadbackPolicy.evaluate(
            null, "Body", "https://example.com",
            missingCounter + listOf(
                PinterestEditorFieldSnapshot(PinterestEditorFieldKind.BODY, "Body", ""),
                PinterestEditorFieldSnapshot(PinterestEditorFieldKind.LINK, "https://example.com", ""),
            ), emptyList()).matches)
        assertFalse(PinterestHintAwareEditorReadbackPolicy.evaluate(
            "Expected title", "Body", "https://example.com",
            localized + listOf(
                PinterestEditorFieldSnapshot(PinterestEditorFieldKind.BODY, "Body", ""),
                PinterestEditorFieldSnapshot(PinterestEditorFieldKind.LINK, "https://example.com", ""),
            ), emptyList()).matches)
    }

    @Test fun runtimeRootUniqueZeroTitleCounterIsAuthoritativeWithoutGeometry() {
        val fields = listOf(
            PinterestEditorFieldSnapshot(PinterestEditorFieldKind.TITLE,
                "Title. Tell everyone what your Pin is about", "", ""),
            PinterestEditorFieldSnapshot(PinterestEditorFieldKind.BODY,
                "Description. Add details", "", "0/800"),
            PinterestEditorFieldSnapshot(PinterestEditorFieldKind.LINK,
                "Link. Add your link here", "", ""),
        )
        assertTrue(PinterestHintAwareEditorReadbackPolicy.evaluate(
            null, null, null, fields, listOf("Create Pin", "Image", "0/100", "0/800")).matches)
        assertFalse(PinterestHintAwareEditorReadbackPolicy.evaluate(
            null, null, null, fields, listOf("Create Pin", "Image", "1/100", "0/800")).matches)
        assertFalse(PinterestHintAwareEditorReadbackPolicy.evaluate(
            null, null, null, fields, listOf("Create Pin", "Image", "0/800")).matches)
        assertFalse(PinterestHintAwareEditorReadbackPolicy.evaluate(
            null, null, null, fields, listOf("0/100", "1/100", "0/800")).matches)
    }

    @Test fun attempt25AccessibilityDescriptionIsAuthoritativeAndDeduplicatedPerNode() {
        val fields = listOf(
            PinterestEditorFieldSnapshot(PinterestEditorFieldKind.TITLE,
                "Title. Tell everyone what your Pin is about", "", ""),
            PinterestEditorFieldSnapshot(PinterestEditorFieldKind.BODY,
                "Description. Add details", "", "0/800"),
            PinterestEditorFieldSnapshot(PinterestEditorFieldKind.LINK,
                "Link. Add your link here", "", ""),
        )
        fun evaluate(signals: List<PinterestAccessibilityLabelSignal>) =
            PinterestHintAwareEditorReadbackPolicy.evaluate(
                null, null, null, fields, listOf("Create Pin", "0/800"), signals)

        val exactRuntimeNode = evaluate(listOf(
            PinterestAccessibilityLabelSignal("Create Pin", "", "root/0"),
            PinterestAccessibilityLabelSignal("0/100", "0 characters out of 100", "root/1"),
            PinterestAccessibilityLabelSignal("0/800", "", "root/2"),
        ))
        assertTrue(exactRuntimeNode.titleReadbackEmpty)
        assertEquals(1, exactRuntimeNode.titleCounterTextSignals)
        assertEquals(1, exactRuntimeNode.titleCounterDescriptionSignals)
        assertEquals(1, exactRuntimeNode.titleCounterDistinctNodeSignals)
        assertTrue(exactRuntimeNode.matches)
        assertTrue(evaluate(listOf(
            PinterestAccessibilityLabelSignal("", "0 characters out of 100"),
            PinterestAccessibilityLabelSignal("0/800", ""),
        )).matches)
        assertFalse(evaluate(listOf(
            PinterestAccessibilityLabelSignal("1/100", "1 characters out of 100"),
            PinterestAccessibilityLabelSignal("0/800", ""),
        )).matches)
        assertFalse(evaluate(listOf(
            PinterestAccessibilityLabelSignal("0/100", "0 characters out of 100", "root/1"),
            PinterestAccessibilityLabelSignal("0/100", "", "root/2"),
            PinterestAccessibilityLabelSignal("0/800", ""),
        )).matches)
        assertFalse(evaluate(listOf(
            PinterestAccessibilityLabelSignal("0/100", "1 characters out of 100", "root/1"),
            PinterestAccessibilityLabelSignal("0/800", "", "root/2"),
        )).matches)
        assertFalse(evaluate(listOf(PinterestAccessibilityLabelSignal("0/800", ""))).matches)
    }

    @Test fun attempt28FullEditorGateUsesDistinctZeroNodeWhenPinterestTitleFieldKindIsMissing() {
        val runtimeFields = listOf(
            PinterestEditorFieldSnapshot(PinterestEditorFieldKind.BODY,
                "Description. Add details", "", "0/800"),
            PinterestEditorFieldSnapshot(PinterestEditorFieldKind.LINK,
                "Link. Add your link here", "", ""),
        )
        val readback = PinterestHintAwareEditorReadbackPolicy.evaluate(
            null, null, null, runtimeFields, listOf("Create Pin", "0/100", "0/800"),
            listOf(
                PinterestAccessibilityLabelSignal("0/100", "0 characters out of 100", "root/7/2"),
                PinterestAccessibilityLabelSignal("0/800", "", "root/8/2"),
            ),
        )
        assertTrue(readback.titleExpectedEmpty)
        assertTrue(readback.titleReadbackEmpty)
        assertEquals(1, readback.titleCounterDistinctNodeSignals)
        assertTrue(readback.matches)
        assertTrue(PinterestEditorVerification.isVerified(
            stableComposerSignature = true,
            boardOverlayVisible = false,
            mediaPreviewVisible = true,
            requiredFieldReadbackMatches = readback.matches,
        ))
    }

    @Test fun emptyCountersAndExactNonemptyReadbackFailClosedCorrectly() {
        val unexpected = listOf(
            PinterestEditorFieldSnapshot(PinterestEditorFieldKind.TITLE, "Unexpected title", ""),
            PinterestEditorFieldSnapshot(PinterestEditorFieldKind.BODY, "", "Description"),
            PinterestEditorFieldSnapshot(PinterestEditorFieldKind.LINK, "", "Link"),
        )
        assertFalse(PinterestHintAwareEditorReadbackPolicy.evaluate(
            null, null, null, unexpected, listOf("1/100", "0/800")).matches)
        val exact = listOf(
            PinterestEditorFieldSnapshot(PinterestEditorFieldKind.TITLE, "Farm title", "Title"),
            PinterestEditorFieldSnapshot(PinterestEditorFieldKind.BODY, "Farm body", "Description"),
            PinterestEditorFieldSnapshot(PinterestEditorFieldKind.LINK, "https://example.com/farm", "Link"),
        )
        assertTrue(PinterestHintAwareEditorReadbackPolicy.evaluate(
            "Farm title", "Farm body", "https://example.com/farm", exact, emptyList()).matches)
    }

    @Test fun directFreshShareComposerWithExactMediaQualifiesWithoutMediaStep() {
        assertTrue(PinterestDirectShareComposerPolicy.qualifies(
            true, true, "com.elevium.mobileposteragent", "com.pinterest",
            10, 11, "agent", "composer", PinterestScreenKind.COMPOSER, true, false))
        assertTrue(PinterestDirectShareComposerPolicy.qualifies(
            true, true, "com.miui.home", "com.pinterest",
            10, 10, "home", "composer", PinterestScreenKind.COMPOSER, true, false))
    }

    @Test fun retainedPinterestPackageQualifiesOnlyWithPostDispatchFreshRoot() {
        assertTrue(PinterestDirectShareComposerPolicy.qualifies(
            true, true, "com.pinterest", "com.pinterest",
            10, 11, "composer-before", "composer-after", PinterestScreenKind.COMPOSER, true, false))
        assertFalse(PinterestDirectShareComposerPolicy.qualifies(
            true, true, "com.pinterest", "com.pinterest",
            10, 10, "composer", "composer", PinterestScreenKind.COMPOSER, true, false))
    }

    @Test fun missingMediaPreviewOrPostDispatchRootNeverQualifies() {
        assertFalse(PinterestDirectShareComposerPolicy.qualifies(
            true, true, "com.miui.home", "com.pinterest",
            10, 11, "home", "composer", PinterestScreenKind.COMPOSER, false, false))
        assertFalse(PinterestDirectShareComposerPolicy.qualifies(
            true, false, "com.miui.home", "com.pinterest",
            10, 11, "home", "composer", PinterestScreenKind.COMPOSER, true, false))
        assertFalse(PinterestDirectShareComposerPolicy.qualifies(
            true, true, "com.miui.home", "com.pinterest",
            10, 11, "home", "composer", PinterestScreenKind.COMPOSER, true, true))
        assertFalse(PinterestDirectShareComposerPolicy.qualifies(
            true, true, "com.miui.home", "com.pinterest",
            10, 10, "same", "same", PinterestScreenKind.COMPOSER, true, false))
    }

    @Test fun directShareDiagnosticContainsOnlyStructuredNonsecretState() {
        val evaluation = PinterestDirectShareComposerPolicy.evaluate(
            true, true, "com.pinterest", "com.pinterest",
            10, 11, "before", "after", PinterestScreenKind.COMPOSER, true, false)
        assertTrue(evaluation.qualifies)
        assertEquals(
            "shareRecorded=true,prePackageCategory=pinterest,postPackagePinterest=true," +
                "generationAdvanced=true,fingerprintChanged=true,postFingerprintNonempty=true," +
                "exactMediaPreview=true,composer=true,boardOverlay=false,currentAttemptBoardProof=false",
            evaluation.diagnostic(false),
        )
    }

    @Test fun mediaSelectionRequiresFreshExactConfirmation() {
        assertFalse(PinterestMediaSelectionPolicy.selectionConfirmed(
            false, false, PinterestMediaSelectionState(exactTileSelected = true, nextEnabled = true)))
        assertFalse(PinterestMediaSelectionPolicy.selectionConfirmed(
            true, false, PinterestMediaSelectionState(exactTileSelected = false, nextEnabled = false)))
        assertTrue(PinterestMediaSelectionPolicy.selectionConfirmed(
            true, false, PinterestMediaSelectionState(exactTileSelected = false, nextEnabled = true)))
        assertTrue(PinterestMediaSelectionPolicy.selectionConfirmed(
            true, true, PinterestMediaSelectionState(exactTileSelected = true, nextEnabled = true)))
    }

    @Test fun disabledNextCannotAdvanceAndTransitionMustBeFreshEditor() {
        assertFalse(PinterestMediaSelectionPolicy.canClickNext(false, PinterestMediaSelectionState(false, true)))
        assertFalse(PinterestMediaSelectionPolicy.canClickNext(true, PinterestMediaSelectionState(true, false)))
        assertTrue(PinterestMediaSelectionPolicy.canClickNext(true, PinterestMediaSelectionState(false, true)))
        assertFalse(PinterestMediaSelectionPolicy.nextTransitionAccepted(false, PinterestScreenKind.COMPOSER))
        assertFalse(PinterestMediaSelectionPolicy.nextTransitionAccepted(true, PinterestScreenKind.MEDIA_STEP))
        assertTrue(PinterestMediaSelectionPolicy.nextTransitionAccepted(true, PinterestScreenKind.COMPOSER))
        assertTrue(PinterestMediaSelectionPolicy.nextTransitionAccepted(true, PinterestScreenKind.BOARD_SELECTION))
    }

    @Test fun ignoredActionAndMissingGestureCallbackFailWithinBound() {
        assertTrue(PinterestBoundedInteractionPolicy.hasBudget(100, 749, 650))
        assertFalse(PinterestBoundedInteractionPolicy.hasBudget(100, 750, 650))
        assertFalse(PinterestMediaSelectionPolicy.selectionConfirmed(
            false, false, PinterestMediaSelectionState(false, false)))
        assertFalse(PinterestBoundedInteractionPolicy.gestureSucceeded(
            dispatched = true, callbackCompleted = false, elapsedMs = 1_000, timeoutMs = 1_000))
        assertFalse(PinterestBoundedInteractionPolicy.gestureSucceeded(
            dispatched = false, callbackCompleted = false, elapsedMs = 0, timeoutMs = 1_000))
        assertTrue(PinterestBoundedInteractionPolicy.gestureSucceeded(
            dispatched = true, callbackCompleted = true, elapsedMs = 80, timeoutMs = 1_000))
    }

    @Test fun draftSavedIsNeverPublicationSuccess() {
        assertEquals(PinterestPublicationConfirmation.DRAFT_SAVED,
            PinterestPublicationConfirmationPolicy.classify(listOf("Draft saved", "For you")))
        assertEquals(PinterestPublicationConfirmation.DRAFT_SAVED,
            PinterestPublicationConfirmationPolicy.classify(listOf("Черновик сохранён")))
    }

    @Test fun homeScreenAloneIsNotPublicationProof() {
        assertEquals(PinterestPublicationConfirmation.PENDING,
            PinterestPublicationConfirmationPolicy.classify(listOf("For you", "Home", "Saved")))
    }

    @Test fun explicitPublishedReceiptIsRequired() {
        assertEquals(PinterestPublicationConfirmation.PUBLISHED,
            PinterestPublicationConfirmationPolicy.classify(listOf("Pin published")))
        assertEquals(PinterestPublicationConfirmation.PENDING,
            PinterestPublicationConfirmationPolicy.classify(listOf("Save", "Create", "Pin")))
    }
}
