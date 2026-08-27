package com.elevium.mobileposteragent.service

internal enum class PinterestScreenKind {
    AGENT_UI, CHOOSER, CREATE_MENU, MEDIA_STEP, COMPOSER, BOARD_SELECTION,
    CAMERA, HELP, HOME, OTHER_APP, UNKNOWN,
}

internal data class PinterestScreenSignals(
    val composer: Boolean = false,
    val helpModalOwned: Boolean = false,
    val helpSpecificControl: Boolean = false,
    val createMenu: Boolean = false,
    val camera: Boolean = false,
    val boardSelection: Boolean = false,
    val mediaStep: Boolean = false,
    val home: Boolean = false,
)

internal data class PinterestEditableSignal(
    val label: String,
    val visibleToUser: Boolean,
)

internal object PinterestScreenClassifier {
    private val titleMarkers = listOf("title", "pin_title", "заголов")
    private val descriptionMarkers = listOf("description", "pin_description", "описан")
    private val linkMarkers = listOf("link", "destination", "ссылк")

    fun hasStableComposerSignature(editableFields: List<PinterestEditableSignal>): Boolean {
        val categories = buildSet {
            editableFields.filter { it.visibleToUser }.forEach { field ->
                val label = field.label
                if (titleMarkers.any { label.contains(it, ignoreCase = true) }) add("title")
                if (descriptionMarkers.any { label.contains(it, ignoreCase = true) }) add("description")
                if (linkMarkers.any { label.contains(it, ignoreCase = true) }) add("link")
            }
        }
        return categories.size >= 2
    }

    fun resolve(signals: PinterestScreenSignals): PinterestScreenKind = when {
        // A visible modal owns the active interaction even if the underlying
        // composer remains present in the accessibility tree.
        signals.boardSelection -> PinterestScreenKind.BOARD_SELECTION
        signals.composer -> PinterestScreenKind.COMPOSER
        signals.helpModalOwned && signals.helpSpecificControl -> PinterestScreenKind.HELP
        signals.createMenu -> PinterestScreenKind.CREATE_MENU
        signals.camera -> PinterestScreenKind.CAMERA
        signals.mediaStep -> PinterestScreenKind.MEDIA_STEP
        signals.home -> PinterestScreenKind.HOME
        else -> PinterestScreenKind.UNKNOWN
    }
}

internal data class PinterestCreateMenuNodeSignal(
    val label: String,
    val viewId: String,
    val visibleToUser: Boolean,
    val clickable: Boolean,
    val width: Int,
    val height: Int,
)

internal object PinterestCreateMenuPolicy {
    private val excludedViewIds = listOf(
        "closeup_back_button", "pin_image_view", "flashlight_search_button",
    )
    private val pinLabels = listOf("pin", "пин", "photo pin")
    private val ownerTitles = listOf(
        "create something",
        "start creating now",
        "создайте что-нибудь",
        "начните создавать",
    )
    private val collageLabels = listOf("collage", "коллаж")
    private val boardLabels = listOf("board", "доска")

    fun isOwnedMenu(nodes: List<PinterestCreateMenuNodeSignal>): Boolean {
        val visible = nodes.filter(PinterestCreateMenuNodeSignal::visibleToUser)
        val hasPin = visible.any { isExactPinLabel(it.label) }
        val hasOwnerTitle = visible.any { node ->
            ownerTitles.any { normalizedLabel(node.label).equals(it, ignoreCase = true) }
        }
        val hasOwnedSiblings = visible.any { node ->
            collageLabels.any { normalizedLabel(node.label).equals(it, ignoreCase = true) }
        } && visible.any { node ->
            boardLabels.any { normalizedLabel(node.label).equals(it, ignoreCase = true) }
        }
        // Pinterest leaves the underlying home-feed nodes visible to Accessibility while
        // this owned bottom sheet is open.  A complete title + three-entry signature is
        // stronger than those background close-up exclusions.
        if (hasPin && hasOwnerTitle && hasOwnedSiblings) return true
        if (visible.any { node -> excludedViewIds.any { node.viewId.contains(it, ignoreCase = true) } }) return false
        return hasPin && (hasOwnerTitle || hasOwnedSiblings)
    }

    private fun normalizedLabel(value: String): String = value
        .trim()
        .replace(Regex("\\s+"), " ")

