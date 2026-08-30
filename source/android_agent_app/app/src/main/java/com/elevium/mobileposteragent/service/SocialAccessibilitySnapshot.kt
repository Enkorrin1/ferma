package com.elevium.mobileposteragent.service

internal data class SocialAccessibilityNode(
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

internal data class SocialAccessibilitySnapshot(
    val packageName: String,
    val generationBefore: Long,
    val generationAfter: Long,
    val fingerprint: String,
    val nodes: List<SocialAccessibilityNode>,
) {
    val consistent: Boolean
        get() = packageName.isNotBlank() && generationBefore > 0 &&
            generationBefore == generationAfter && fingerprint.isNotBlank() && nodes.isNotEmpty()

    fun visibleLabels(): List<String> = nodes.asSequence()
        .filter(SocialAccessibilityNode::visible)
        .flatMap { sequenceOf(it.text, it.description) }
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinct()
        .toList()
}

internal enum class SocialScreenKind {
    LEGAL,
    LOGIN_CHALLENGE,
    ACCOUNT_PROOF,
    CREATE,
    MEDIA,
    EDITOR,
    FINAL,
    UNKNOWN,
}

internal data class SocialAccountOwnershipRule(
    val viewIds: Set<String> = emptySet(),
    val descriptionPrefixes: Set<String> = emptySet(),
)

internal data class SocialSnapshotDecision(
    val screen: SocialScreenKind,
    val accountMatch: SocialDryRunPolicy.AccountMatch,
)

internal data class SocialSnapshotFlowDecision(
    val snapshotDecision: SocialSnapshotDecision,
    val exactMediaVisible: Boolean,
)

internal data class SocialAccountFixtureDiagnostic(
    val exactLabelNodeCount: Int,
    val mentionNodeCount: Int,
    val visibleNonEditableCount: Int,
    val textMatches: Boolean,
    val descriptionMatches: Boolean,
    val viewIds: List<String>,
    val labeledViewIds: List<String>,
    val labeledNodeShapes: List<String>,
    val visibleNodeShapes: List<String> = emptyList(),
) {
    fun redactedMessage(): String = buildString {
        append("social-account-fixture exactNodes=").append(exactLabelNodeCount)
        append(" mentionNodes=").append(mentionNodeCount)
        append(" visibleNonEditable=").append(visibleNonEditableCount)
        append(" textMatch=").append(textMatches)
        append(" descriptionMatch=").append(descriptionMatches)
        append(" viewIds=")
        append(if (viewIds.isEmpty()) "none" else viewIds.joinToString(","))
        append(" labeledViewIds=")
        append(if (labeledViewIds.isEmpty()) "none" else labeledViewIds.joinToString(","))
        append(" labeledNodeShapes=")
        append(if (labeledNodeShapes.isEmpty()) "none" else labeledNodeShapes.joinToString(";"))
        append(" visibleNodeShapes=")
        append(if (visibleNodeShapes.isEmpty()) "none" else visibleNodeShapes.joinToString(";"))
    }
}

/**
 * Fail-closed immutable social-screen classifier. Production ownership allowlists intentionally
 * remain empty until exact IDs are captured from a user-authenticated app fixture.
 */
internal object SocialAccessibilitySnapshotPolicy {
    const val INSTAGRAM_PROFILE_ENTRY_VIEW_ID = "com.instagram.android:id/profile_tab"
    const val INSTAGRAM_PROFILE_ENTRY_BOUNDS = "[576,1336][720,1424]"
    const val INSTAGRAM_ACCOUNT_VIEW_ID = "com.instagram.android:id/action_bar_title"
    const val INSTAGRAM_POST_COUNT_VIEW_ID = "com.instagram.android:id/profile_header_familiar_post_count_value"
    const val TIKTOK_CREATE_ENTRY_VIEW_ID = "com.zhiliaoapp.musically:id/of9"
    const val TIKTOK_PROFILE_ENTRY_VIEW_ID = "com.zhiliaoapp.musically:id/ofe"
    const val TIKTOK_PROFILE_ENTRY_BOUNDS = "[576,1326][720,1424]"
    const val TIKTOK_MEDIA_PAGER_VIEW_ID = "com.zhiliaoapp.musically:id/viewpager_choose_media"
    const val TIKTOK_MEDIA_PICKER_CLOSE_VIEW_ID = "com.zhiliaoapp.musically:id/bqo"
    const val TIKTOK_MEDIA_PICKER_CLOSE_VIEW_ID_CURRENT = "com.zhiliaoapp.musically:id/bq7"
    const val TIKTOK_MEDIA_PICKER_CLOSE_BOUNDS = "[16,63][96,143]"
    const val TIKTOK_MEDIA_TILE_VIEW_ID = "com.zhiliaoapp.musically:id/ooy"
    const val TIKTOK_MEDIA_TILE_VIEW_ID_CURRENT = "com.zhiliaoapp.musically:id/oo0"
    const val TIKTOK_FIRST_MEDIA_TILE_BOUNDS = "[243,245][478,483]"
    const val TIKTOK_MEDIA_NEXT_VIEW_ID = "com.zhiliaoapp.musically:id/pt2"
    const val TIKTOK_MEDIA_NEXT_VIEW_ID_CURRENT = "com.zhiliaoapp.musically:id/pr7"
    const val TIKTOK_MEDIA_NEXT_BOUNDS = "[368,1304][688,1392]"
    const val TIKTOK_EDITOR_NEXT_VIEW_ID = "com.zhiliaoapp.musically:id/ptb"
    const val TIKTOK_EDITOR_NEXT_VIEW_ID_CURRENT = "com.zhiliaoapp.musically:id/prf"
    const val TIKTOK_EDITOR_NEXT_BOUNDS = "[494,1336][566,1376]"

    private val tikTokMediaPickerCloseViewIds = setOf(
        TIKTOK_MEDIA_PICKER_CLOSE_VIEW_ID,
        TIKTOK_MEDIA_PICKER_CLOSE_VIEW_ID_CURRENT,
    )
    private val tikTokMediaTileViewIds = setOf(
        TIKTOK_MEDIA_TILE_VIEW_ID,
        TIKTOK_MEDIA_TILE_VIEW_ID_CURRENT,
    )
    private val tikTokMediaNextViewIds = setOf(
        TIKTOK_MEDIA_NEXT_VIEW_ID,
        TIKTOK_MEDIA_NEXT_VIEW_ID_CURRENT,
    )
    private val tikTokSelectedMediaNextLabels = setOf("Next", "Next (1)", "Далее", "Далее (1)")
    private val tikTokEditorNextViewIds = setOf(
        TIKTOK_EDITOR_NEXT_VIEW_ID,
        TIKTOK_EDITOR_NEXT_VIEW_ID_CURRENT,
    )
    const val TIKTOK_CAPTION_VIEW_ID = "com.zhiliaoapp.musically:id/h3r"
    const val TIKTOK_CAPTION_VIEW_ID_CURRENT = "com.zhiliaoapp.musically:id/h3a"
    const val TIKTOK_PREVIEW_VIEW_ID = "com.zhiliaoapp.musically:id/ksj"
    const val TIKTOK_PREVIEW_VIEW_ID_CURRENT = "com.zhiliaoapp.musically:id/ksa"
    const val TIKTOK_PHOTO_PREVIEW_VIEW_ID = "com.zhiliaoapp.musically:id/af_"
    const val TIKTOK_DRAFTS_VIEW_ID = "com.zhiliaoapp.musically:id/gdl"
    const val TIKTOK_DRAFTS_VIEW_ID_CURRENT = "com.zhiliaoapp.musically:id/gd8"
    const val TIKTOK_POST_VIEW_ID = "com.zhiliaoapp.musically:id/t7a"
    const val TIKTOK_POST_VIEW_ID_CURRENT = "com.zhiliaoapp.musically:id/t6b"
    const val TIKTOK_DRAFTS_BOUNDS = "[24,1304][352,1400]"
    const val TIKTOK_POST_BOUNDS = "[368,1304][696,1400]"
    private val tikTokCaptionViewIds = setOf(TIKTOK_CAPTION_VIEW_ID, TIKTOK_CAPTION_VIEW_ID_CURRENT)
    private val tikTokPreviewViewIds = setOf(
        TIKTOK_PREVIEW_VIEW_ID,
        TIKTOK_PREVIEW_VIEW_ID_CURRENT,
        TIKTOK_PHOTO_PREVIEW_VIEW_ID,
    )
    private val tikTokDraftsViewIds = setOf(TIKTOK_DRAFTS_VIEW_ID, TIKTOK_DRAFTS_VIEW_ID_CURRENT)
    private val tikTokPostViewIds = setOf(TIKTOK_POST_VIEW_ID, TIKTOK_POST_VIEW_ID_CURRENT)
    private val productionRules = mapOf(
        SocialPlatform.INSTAGRAM to SocialAccountOwnershipRule(
            viewIds = setOf(INSTAGRAM_ACCOUNT_VIEW_ID),
        ),
        SocialPlatform.TIKTOK to SocialAccountOwnershipRule(
            viewIds = setOf("com.zhiliaoapp.musically:id/sxa"),
        ),
    )

    fun classify(
        platform: SocialPlatform,
        expectedPackage: String,
        expectedAccount: String?,
        snapshot: SocialAccessibilitySnapshot,
        rule: SocialAccountOwnershipRule = productionRules.getValue(platform),
    ): SocialSnapshotDecision {
        if (!snapshot.consistent || snapshot.packageName != expectedPackage) {
            return SocialSnapshotDecision(SocialScreenKind.UNKNOWN, SocialDryRunPolicy.AccountMatch.MISSING_VISIBLE)
        }
        val visible = snapshot.nodes.filter(SocialAccessibilityNode::visible)
        val labels = visible.flatMap { listOf(it.text, it.description) }.filter(String::isNotBlank)
        val legal = SocialLegalGatePolicy.classify(snapshot.packageName, labels)
        if (legal != SocialLegalGate.CLEAR) {
            return SocialSnapshotDecision(SocialScreenKind.LEGAL, SocialDryRunPolicy.AccountMatch.MISSING_VISIBLE)
        }
        if (SocialDryRunPolicy.hasLoginOrChallenge(platform, labels)) {
            return SocialSnapshotDecision(
                SocialScreenKind.LOGIN_CHALLENGE,
                SocialDryRunPolicy.AccountMatch.MISSING_VISIBLE,
            )
        }
        val ownerNodes = visible.asSequence()
            .filterNot(SocialAccessibilityNode::editable)
            .filter { node ->
                node.viewId in rule.viewIds || rule.descriptionPrefixes.any { prefix ->
                    node.description.startsWith(prefix, ignoreCase = true)
                }
            }
            .toList()
        val canonicalExpected = SocialDryRunPolicy.normalizeAccountLabel(platform, expectedAccount)
        val matchingOwnerNodes = canonicalExpected?.let { expected ->
            ownerNodes.filter { node ->
                sequenceOf(node.text, node.description)
                    .filter(String::isNotBlank)
                    .any { raw ->
                        val normalized = SocialDryRunPolicy.normalizeAccountLabel(platform, raw)
                        val stableLabel = raw.trim().lowercase().removePrefix("@")
                        val exactPrefixBoundary = stableLabel.startsWith(expected) &&
                            (stableLabel.length == expected.length || stableLabel[expected.length].let { ch ->
                                !ch.isLetterOrDigit() && ch != '_' && ch != '.'
                            })
                        normalized == expected || stableLabel == expected ||
                            exactPrefixBoundary ||
                            stableLabel.startsWith("$expected ") ||
                            stableLabel.startsWith("$expected,") ||
                            stableLabel.startsWith("$expected\n")
                    }
            }
        }.orEmpty()
        val accountMatch = when {
            canonicalExpected == null -> SocialDryRunPolicy.AccountMatch.MISSING_EXPECTED
            ownerNodes.isEmpty() -> SocialDryRunPolicy.AccountMatch.MISSING_VISIBLE
            ownerNodes.size > 1 -> SocialDryRunPolicy.AccountMatch.AMBIGUOUS
            matchingOwnerNodes.size == 1 -> SocialDryRunPolicy.AccountMatch.MATCH
            else -> SocialDryRunPolicy.AccountMatch.MISMATCH
        }
        return if (accountMatch == SocialDryRunPolicy.AccountMatch.MATCH) {
            SocialSnapshotDecision(SocialScreenKind.ACCOUNT_PROOF, accountMatch)
        } else {
            SocialSnapshotDecision(SocialScreenKind.UNKNOWN, accountMatch)
        }
    }

    fun evaluateFlow(
        platform: SocialPlatform,
        expectedPackage: String,
        expectedAccount: String?,
        expectedMediaPath: String?,
        expectedMediaName: String?,
        snapshot: SocialAccessibilitySnapshot,
        rule: SocialAccountOwnershipRule = productionRules.getValue(platform),
    ): SocialSnapshotFlowDecision = SocialSnapshotFlowDecision(
        snapshotDecision = classify(platform, expectedPackage, expectedAccount, snapshot, rule),
        exactMediaVisible = snapshot.consistent && SocialDryRunPolicy.exactMediaVisible(
            snapshot.visibleLabels(), expectedMediaPath, expectedMediaName,
        ),
    )

    fun accountFixtureDiagnostic(
        platform: SocialPlatform,
        expectedAccount: String,
        snapshot: SocialAccessibilitySnapshot,
    ): SocialAccountFixtureDiagnostic {
        if (!snapshot.consistent) {
            return SocialAccountFixtureDiagnostic(0, 0, 0, false, false, emptyList(), emptyList(), emptyList())
        }
        val matches = snapshot.nodes.filter { node ->
            SocialDryRunPolicy.matchAccount(
                platform,
                expectedAccount,
                listOfNotNull(node.text.takeIf(String::isNotBlank), node.description.takeIf(String::isNotBlank)),
            ) == SocialDryRunPolicy.AccountMatch.MATCH
        }
        val mentions = snapshot.nodes.filter { node ->
            SocialDryRunPolicy.mentionsAccount(platform, expectedAccount, node.text) ||
                SocialDryRunPolicy.mentionsAccount(platform, expectedAccount, node.description)
        }
        return SocialAccountFixtureDiagnostic(
            exactLabelNodeCount = matches.size,
            mentionNodeCount = mentions.size,
            visibleNonEditableCount = mentions.count { it.visible && !it.editable },
            textMatches = mentions.any { it.text.isNotBlank() },
            descriptionMatches = mentions.any { it.description.isNotBlank() },
            viewIds = mentions.asSequence()
                .map(SocialAccessibilityNode::viewId)
                .filter(String::isNotBlank)
                .distinct()
                .sorted()
                .toList(),
            labeledViewIds = snapshot.nodes.asSequence()
                .filter { it.visible && (it.text.isNotBlank() || it.description.isNotBlank()) }
                .map(SocialAccessibilityNode::viewId)
                .filter(String::isNotBlank)
                .distinct()
                .sorted()
                .take(32)
                .toList(),
            labeledNodeShapes = snapshot.nodes.asSequence()
                .filter { it.visible && (it.text.isNotBlank() || it.description.isNotBlank()) }
                .filter { it.viewId.isNotBlank() }
                .map { node ->
                    val channel = when {
                        node.text.isNotBlank() && node.description.isNotBlank() -> "text+desc"
                        node.text.isNotBlank() -> "text"
                        else -> "desc"
                    }
                    "${node.viewId}@${node.bounds}:$channel"
                }
                .distinct()
                .take(32)
                .toList(),
            visibleNodeShapes = snapshot.nodes.asSequence()
                .filter { it.visible && it.viewId.isNotBlank() }
                .map { node -> "${node.viewId}@${node.bounds}" }
                .distinct()
                .take(64)
                .toList(),
        )
    }

    fun mayOpenTikTokCreate(
        decision: SocialSnapshotDecision,
        snapshot: SocialAccessibilitySnapshot,
    ): Boolean = snapshot.consistent &&
        decision.screen == SocialScreenKind.ACCOUNT_PROOF &&
        decision.accountMatch == SocialDryRunPolicy.AccountMatch.MATCH &&
        snapshot.nodes.count { node ->
            node.visible && !node.editable && node.viewId == TIKTOK_CREATE_ENTRY_VIEW_ID
        } == 1

    fun mayOpenTikTokProfile(snapshot: SocialAccessibilitySnapshot): Boolean =
        snapshot.consistent && snapshot.packageName == "com.zhiliaoapp.musically" &&
            snapshot.nodes.count { node ->
                node.visible && node.viewId == TIKTOK_PROFILE_ENTRY_VIEW_ID &&
                    node.bounds == TIKTOK_PROFILE_ENTRY_BOUNDS
            } == 1

    fun mayOpenInstagramProfile(snapshot: SocialAccessibilitySnapshot): Boolean =
        snapshot.consistent && snapshot.packageName == "com.instagram.android" &&
            snapshot.nodes.count { node ->
                node.visible && node.viewId == INSTAGRAM_PROFILE_ENTRY_VIEW_ID &&
                    node.bounds == INSTAGRAM_PROFILE_ENTRY_BOUNDS
            } == 1

    fun instagramPostCount(snapshot: SocialAccessibilitySnapshot): Int? {
        if (!snapshot.consistent || snapshot.packageName != "com.instagram.android") return null
        val values = snapshot.nodes.filter { node ->
            node.visible && node.viewId == INSTAGRAM_POST_COUNT_VIEW_ID
        }.mapNotNull { it.text.trim().toIntOrNull() }
        return values.singleOrNull()
    }

    fun isInstagramExactProfile(snapshot: SocialAccessibilitySnapshot, expectedAccount: String): Boolean {
        if (!snapshot.consistent || snapshot.packageName != "com.instagram.android") return false
        val expected = expectedAccount.trim().removePrefix("@").trim()
        return expected.isNotBlank() && snapshot.nodes.count { node ->
            node.visible && node.viewId == INSTAGRAM_ACCOUNT_VIEW_ID &&
                sequenceOf(node.text, node.description).any { label ->
                    label.trim().removePrefix("@").trim() == expected
                }
        } == 1
    }

    fun detectInstagramOwnedAccount(snapshot: SocialAccessibilitySnapshot): String? {
        if (!snapshot.consistent || snapshot.packageName != "com.instagram.android") return null
        val labels = snapshot.visibleLabels().toSet()
        if (!labels.containsAll(setOf("Edit profile", "Share profile"))) return null
        if (instagramPostCount(snapshot) == null) return null
        return snapshot.nodes.asSequence()
            .filter { it.visible && !it.editable && it.viewId == INSTAGRAM_ACCOUNT_VIEW_ID }
            .mapNotNull { node ->
                sequenceOf(node.text, node.description)
                    .mapNotNull { SocialDryRunPolicy.normalizeAccountLabel(SocialPlatform.INSTAGRAM, it) }
                    .firstOrNull()
            }
            .distinct()
            .singleOrNull()
    }

    fun detectTikTokOwnedAccount(snapshot: SocialAccessibilitySnapshot): String? {
        if (!snapshot.consistent || snapshot.packageName != "com.zhiliaoapp.musically") return null
        val labels = snapshot.visibleLabels().toSet()
        if (labels.none { it == "Edit profile" || it == "Set up profile" }) return null
        return snapshot.nodes.asSequence()
            .filter { it.visible && !it.editable && it.viewId == "com.zhiliaoapp.musically:id/sxa" }
            .mapNotNull { node ->
                sequenceOf(node.text, node.description)
                    .mapNotNull { SocialDryRunPolicy.normalizeAccountLabel(SocialPlatform.TIKTOK, it) }
                    .firstOrNull()
            }
            .distinct()
            .singleOrNull()
    }

    fun isInstagramCreateMenu(snapshot: SocialAccessibilitySnapshot): Boolean {
        if (!snapshot.consistent || snapshot.packageName != "com.instagram.android") return false
        val labels = snapshot.visibleLabels().toSet()
        return labels.containsAll(setOf("Create", "Reel", "Edits", "Post", "Story"))
    }

    fun isInstagramCreationPicker(snapshot: SocialAccessibilitySnapshot): Boolean {
        if (!snapshot.consistent || snapshot.packageName != "com.instagram.android") return false
        val labels = snapshot.visibleLabels().toSet()
        return labels.contains("Recents") && labels.contains("Select") &&
            labels.any { it == "POST" || it == "Post" } &&
            labels.any { it == "REEL" || it == "Reel" }
    }

    fun isCalibratedInstagramReelPicker(snapshot: SocialAccessibilitySnapshot): Boolean {
        if (!snapshot.consistent || snapshot.packageName != "com.instagram.android") return false
        val labels = snapshot.visibleLabels().toSet()
        return labels.containsAll(setOf("New reel", "Recents", "Select")) &&
            snapshot.nodes.any { node ->
                node.visible && Regex("\\d{1,2}:\\d{2}").matches(node.text.trim())
            }
    }

    fun instagramUniqueVideoDurationLabel(snapshot: SocialAccessibilitySnapshot): String? {
        if (!isCalibratedInstagramReelPicker(snapshot)) return null
        return snapshot.nodes.asSequence()
            .filter { it.visible }
            .map { it.text.trim() }
            .filter { Regex("\\d{1,2}:\\d{2}").matches(it) }
            .distinct()
            .singleOrNull()
    }

    fun isInstagramDraftPrompt(snapshot: SocialAccessibilitySnapshot): Boolean {
        if (!snapshot.consistent || snapshot.packageName != "com.instagram.android") return false
        val labels = snapshot.visibleLabels().toSet()
        return labels.containsAll(setOf("Keep editing your draft?", "Keep editing", "Start new video"))
    }

    fun isVerifiedInstagramVideoEditor(snapshot: SocialAccessibilitySnapshot): Boolean {
        if (!snapshot.consistent || snapshot.packageName != "com.instagram.android") return false
        val labels = snapshot.visibleLabels().toSet()
        return labels.contains("Next") && labels.contains("Edit video") &&
            labels.containsAll(setOf("Text", "Voice", "Captions", "Stickers"))
    }

    fun isInstagramCaptionComposerStructure(snapshot: SocialAccessibilitySnapshot): Boolean {
        if (!snapshot.consistent || snapshot.packageName != "com.instagram.android") return false
        val labels = snapshot.visibleLabels().toSet()
        return labels.containsAll(setOf("New reel", "Edit cover", "Save draft", "Next")) &&
            snapshot.nodes.count { it.visible && it.editable } == 1
    }

    fun isInstagramOwnedCaptionComposerBase(snapshot: SocialAccessibilitySnapshot): Boolean {
        if (!snapshot.consistent || snapshot.packageName != "com.instagram.android") return false
        return snapshot.visibleLabels().toSet().containsAll(setOf("New reel", "Edit cover", "Save draft"))
    }

    fun isVerifiedInstagramCaptionComposer(
        snapshot: SocialAccessibilitySnapshot,
        expectedCaption: String,
    ): Boolean {
        if (!snapshot.consistent || snapshot.packageName != "com.instagram.android") return false
        val labels = snapshot.visibleLabels().toSet()
        // After Instagram closes its caption editor with OK, "Edit cover" is no longer
        // exposed in the accessibility tree.  The owned non-final composer is still
        // uniquely identified by New reel + Save draft + Next and its single editable
        // caption node.  Do not require Share here: that belongs only to the following
        // confirmation screen and remains behind the dry-run guard.
        if (!labels.containsAll(setOf("New reel", "Save draft", "Next"))) return false
        val editable = snapshot.nodes.filter { it.visible && it.editable }
        if (expectedCaption.isBlank()) {
            return editable.all { it.text.isBlank() }
        }
        return editable.size == 1 && editable.single().text.trim() == expectedCaption.trim()
    }

    fun isVerifiedInstagramFinalShare(snapshot: SocialAccessibilitySnapshot): Boolean {
        if (!snapshot.consistent || snapshot.packageName != "com.instagram.android") return false
        val labels = snapshot.visibleLabels().toSet()
        return labels.containsAll(setOf("About Reels", "Share", "Cancel")) &&
            labels.any { it.startsWith("Your reel will be shared publicly") }
    }

    fun isVerifiedInstagramPublicationReceipt(
        snapshot: SocialAccessibilitySnapshot,
        preShareFingerprint: String,
    ): Boolean {
        if (!snapshot.consistent || snapshot.packageName != "com.instagram.android") return false
        if (preShareFingerprint.isBlank() || snapshot.fingerprint == preShareFingerprint) return false
        val labels = snapshot.visibleLabels().toSet()
        return labels.contains("Posted! All set.") && labels.contains("Want to send it to friends?")
    }

    fun isThreadsOwnedHome(snapshot: SocialAccessibilitySnapshot, expectedAccount: String): Boolean {
        if (!snapshot.consistent || snapshot.packageName != "com.instagram.barcelona") return false
        val expected = expectedAccount.trim().removePrefix("@").trim()
        val labels = snapshot.visibleLabels().toSet()
        return expected.isNotBlank() && labels.contains(expected) && labels.contains("What's new?") &&
            !labels.contains("New thread")
    }

    fun isThreadsInstagramStoryPrompt(snapshot: SocialAccessibilitySnapshot): Boolean {
        if (!snapshot.consistent || snapshot.packageName != "com.instagram.barcelona") return false
        val labels = snapshot.visibleLabels()
        return labels.contains("Add this to your Instagram story") &&
            labels.contains("Try it")
    }

    fun isThreadsDiscardDraftPrompt(snapshot: SocialAccessibilitySnapshot): Boolean {
        if (!snapshot.consistent || snapshot.packageName != "com.instagram.barcelona") return false
        return snapshot.visibleLabels().toSet().containsAll(
            setOf("Save to drafts?", "Save", "Don't save", "Keep editing"),
        )
    }

    fun isThreadsComposer(snapshot: SocialAccessibilitySnapshot, expectedAccount: String): Boolean {
        if (!snapshot.consistent || snapshot.packageName != "com.instagram.barcelona") return false
        val expected = expectedAccount.trim().removePrefix("@").trim()
        val labels = snapshot.visibleLabels().toSet()
        return expected.isNotBlank() && labels.contains(expected) && labels.contains("New thread") &&
            snapshot.nodes.count { it.visible && it.editable } == 1
    }

    fun isThreadsGallery(snapshot: SocialAccessibilitySnapshot): Boolean {
        if (!snapshot.consistent || snapshot.packageName != "com.instagram.barcelona") return false
        return snapshot.visibleLabels().toSet().containsAll(setOf("Gallery", "Done", "Recents"))
    }

    fun isThreadsReadyComposer(
        snapshot: SocialAccessibilitySnapshot,
        expectedAccount: String,
        expectedCaption: String,
        mediaSelectedInCurrentAttempt: Boolean,
    ): Boolean {
        if (!mediaSelectedInCurrentAttempt) return false
        if (!snapshot.consistent || snapshot.packageName != "com.instagram.barcelona") return false
        if (expectedAccount.trim().removePrefix("@").trim().isBlank()) return false
        val labels = snapshot.visibleLabels().toSet()
        // The verified account header scrolls above the visible viewport after a tall video is
        // attached. Account ownership was already proven on Home and again on the pre-media
        // composer in this same synchronous attempt; the final snapshot must still prove the
        // owned composer structure, exact caption and final Post controls without requiring an
        // off-screen header to remain visible.
        if (!labels.contains("New thread")) return false
        // Threads removes the pre-attachment "Add to thread" affordance once a video is
        // attached. Requiring that stale label rejected the genuine final composer. Media
        // ownership is instead carried explicitly from the exact current-job gallery
        // selection + Done handshake performed by the service in this same attempt.
        if (!labels.containsAll(setOf("Post", "Post options"))) return false
        val editable = snapshot.nodes.filter { it.visible && it.editable }
        return editable.size == 1 && editable.single().text.trim() == expectedCaption.trim()
    }

    fun isThreadsPublicationReceipt(
        snapshot: SocialAccessibilitySnapshot,
        prePostFingerprint: String,
        expectedAccount: String,
        expectedCaption: String,
    ): Boolean {
        if (!snapshot.consistent || snapshot.packageName != "com.instagram.barcelona") return false
        if (prePostFingerprint.isBlank() || snapshot.fingerprint == prePostFingerprint) return false
        val expected = expectedAccount.trim().removePrefix("@").trim()
        val labels = snapshot.visibleLabels().map(String::trim).toSet()
        return expected.isNotBlank() && labels.contains(expected) &&
            labels.contains(expectedCaption.trim()) && labels.contains("Threads")
    }

    fun isYouTubeOwnedChannel(snapshot: SocialAccessibilitySnapshot, expectedAccount: String): Boolean {
        if (!snapshot.consistent || snapshot.packageName != "com.google.android.youtube") return false
        val expected = expectedAccount.trim().removePrefix("@").trim()
        val labels = snapshot.visibleLabels().map { it.trim().removePrefix("@").trim() }
        return expected.isNotBlank() && labels.contains(expected) && youtubeVideoCount(snapshot) != null
    }

    fun isYouTubeAccountTab(snapshot: SocialAccessibilitySnapshot, expectedAccount: String): Boolean {
        if (!snapshot.consistent || snapshot.packageName != "com.google.android.youtube") return false
        val expected = expectedAccount.trim().removePrefix("@").trim()
        val normalized = snapshot.visibleLabels().map { it.trim().removePrefix("@").trim() }.toSet()
        return expected.isNotBlank() && normalized.contains(expected) && snapshot.visibleLabels().contains("View channel")
    }

    fun isYouTubeHome(snapshot: SocialAccessibilitySnapshot): Boolean {
        if (!snapshot.consistent || snapshot.packageName != "com.google.android.youtube") return false
        val labels = snapshot.visibleLabels().map(String::trim).toSet()
        return setOf("Home", "Shorts", "Subscriptions", "You").all(labels::contains)
    }

    fun youtubeVideoCount(snapshot: SocialAccessibilitySnapshot): Int? {
        if (!snapshot.consistent || snapshot.packageName != "com.google.android.youtube") return null
        val regex = Regex("^(\\d+)\\s+videos?$", RegexOption.IGNORE_CASE)
        return snapshot.visibleLabels().mapNotNull { regex.matchEntire(it.trim())?.groupValues?.get(1)?.toIntOrNull() }
            .distinct().singleOrNull()
    }

    fun isYouTubeShortEntry(snapshot: SocialAccessibilitySnapshot): Boolean {
        if (!snapshot.consistent || snapshot.packageName != "com.google.android.youtube") return false
        val labels = snapshot.visibleLabels().toSet()
        val galleryAction = labels.contains("Add from Gallery") || labels.contains("Add")
        return galleryAction && labels.containsAll(setOf("Short", "Video", "Live", "Post"))
    }

    fun isYouTubeDraftPrompt(snapshot: SocialAccessibilitySnapshot): Boolean {
        if (!snapshot.consistent || snapshot.packageName != "com.google.android.youtube") return false
        val labels = snapshot.visibleLabels().toSet()
        return labels.containsAll(setOf("Continue your draft video?", "Start over", "Continue"))
    }

    fun isYouTubeGallery(snapshot: SocialAccessibilitySnapshot): Boolean {
        if (!snapshot.consistent || snapshot.packageName != "com.google.android.youtube") return false
        val labels = snapshot.visibleLabels().toSet()
        return labels.contains("Gallery") && labels.contains("Next")
    }

    fun isYouTubeTrimEditor(snapshot: SocialAccessibilitySnapshot): Boolean {
        if (!snapshot.consistent || snapshot.packageName != "com.google.android.youtube") return false
        val labels = snapshot.visibleLabels().toSet()
        return labels.contains("Done") && labels.any { it.contains("Drag to adjust video") }
    }

    fun isYouTubeShortEditor(snapshot: SocialAccessibilitySnapshot): Boolean {
        if (!snapshot.consistent || snapshot.packageName != "com.google.android.youtube") return false
        return snapshot.visibleLabels().toSet().containsAll(setOf("Add sound", "Edit", "Next"))
    }

    fun isYouTubeDetails(snapshot: SocialAccessibilitySnapshot, expectedAccount: String): Boolean {
        if (!snapshot.consistent || snapshot.packageName != "com.google.android.youtube") return false
        val expected = expectedAccount.trim().removePrefix("@").trim()
        val normalized = snapshot.visibleLabels().map { it.trim().removePrefix("@").trim() }.toSet()
        val labels = snapshot.visibleLabels().toSet()
        val audienceControl = labels.contains("Select audience") || labels.contains("Audience")
        return expected.isNotBlank() && normalized.contains(expected) &&
            labels.containsAll(setOf("Add details", "Upload Short")) && audienceControl &&
            snapshot.nodes.count { it.visible && it.editable } == 1
    }

    fun isYouTubeAudienceScreen(snapshot: SocialAccessibilitySnapshot): Boolean {
        if (!snapshot.consistent || snapshot.packageName != "com.google.android.youtube") return false
        val labels = snapshot.visibleLabels().toSet()
        return labels.contains("Select audience") && labels.contains("No, it's not made for kids")
    }

    fun isYouTubeNotMadeForKidsSelected(snapshot: SocialAccessibilitySnapshot): Boolean {
        if (!isYouTubeAudienceScreen(snapshot)) return false
        return snapshot.visibleLabels().any {
            it.trim().replace('\u2019', '\'').equals(
                "This video is set to not made for kids",
                ignoreCase = true,
            )
        }
    }

    fun isYouTubeReadyToUpload(
        snapshot: SocialAccessibilitySnapshot,
        expectedAccount: String,
        expectedTitle: String,
        audienceVerifiedInCurrentFlow: Boolean = false,
    ): Boolean {
        if (!isYouTubeDetails(snapshot, expectedAccount)) return false
        val editable = snapshot.nodes.filter { it.visible && it.editable }
        val labels = snapshot.visibleLabels().toSet()
        val audienceVerified = audienceVerifiedInCurrentFlow || labels.contains("Not made for kids") || labels.any {
            it.replace('\u2019', '\'') == "No, it's not made for kids"
        }
        val expected = expectedTitle.trim()
        val actual = editable.singleOrNull()?.text?.trim().orEmpty()
        val titleVerified = if (expected.isEmpty()) {
            actual.isEmpty() || actual == "Caption your Short"
        } else {
            actual == expected
        }
        return audienceVerified && editable.size == 1 && titleVerified
    }

    fun isYouTubePublicationReceipt(
        snapshot: SocialAccessibilitySnapshot,
        expectedAccount: String,
        prePublishCount: Int,
        preUploadFingerprint: String,
    ): Boolean {
        if (preUploadFingerprint.isBlank() || snapshot.fingerprint == preUploadFingerprint) return false
        if (!isYouTubeOwnedChannel(snapshot, expectedAccount)) return false
        val countAdvanced = (youtubeVideoCount(snapshot) ?: return false) > prePublishCount
        val uploadAccepted = snapshot.visibleLabels().any {
            it.trim().startsWith("Uploading", ignoreCase = true)
        }
        return countAdvanced || uploadAccepted
    }

    fun isVerifiedInstagramDirectShareComposer(
        snapshot: SocialAccessibilitySnapshot,
        expectedCaption: String,
    ): Boolean {
        if (!snapshot.consistent || snapshot.packageName != "com.instagram.android") return false
        val labels = snapshot.visibleLabels().toSet()
        // Current Instagram renders the focused caption editor directly over the final
        // Share composer. "Edit cover" is no longer visible there, while the owned final
        // screen is uniquely anchored by New reel + Save draft + Link a reel + Share.
        if (!labels.containsAll(setOf("New reel", "Save draft", "Link a reel", "Share"))) return false
        val editable = snapshot.nodes.filter { it.visible && it.editable }
        return if (expectedCaption.isBlank()) {
            editable.all { it.text.isBlank() }
        } else {
            editable.size == 1 && editable.single().text.trim() == expectedCaption.trim()
        }
    }

    fun hasTikTokFirstVideoPickerStructure(snapshot: SocialAccessibilitySnapshot): Boolean =
        snapshot.packageName == "com.zhiliaoapp.musically" &&
            snapshot.generationBefore > 0L &&
            snapshot.generationAfter > 0L &&
            snapshot.fingerprint.isNotBlank() &&
            snapshot.nodes.any { it.visible && it.viewId == TIKTOK_MEDIA_PAGER_VIEW_ID } &&
            snapshot.nodes.count {
                it.visible && it.viewId in tikTokMediaTileViewIds &&
                    it.bounds == TIKTOK_FIRST_MEDIA_TILE_BOUNDS
            } == 1

    fun tikTokFirstMediaTileViewId(snapshot: SocialAccessibilitySnapshot): String? =
        snapshot.nodes.singleOrNull {
            it.visible && it.viewId in tikTokMediaTileViewIds &&
                it.bounds == TIKTOK_FIRST_MEDIA_TILE_BOUNDS
        }?.viewId

    fun tikTokMediaNextViewId(snapshot: SocialAccessibilitySnapshot): String? =
        snapshot.nodes.singleOrNull { node ->
            node.visible && node.viewId in tikTokMediaNextViewIds &&
                node.bounds == TIKTOK_MEDIA_NEXT_BOUNDS && node.text.trim() in tikTokSelectedMediaNextLabels
        }?.viewId

    fun isStableTikTokFirstVideoPickerPair(
        previous: SocialAccessibilitySnapshot,
        current: SocialAccessibilitySnapshot,
    ): Boolean =
        hasTikTokFirstVideoPickerStructure(previous) &&
            hasTikTokFirstVideoPickerStructure(current) &&
            tikTokPickerActionSignature(previous) == tikTokPickerActionSignature(current)

    private fun tikTokPickerActionSignature(snapshot: SocialAccessibilitySnapshot): List<String> =
        snapshot.nodes.asSequence()
            .filter { node ->
                node.visible && (
                    node.viewId == TIKTOK_MEDIA_PAGER_VIEW_ID ||
                        node.viewId in tikTokMediaTileViewIds ||
                        node.viewId in tikTokMediaPickerCloseViewIds
                    )
            }
            .map { "${it.viewId}@${it.bounds}" }
            .sorted()
            .toList()

    fun isCalibratedTikTokFirstVideoPicker(snapshot: SocialAccessibilitySnapshot): Boolean =
        snapshot.consistent &&
            hasTikTokFirstVideoPickerStructure(snapshot)

    fun isVerifiedTikTokSelectedVideoPreview(snapshot: SocialAccessibilitySnapshot): Boolean =
        snapshot.consistent && snapshot.packageName == "com.zhiliaoapp.musically" &&
            snapshot.nodes.count { node ->
                node.visible && node.viewId in tikTokMediaNextViewIds &&
                    node.bounds == TIKTOK_MEDIA_NEXT_BOUNDS && node.text.trim() in tikTokSelectedMediaNextLabels
            } == 1

    fun isVerifiedTikTokVideoEditor(snapshot: SocialAccessibilitySnapshot): Boolean =
        snapshot.consistent && snapshot.packageName == "com.zhiliaoapp.musically" &&
            snapshot.nodes.count { node ->
                node.visible && node.viewId in tikTokEditorNextViewIds &&
                    node.bounds == TIKTOK_EDITOR_NEXT_BOUNDS && node.text.trim() == "Next"
            } == 1

    fun tikTokEditorNextViewId(snapshot: SocialAccessibilitySnapshot): String? =
        snapshot.nodes.singleOrNull { node ->
            node.visible && node.viewId in tikTokEditorNextViewIds &&
                node.bounds == TIKTOK_EDITOR_NEXT_BOUNDS && node.text.trim() == "Next"
        }?.viewId

    fun isTikTokFinalComposerStructure(snapshot: SocialAccessibilitySnapshot): Boolean =
        snapshot.consistent && snapshot.packageName == "com.zhiliaoapp.musically" &&
            snapshot.nodes.count { it.visible && it.editable && it.viewId in tikTokCaptionViewIds } == 1 &&
          snapshot.nodes.count {
              it.visible && it.viewId in tikTokPreviewViewIds
          } == 1 &&
            snapshot.nodes.count { node ->
                node.visible && node.viewId in tikTokDraftsViewIds &&
                    node.bounds == TIKTOK_DRAFTS_BOUNDS && node.text.trim() == "Drafts"
            } == 1 &&
            snapshot.nodes.count { node ->
                node.visible && node.viewId in tikTokPostViewIds &&
                    node.bounds == TIKTOK_POST_BOUNDS && node.text.trim() == "Post"
            } == 1

    fun tikTokCaptionViewId(snapshot: SocialAccessibilitySnapshot): String? =
        snapshot.nodes.singleOrNull { it.visible && it.editable && it.viewId in tikTokCaptionViewIds }?.viewId

    fun tikTokPostViewId(snapshot: SocialAccessibilitySnapshot): String? =
        snapshot.nodes.singleOrNull { node ->
            node.visible && node.viewId in tikTokPostViewIds &&
                node.bounds == TIKTOK_POST_BOUNDS && node.text.trim() == "Post"
        }?.viewId

    fun isVerifiedTikTokFinalComposer(
        snapshot: SocialAccessibilitySnapshot,
        expectedCaption: String,
    ): Boolean {
        if (!isTikTokFinalComposerStructure(snapshot)) return false
        val actual = snapshot.nodes.single {
            it.visible && it.editable && it.viewId in tikTokCaptionViewIds
        }.text.trim()
        val expected = expectedCaption.trim()
        val emptyPlaceholder = actual.startsWith("Add a catchy title") ||
            actual.startsWith("Add description") ||
            actual.startsWith("Добавьте привлекательный заголовок") ||
            actual.startsWith("Добавьте описание") ||
            actual.startsWith("Writing a long description can help") ||
            actual.startsWith("Подробное описание поможет")
        val exactEmptyCounter = snapshot.visibleLabels().count { it.trim() == "0/4000" } == 1
        return if (expected.isEmpty()) actual.isEmpty() || emptyPlaceholder || exactEmptyCounter else actual == expected
    }

    fun isTikTokDirectPublicationReceipt(
        snapshot: SocialAccessibilitySnapshot,
        composerFingerprint: String,
    ): Boolean {
        if (
            !snapshot.consistent || snapshot.packageName != "com.zhiliaoapp.musically" ||
            composerFingerprint.isBlank() || snapshot.fingerprint == composerFingerprint
        ) return false
        val labels = snapshot.visibleLabels().map(String::trim)
        val hasOwnedFeedNavigation = listOf("Home", "Friends", "Inbox", "Profile").all(labels::contains)
        val hasFreshAge = labels.any { label ->
            label == "Just now" || label == "Только что" ||
                Regex("^(?:[0-5]?\\d)s ago$").matches(label) ||
                Regex("(?:^|\\s|·)(?:[0-5]?\\d)s ago(?:$|\\s)").containsMatchIn(label) ||
                Regex("(?:^|\\s|·)(?:[0-5]?\\d) сек.*назад(?:$|\\s)", RegexOption.IGNORE_CASE)
                    .containsMatchIn(label)
        }
        // Photo posts expose an explicit Photo marker; ordinary videos do not. This predicate is
        // evaluated only after the exact final Post control was verified and pressed, so a fresh
        // owned feed with a changed fingerprint is the platform receipt for either media kind.
        return hasOwnedFeedNavigation && hasFreshAge
    }

    /**
     * A logged-in TikTok shell is sufficient for a real post routed to this dedicated device.
     * TikTok does not expose the profile handle on Home/Friends, so requiring it on every job
     * incorrectly blocks an already authenticated device. Legal/login precedence is still
     * evaluated by [classify] before this fallback is considered by the service.
     */
    fun isTikTokAuthenticatedShell(snapshot: SocialAccessibilitySnapshot): Boolean {
        if (!snapshot.consistent || snapshot.packageName != "com.zhiliaoapp.musically") return false
        return hasTikTokOwnedNavigation(snapshot) &&
            snapshot.nodes.count { node ->
                node.visible && node.viewId == TIKTOK_CREATE_ENTRY_VIEW_ID &&
                    (node.description.trim() == "Create" || node.description.trim() == "Создать")
            } == 1
    }

    fun hasTikTokOwnedNavigation(snapshot: SocialAccessibilitySnapshot): Boolean {
        if (!snapshot.consistent || snapshot.packageName != "com.zhiliaoapp.musically") return false
        val labels = snapshot.visibleLabels().map(String::trim)
        return listOf("Home", "Friends", "Inbox", "Profile").all(labels::contains)
    }
}