    fun isActionablePinCandidate(
        node: PinterestCreateMenuNodeSignal,
        rootWidth: Int,
        rootHeight: Int,
    ): Boolean = node.visibleToUser &&
        node.clickable &&
        isExactPinLabel(node.label) &&
        excludedViewIds.none { node.viewId.contains(it, ignoreCase = true) } &&
        node.width > 0 && node.height > 0 && rootWidth > 0 && rootHeight > 0 &&
        node.width * 100 < rootWidth * 95 &&
        node.height * 100 < rootHeight * 50

    private fun isExactPinLabel(label: String): Boolean = pinLabels.any { label.trim().equals(it, ignoreCase = true) }
}

internal object PinterestCreateTransitionPolicy {
    fun isVerifiedMediaPickerTransition(
        freshTree: Boolean,
        screen: PinterestScreenKind,
        exactPreparedMediaVisible: Boolean,
    ): Boolean = freshTree && screen == PinterestScreenKind.MEDIA_STEP && exactPreparedMediaVisible
}

internal object PinterestMediaStepOwnershipPolicy {
    private val exactActions = setOf(
        "next", "continue", "done", "use", "select",
        "далее", "продолжить", "готово", "выбрать",
    )

    fun isOwnedMediaStep(labels: List<String>, exactPreparedMediaVisible: Boolean): Boolean =
        exactPreparedMediaVisible && labels.any { it.trim().lowercase() in exactActions }
}

internal data class PinterestMediaSelectionState(
    val exactTileSelected: Boolean,
    val nextEnabled: Boolean,
)

internal object PinterestMediaSelectionPolicy {
    private val selectedMarkers = listOf(" selected", "selected,", "выбран", "выбрано")

    fun isExactSelectedLabel(label: String, expectedPath: String?, expectedName: String?): Boolean {
        val actual = label.trim()
        val expected = expectedPath?.trim()?.takeIf(String::isNotEmpty)
            ?: expectedName?.trim()?.takeIf(String::isNotEmpty)
            ?: return false
        val exactMediaPrefix = actual == expected ||
            actual.startsWith("$expected ", ignoreCase = false) ||
            actual.startsWith("$expected,", ignoreCase = false)
        return exactMediaPrefix && selectedMarkers.any { actual.contains(it, ignoreCase = true) }
    }

    fun selectionConfirmed(
        freshState: Boolean,
        nextEnabledBeforeClick: Boolean,
        stateAfterClick: PinterestMediaSelectionState,
    ): Boolean = freshState && (
        stateAfterClick.exactTileSelected ||
            (!nextEnabledBeforeClick && stateAfterClick.nextEnabled)
        )

    fun canClickNext(exactSelectionConfirmed: Boolean, state: PinterestMediaSelectionState): Boolean =
        exactSelectionConfirmed && state.nextEnabled

    fun nextTransitionAccepted(freshTree: Boolean, screen: PinterestScreenKind): Boolean =
        freshTree && screen in setOf(PinterestScreenKind.COMPOSER, PinterestScreenKind.BOARD_SELECTION)

    fun shouldAttemptNextCenterFallback(firstTransitionAccepted: Boolean, screen: PinterestScreenKind): Boolean =
        !firstTransitionAccepted && screen == PinterestScreenKind.MEDIA_STEP
}

internal object PinterestBoundedInteractionPolicy {
    fun hasBudget(startedAtMs: Long, nowMs: Long, timeoutMs: Long): Boolean =
        timeoutMs > 0 && nowMs >= startedAtMs && nowMs - startedAtMs < timeoutMs

    fun gestureSucceeded(
        dispatched: Boolean,
        callbackCompleted: Boolean,
        elapsedMs: Long,
        timeoutMs: Long,
    ): Boolean = dispatched && callbackCompleted && elapsedMs in 0..timeoutMs
}

internal enum class PinterestScreenAction { ADVANCE_CREATE, DISMISS_HELP, CONTINUE }

internal object PinterestScreenActionPolicy {
    fun actionFor(screen: PinterestScreenKind): PinterestScreenAction = when (screen) {
        PinterestScreenKind.HOME -> PinterestScreenAction.ADVANCE_CREATE
        PinterestScreenKind.HELP -> PinterestScreenAction.DISMISS_HELP
        else -> PinterestScreenAction.CONTINUE
    }
}

internal object PinterestBoardPolicy {
    private val createBoardLabels = listOf("create board", "создать доску")

    fun existingBoardLabel(visibleLabels: List<String>, configuredBoard: String): String? {
        val target = configuredBoard.trim()
        if (target.isBlank()) return null
        return visibleLabels.firstOrNull { label ->
            label.equals(target, ignoreCase = true) &&
                createBoardLabels.none { label.contains(it, ignoreCase = true) }
        }
    }
}

internal object PinterestDeferredBoardPolicy {
    fun canDeferUntilFinalCreate(
        configuredBoard: String?,
        currentScreen: PinterestScreenKind,
        freshComposerViaMediaPipeline: Boolean,
        editorVerified: Boolean,
        boardOverlayVisible: Boolean,
    ): Boolean = !configuredBoard.isNullOrBlank() &&
        currentScreen == PinterestScreenKind.COMPOSER &&
        freshComposerViaMediaPipeline &&
        editorVerified &&
        !boardOverlayVisible
}

internal object PinterestVerifiedComposerBoardGatePolicy {
    fun canSkipPicker(
        allowDeferredAtVerifiedComposer: Boolean,
        verifiedComposerSnapshot: Boolean,
        sameAttemptProof: Boolean,
        boardOverlayVisible: Boolean,
    ): Boolean = allowDeferredAtVerifiedComposer && verifiedComposerSnapshot &&
        sameAttemptProof && !boardOverlayVisible

    fun canSkipFromAttemptState(
        configuredBoard: String?,
        directShareQualified: Boolean,
        currentAttemptBoardProof: Boolean,
        verifiedMediaPipeline: Boolean,
        passedScreen: PinterestScreenKind,
        boardOverlayVisible: Boolean,
    ): Boolean = !configuredBoard.isNullOrBlank() && canSkipPicker(
        allowDeferredAtVerifiedComposer = true,
        verifiedComposerSnapshot = passedScreen == PinterestScreenKind.COMPOSER,
        sameAttemptProof = directShareQualified || currentAttemptBoardProof || verifiedMediaPipeline,
        boardOverlayVisible = boardOverlayVisible,
    )
}

internal object PinterestExactBoardSelectionProofPolicy {
    private val allowedContinuations = setOf(
        PinterestScreenKind.HOME,
        PinterestScreenKind.MEDIA_STEP,
        PinterestScreenKind.COMPOSER,
    )

    fun canRecord(
        exactBoardSelected: Boolean,
        freshTree: Boolean,
        screenAfterSelection: PinterestScreenKind,
        boardOverlayVisible: Boolean,
    ): Boolean = exactBoardSelected && freshTree && !boardOverlayVisible &&
        screenAfterSelection in allowedContinuations

    fun allowsComposerWithoutSecondPicker(
        exactBoardSelectedThisAttempt: Boolean,
        currentScreen: PinterestScreenKind,
        boardOverlayVisible: Boolean,
    ): Boolean = exactBoardSelectedThisAttempt &&
        currentScreen == PinterestScreenKind.COMPOSER &&
        !boardOverlayVisible
}

internal object PinterestDirectShareComposerPolicy {
    fun unavailableDiagnostic(currentAttemptBoardProof: Boolean): String =
        "shareRecorded=false,prePackageCategory=null,postPackagePinterest=false," +
            "generationAdvanced=false,fingerprintChanged=false,postFingerprintNonempty=false," +
            "exactMediaPreview=false,composer=false,boardOverlay=false," +
            "currentAttemptBoardProof=$currentAttemptBoardProof"

    data class Evaluation(
        val shareRecorded: Boolean,
        val prePackageCategory: String,
        val postPackagePinterest: Boolean,
        val generationAdvanced: Boolean,
        val fingerprintChanged: Boolean,
        val postFingerprintNonempty: Boolean,
        val exactMediaPreview: Boolean,
        val composer: Boolean,
        val boardOverlay: Boolean,
        val qualifies: Boolean,
    ) {
        fun diagnostic(currentAttemptBoardProof: Boolean): String = listOf(
            "shareRecorded=$shareRecorded",
            "prePackageCategory=$prePackageCategory",
            "postPackagePinterest=$postPackagePinterest",
            "generationAdvanced=$generationAdvanced",
            "fingerprintChanged=$fingerprintChanged",
            "postFingerprintNonempty=$postFingerprintNonempty",
            "exactMediaPreview=$exactMediaPreview",
            "composer=$composer",
            "boardOverlay=$boardOverlay",
            "currentAttemptBoardProof=$currentAttemptBoardProof",
        ).joinToString(",")
    }

    fun evaluate(
        shareLaunchRecorded: Boolean,
        exactCurrentMediaShared: Boolean,
        packageBeforeShare: String?,
        packageAfterShare: String?,
        generationBeforeShare: Long,
        generationAfterShare: Long,
        fingerprintBeforeShare: String,
        fingerprintAfterShare: String,
        currentScreen: PinterestScreenKind,
        mediaPreviewVisible: Boolean,
        boardOverlayVisible: Boolean,
    ): Evaluation {
        val fingerprintChanged = fingerprintAfterShare.isNotBlank() &&
            fingerprintAfterShare != fingerprintBeforeShare
        val generationAdvanced = generationAfterShare > generationBeforeShare
        val postFingerprintNonempty = fingerprintAfterShare.isNotBlank()
        val postPackagePinterest = packageAfterShare == "com.pinterest"
        val composer = currentScreen == PinterestScreenKind.COMPOSER
        val postDispatchRootProven = postFingerprintNonempty && (generationAdvanced || fingerprintChanged)
        val prePackageCategory = when (packageBeforeShare) {
            null -> "null"
            "com.elevium.mobileposteragent" -> "agent"
            "com.pinterest" -> "pinterest"
            else -> "other"
        }
        val accepted = shareLaunchRecorded && exactCurrentMediaShared && postPackagePinterest &&
            postDispatchRootProven && composer && mediaPreviewVisible && !boardOverlayVisible
        return Evaluation(
            shareLaunchRecorded, prePackageCategory, postPackagePinterest, generationAdvanced,
            fingerprintChanged, postFingerprintNonempty, mediaPreviewVisible, composer,
            boardOverlayVisible, accepted,
        )
    }

    fun qualifies(
        shareLaunchRecorded: Boolean,
        exactCurrentMediaShared: Boolean,
        packageBeforeShare: String?,
        packageAfterShare: String?,
        generationBeforeShare: Long,
        generationAfterShare: Long,
        fingerprintBeforeShare: String,
        fingerprintAfterShare: String,
        currentScreen: PinterestScreenKind,
        mediaPreviewVisible: Boolean,
        boardOverlayVisible: Boolean,
    ): Boolean = evaluate(
        shareLaunchRecorded, exactCurrentMediaShared, packageBeforeShare, packageAfterShare,
        generationBeforeShare, generationAfterShare, fingerprintBeforeShare, fingerprintAfterShare,
        currentScreen, mediaPreviewVisible, boardOverlayVisible,
    ).qualifies
}

internal object PinterestFieldReadbackPolicy {
    fun matches(expectedValues: List<String?>, actualValues: List<String>): Boolean {
        val expected = expectedValues.map { it?.trim().orEmpty() }
        val actual = actualValues.map(String::trim).filter(String::isNotBlank)
        if (expected.all(String::isBlank)) return actual.isEmpty()
        return expected.filter(String::isNotBlank).all { wanted ->
            actual.any { observed -> observed == wanted || observed.contains(wanted, ignoreCase = false) }
        }
    }
}

internal enum class PinterestEditorFieldKind { TITLE, BODY, LINK }

internal data class PinterestEditorFieldSnapshot(
    val kind: PinterestEditorFieldKind,
    val text: String,
    val hint: String,
    val associatedCounter: String = "",
    val description: String = "",
)

internal data class PinterestEditorReadbackEvaluation(
    val titleExpectedEmpty: Boolean,
    val titleReadbackEmpty: Boolean,
    val bodyExpectedEmpty: Boolean,
    val bodyReadbackEmpty: Boolean,
    val linkExpectedEmpty: Boolean,
    val linkReadbackEmpty: Boolean,
    val titleCounterTextSignals: Int,
    val titleCounterDescriptionSignals: Int,
    val titleCounterDistinctNodeSignals: Int,
    val matches: Boolean,
)

internal data class PinterestAccessibilityLabelSignal @JvmOverloads constructor(
    val text: String,
    val description: String,
    val nodeIdentity: String = "legacy:${text}:${description}",
)

internal data class PinterestNodeSnapshot(
    val path: String,
    val text: String,
    val description: String,
    val hint: String,
    val className: String,
    val viewId: String,
    val visible: Boolean,
    val editable: Boolean,
    val bounds: String,
)

internal data class PinterestImmutableEditorSnapshot(
    val nodes: List<PinterestNodeSnapshot>,
    val generation: Long,
    val fingerprint: String,
    val consistent: Boolean,
    val reason: PinterestImmutableSnapshotReason,
)

internal enum class PinterestImmutableSnapshotReason {
    CONSISTENT, EMPTY_NODES, NONPOSITIVE_GENERATION, GENERATION_CHURN, INVALID_FINGERPRINT,
}

internal object PinterestImmutableEditorSnapshotPolicy {
    private val boardMarkers = listOf(
        "Choose board", "Select board", "Save to board", "Create board",
        "Выберите доску", "Выбрать доску", "Сохранить на доску", "Создать доску",
    )
    private val boundsPattern = Regex("\\[(-?\\d+),(-?\\d+)]\\[(-?\\d+),(-?\\d+)]")

    fun create(nodes: List<PinterestNodeSnapshot>, generationBefore: Long, generationAfter: Long): PinterestImmutableEditorSnapshot {
        val fingerprint = nodes.joinToString("\u001f") {
            listOf(it.path, it.text, it.description, it.hint, it.className, it.viewId, it.visible.toString(), it.bounds)
                .joinToString("\u001e")
        }.hashCode().toString()
        val reason = when {
            nodes.isEmpty() -> PinterestImmutableSnapshotReason.EMPTY_NODES
            generationBefore <= 0 || generationAfter <= 0 -> PinterestImmutableSnapshotReason.NONPOSITIVE_GENERATION
            generationBefore != generationAfter -> PinterestImmutableSnapshotReason.GENERATION_CHURN
            fingerprint.isBlank() -> PinterestImmutableSnapshotReason.INVALID_FINGERPRINT
            else -> PinterestImmutableSnapshotReason.CONSISTENT
        }
        return PinterestImmutableEditorSnapshot(nodes.toList(), generationAfter, fingerprint,
            reason == PinterestImmutableSnapshotReason.CONSISTENT, reason)
    }

    fun hasBoardOverlay(snapshot: PinterestImmutableEditorSnapshot): Boolean = snapshot.nodes.any { node ->
        node.visible && listOf(node.text, node.description).any { label ->
            boardMarkers.any { marker -> label.contains(marker, ignoreCase = true) }
        }
    }

    fun hasMediaPreview(snapshot: PinterestImmutableEditorSnapshot, verifiedComposerContext: Boolean): Boolean {
        if (!snapshot.consistent || !verifiedComposerContext) return false
        return snapshot.nodes.any { node ->
            if (!node.visible) return@any false
            val imageNode = node.viewId.endsWith("attribute_image_view") ||
                node.description.equals("Image", ignoreCase = true) ||
                node.className.contains("Image", ignoreCase = true)
            val match = boundsPattern.matchEntire(node.bounds)
            val width = match?.let { it.groupValues[3].toInt() - it.groupValues[1].toInt() } ?: 0
            val height = match?.let { it.groupValues[4].toInt() - it.groupValues[2].toInt() } ?: 0
            imageNode && width >= 48 && height >= 48
        }
    }
}

internal object PinterestImmutableSnapshotAcquirer {
    fun acquire(
        maxAttempts: Int,
        capture: () -> PinterestImmutableEditorSnapshot,
        settle: () -> Unit,
    ): PinterestImmutableEditorSnapshot {
        var snapshot = capture()
        var attempts = 1
        while (snapshot.reason == PinterestImmutableSnapshotReason.GENERATION_CHURN && attempts < maxAttempts) {
            settle()
            snapshot = capture()
            attempts += 1
        }
        return snapshot
    }
}

internal object PinterestAttemptProofPolicy {
    fun qualifies(
        liveDirectShare: Boolean,
        liveComposerPipeline: Boolean,
        liveExactBoard: Boolean,
        terminalJobMatches: Boolean,
        terminalDirectShare: Boolean,
        terminalComposerPipeline: Boolean,
        terminalExactBoard: Boolean,
    ): Boolean = liveDirectShare || liveComposerPipeline || liveExactBoard ||
        (terminalJobMatches && (terminalDirectShare || terminalComposerPipeline || terminalExactBoard))
}

internal data class PinterestEditorExtraction(
    val editableSignals: List<PinterestEditableSignal>,
    val fields: List<PinterestEditorFieldSnapshot>,
    val visibleLabels: List<String>,
    val visibleSignals: List<PinterestAccessibilityLabelSignal>,
)

internal data class PinterestSharedEditorGate(
    val stableComposer: Boolean,
    val readback: PinterestEditorReadbackEvaluation,
    val verified: Boolean,
    val diagnostic: String,
)

internal object PinterestEditorExtractionAdapter {
    fun extract(nodes: List<PinterestNodeSnapshot>): PinterestEditorExtraction {
        val visibleNodes = nodes.filter(PinterestNodeSnapshot::visible)
        val labels = visibleNodes.mapNotNull { node ->
            (node.text.ifBlank { node.description }).trim().takeIf(String::isNotEmpty)
        }.distinct().take(80)
        val signals = visibleNodes.filter { it.text.isNotBlank() || it.description.isNotBlank() }.map {
            PinterestAccessibilityLabelSignal(it.text.trim(), it.description.trim(), it.path)
        }.take(80)
        val bodyCounter = labels.singleOrNull { it.trim() == "0/800" }.orEmpty()
        val titleCounter = labels.singleOrNull { it.trim() == "0/100" }.orEmpty()
        val editableNodes = visibleNodes.filter { it.editable || it.className == "android.widget.EditText" }
        val editableSignals = editableNodes.map { node ->
            PinterestEditableSignal(
                listOf(node.hint, node.text, node.description, node.viewId)
                    .firstOrNull { it.isNotBlank() }.orEmpty(),
                true,
            )
        }
        val fields = editableNodes.mapNotNull { node ->
            val combined = listOf(node.hint, node.text, node.description, node.viewId)
                .joinToString(" ").lowercase()
            val kind = when {
                listOf("title", "pin_title", "заголов").any(combined::contains) -> PinterestEditorFieldKind.TITLE
                listOf("description", "caption", "описан", "подпись").any(combined::contains) -> PinterestEditorFieldKind.BODY
                listOf("link", "destination", "url", "ссылк").any(combined::contains) -> PinterestEditorFieldKind.LINK
                else -> null
            } ?: return@mapNotNull null
            PinterestEditorFieldSnapshot(
                kind, node.text, node.hint,
                if (kind == PinterestEditorFieldKind.TITLE) titleCounter
                else if (kind == PinterestEditorFieldKind.BODY) bodyCounter else "",
                node.description,
            )
        }
        return PinterestEditorExtraction(editableSignals, fields, labels, signals)
    }

    fun evaluate(
        extraction: PinterestEditorExtraction,
        expectedTitle: String?,
        expectedBody: String?,
        expectedLink: String?,
        boardOverlay: Boolean,
        mediaPreview: Boolean,
        generationPositive: Boolean,
        fingerprintNonempty: Boolean,
    ): PinterestSharedEditorGate {
        val stable = PinterestScreenClassifier.hasStableComposerSignature(extraction.editableSignals)
        val readback = PinterestHintAwareEditorReadbackPolicy.evaluate(
            expectedTitle, expectedBody, expectedLink, extraction.fields,
            extraction.visibleLabels, extraction.visibleSignals,
        )
        val verified = generationPositive && fingerprintNonempty &&
            PinterestEditorVerification.isVerified(stable, boardOverlay, mediaPreview, readback.matches)
        val diagnostic = "composer=$stable,overlay=$boardOverlay,mediaPreview=$mediaPreview," +
            "titleExpectedEmpty=${readback.titleExpectedEmpty},titleReadbackEmpty=${readback.titleReadbackEmpty}," +
            "titleCounterTextSignals=${readback.titleCounterTextSignals}," +
            "titleCounterDescriptionSignals=${readback.titleCounterDescriptionSignals}," +
            "titleCounterDistinctNodeSignals=${readback.titleCounterDistinctNodeSignals}," +
            "bodyExpectedEmpty=${readback.bodyExpectedEmpty},bodyReadbackEmpty=${readback.bodyReadbackEmpty}," +
            "linkExpectedEmpty=${readback.linkExpectedEmpty},linkReadbackEmpty=${readback.linkReadbackEmpty}," +
            "generationPositive=$generationPositive,fingerprintNonempty=$fingerprintNonempty"
        return PinterestSharedEditorGate(stable, readback, verified, diagnostic)
    }
}

internal object PinterestHintAwareEditorReadbackPolicy {
    @JvmOverloads
    fun evaluate(
        expectedTitle: String?,
        expectedBody: String?,
        expectedLink: String?,
        fields: List<PinterestEditorFieldSnapshot>,
        visibleLabels: List<String>,
        visibleSignals: List<PinterestAccessibilityLabelSignal> = visibleLabels.map {
            PinterestAccessibilityLabelSignal(it, "")
        },
    ): PinterestEditorReadbackEvaluation {
        val titleExpectedEmpty = expectedTitle.isNullOrBlank()
        val bodyExpectedEmpty = expectedBody.isNullOrBlank()
        val linkExpectedEmpty = expectedLink.isNullOrBlank()
        val title = fields.firstOrNull { it.kind == PinterestEditorFieldKind.TITLE }
        val body = fields.firstOrNull { it.kind == PinterestEditorFieldKind.BODY }
        val link = fields.firstOrNull { it.kind == PinterestEditorFieldKind.LINK }
        val textCounterPattern = Regex("^(\\d+)\\s*/\\s*100$")
        val descriptionCounterPattern = Regex("^(\\d+)\\s+characters\\s+out\\s+of\\s+100$", RegexOption.IGNORE_CASE)
        val textCounters = visibleSignals.mapNotNull { signal ->
            textCounterPattern.matchEntire(signal.text.trim())?.groupValues?.get(1)?.toIntOrNull()
        }
        val descriptionCounters = visibleSignals.mapNotNull { signal ->
            descriptionCounterPattern.matchEntire(signal.description.trim())?.groupValues?.get(1)?.toIntOrNull()
        }
        val titleCounterNodes = visibleSignals.mapNotNull { signal ->
            val values = listOfNotNull(
                textCounterPattern.matchEntire(signal.text.trim())?.groupValues?.get(1)?.toIntOrNull(),
                descriptionCounterPattern.matchEntire(signal.description.trim())?.groupValues?.get(1)?.toIntOrNull(),
            ).distinct()
            if (values.isEmpty()) null else signal.nodeIdentity to values
        }
        val groupedCounterNodes = titleCounterNodes.groupBy({ it.first }, { it.second })
        val titleCounterValuesByNode = groupedCounterNodes.mapValues { (_, nodeValues) -> nodeValues.flatten().distinct() }
        val titleReadbackEmpty = titleCounterValuesByNode.size == 1 &&
            titleCounterValuesByNode.values.single() == listOf(0)
        val bodyReadbackEmpty = isEmptyPlaceholder(body) && body?.associatedCounter?.trim() == "0/800"
        val linkReadbackEmpty = isEmptyLinkPlaceholder(link)
        val titleMatches = if (titleExpectedEmpty) titleReadbackEmpty else
            exactValue(title, expectedTitle) || exactVisibleValue(visibleSignals, expectedTitle)
        val bodyMatches = if (bodyExpectedEmpty) bodyReadbackEmpty else
            exactValue(body, expectedBody) || exactVisibleValue(visibleSignals, expectedBody)
        val linkMatches = if (linkExpectedEmpty) linkReadbackEmpty else
            exactValue(link, expectedLink) || exactVisibleValue(visibleSignals, expectedLink)
        return PinterestEditorReadbackEvaluation(
            titleExpectedEmpty, titleReadbackEmpty, bodyExpectedEmpty, bodyReadbackEmpty,
            linkExpectedEmpty, linkReadbackEmpty, textCounters.size, descriptionCounters.size,
            titleCounterValuesByNode.size,
            titleMatches && bodyMatches && linkMatches,
        )
    }

    private fun exactValue(field: PinterestEditorFieldSnapshot?, expected: String?): Boolean {
        if (field == null) return false
        val wanted = expected?.trim().orEmpty()
        if (wanted.isEmpty()) return false
        if (field.text.trim() == wanted || field.description.trim() == wanted) return true
        val description = field.description.trim()
        val expectedPrefix = when (field.kind) {
            PinterestEditorFieldKind.TITLE -> listOf("title", "заголовок")
            PinterestEditorFieldKind.BODY -> listOf("description", "описание")
            PinterestEditorFieldKind.LINK -> listOf("link", "ссылка")
        }
        val candidates = listOf(field.text.trim(), description).filter(String::isNotEmpty)
        return candidates.any { candidate ->
            expectedPrefix.any { prefix ->
                if (!candidate.startsWith(prefix, ignoreCase = true)) false
                else candidate.substring(prefix.length)
                    .trimStart('.', ':', ',', ' ')
                    .trim() == wanted
            }
        }
    }

    private fun exactVisibleValue(
        visibleSignals: List<PinterestAccessibilityLabelSignal>,
        expected: String?,
    ): Boolean {
        val wanted = expected?.trim().orEmpty()
        return wanted.isNotEmpty() && visibleSignals.any { signal ->
            signal.text.trim() == wanted || signal.description.trim() == wanted
        }
    }

    private fun isEmptyPlaceholder(field: PinterestEditorFieldSnapshot?): Boolean {
        if (field == null) return false
        val text = field.text.trim()
        val hint = field.hint.trim()
        if (text.isEmpty()) return true
        if (hint.isNotEmpty() && text == hint) return true
        val lower = text.lowercase()
        return when (field.kind) {
            PinterestEditorFieldKind.TITLE -> lower == "title" ||
                lower == "title. tell everyone what your pin is about"
            PinterestEditorFieldKind.BODY -> lower == "description" || lower.startsWith("description.")
            PinterestEditorFieldKind.LINK -> false
        }
    }

    private fun isEmptyLinkPlaceholder(field: PinterestEditorFieldSnapshot?): Boolean {
        if (field == null) return false
        val text = field.text.trim()
        val hint = field.hint.trim()
        return text.isEmpty() || (hint.isNotEmpty() && text == hint) ||
            text.equals("Link. Add your link here", ignoreCase = true)
    }
}

internal enum class PinterestBoardPostSelectionAction {
    VERIFIED_COMPOSER, CONTINUE_HOME_CREATE, CONTINUE_MEDIA_SELECTION, NEEDS_REVIEW,
}

internal object PinterestBoardPostSelectionPolicy {
    fun action(
        exactConfiguredBoardSelected: Boolean,
        freshTree: Boolean,
        screen: PinterestScreenKind,
    ): PinterestBoardPostSelectionAction = when {
        !exactConfiguredBoardSelected || !freshTree -> PinterestBoardPostSelectionAction.NEEDS_REVIEW
        screen == PinterestScreenKind.COMPOSER -> PinterestBoardPostSelectionAction.VERIFIED_COMPOSER
        screen == PinterestScreenKind.HOME -> PinterestBoardPostSelectionAction.CONTINUE_HOME_CREATE
        screen == PinterestScreenKind.MEDIA_STEP -> PinterestBoardPostSelectionAction.CONTINUE_MEDIA_SELECTION
        else -> PinterestBoardPostSelectionAction.NEEDS_REVIEW
    }
}

internal object PinterestFreshTreeGuard {
    fun hasFreshTree(
        generationBeforeClick: Long,
        generationAfterClick: Long,
        fingerprintBeforeClick: String,
        fingerprintAfterClick: String,
    ): Boolean = generationAfterClick > generationBeforeClick &&
        fingerprintAfterClick.isNotBlank() &&
        fingerprintAfterClick != fingerprintBeforeClick
}

internal object PinterestEditorVerification {
    fun isVerified(
        stableComposerSignature: Boolean,
        boardOverlayVisible: Boolean,
        mediaPreviewVisible: Boolean,
        requiredFieldReadbackMatches: Boolean,
    ): Boolean = stableComposerSignature &&
        !boardOverlayVisible &&
        mediaPreviewVisible &&
        requiredFieldReadbackMatches
}

internal enum class PinterestComposerAction { READY_TO_PUBLISH, NEEDS_REVIEW, CONTINUE_TO_PUBLISH }

internal object PinterestTerminalPolicy {
    fun composerAction(
        stopBeforePublish: Boolean,
        editorVerified: Boolean = true,
        evidenceAvailable: Boolean = true,
    ): PinterestComposerAction = when {
        !stopBeforePublish -> PinterestComposerAction.CONTINUE_TO_PUBLISH
        editorVerified && evidenceAvailable -> PinterestComposerAction.READY_TO_PUBLISH
        else -> PinterestComposerAction.NEEDS_REVIEW
    }
}

internal enum class PinterestPublicationConfirmation {
    PUBLISHED, DRAFT_SAVED, PENDING,
}

internal object PinterestPublicationConfirmationPolicy {
    private val publishedLabels = setOf(
        "pin published", "published", "pin created",
        "your pin published", "your pin published!",
        "пин опубликован", "опубликовано", "пин создан",
    )
    private val draftLabels = setOf(
        "draft saved", "saved to drafts", "черновик сохранен", "черновик сохранён",
    )

    fun classify(visibleLabels: List<String>): PinterestPublicationConfirmation {
        val normalized = visibleLabels.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
        return when {
            normalized.any { label -> draftLabels.any { marker -> label == marker || label.startsWith("$marker ") } } ->
                PinterestPublicationConfirmation.DRAFT_SAVED
            normalized.any { label -> publishedLabels.any { marker -> label == marker || label.startsWith("$marker ") } } ->
                PinterestPublicationConfirmation.PUBLISHED
            else -> PinterestPublicationConfirmation.PENDING
        }
    }
}
