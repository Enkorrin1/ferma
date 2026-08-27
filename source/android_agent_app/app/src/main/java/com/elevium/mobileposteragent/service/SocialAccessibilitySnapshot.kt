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
    const val TIKTOK_MEDIA_PICKER_CLOSE_BOUNDS = "[16,63][96,143]"
    const val TIKTOK_MEDIA_TILE_VIEW_ID = "com.zhiliaoapp.musically:id/ooy"
    const val TIKTOK_FIRST_MEDIA_TILE_BOUNDS = "[243,245][478,483]"
    const val TIKTOK_MEDIA_NEXT_VIEW_ID = "com.zhiliaoapp.musically:id/pt2"
    const val TIKTOK_MEDIA_NEXT_BOUNDS = "[368,1304][688,1392]"
    const val TIKTOK_EDITOR_NEXT_VIEW_ID = "com.zhiliaoapp.musically:id/ptb"
    const val TIKTOK_EDITOR_NEXT_BOUNDS = "[494,1336][566,1376]"
    const val TIKTOK_CAPTION_VIEW_ID = "com.zhiliaoapp.musically:id/h3r"
    const val TIKTOK_PREVIEW_VIEW_ID = "com.zhiliaoapp.musically:id/ksj"
    const val TIKTOK_PHOTO_PREVIEW_VIEW_ID = "com.zhiliaoapp.musically:id/af_"
    const val TIKTOK_DRAFTS_VIEW_ID = "com.zhiliaoapp.musically:id/gdl"
    const val TIKTOK_POST_VIEW_ID = "com.zhiliaoapp.musically:id/t7a"
    const val TIKTOK_DRAFTS_BOUNDS = "[24,1304][352,1400]"
    const val TIKTOK_POST_BOUNDS = "[368,1304][696,1400]"
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

    fun isInstagramCreateMenu(snapshot: SocialAccessibilitySnapshot): Boolean {
        if (!snapshot.consistent || snapshot.packageName != "com.instagram.android") return false
        val labels = snapshot.visibleLabels().toSet()
        return labels.containsAll(setOf("Create", "Reel", "Edits", "Post", "Story"))
    }

    fun isCalibratedInstagramReelPicker(snapshot: SocialAccessibilitySnapshot): Boolean {
        if (!snapshot.consistent || snapshot.packageName != "com.instagram.android") return false
        val labels = snapshot.visibleLabels().toSet()
        return labels.containsAll(setOf("New reel", "Recents", "Select")) &&
            snapshot.nodes.any { it.visible && it.text.trim() == "0:05" }
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
        return labels.containsAll(setOf("New reel", "Save draft", "Next")) &&
            snapshot.nodes.count { it.visible && it.editable } == 1 &&
            snapshot.nodes.single { it.visible && it.editable }.text.trim() == expectedCaption.trim()
    }

    fun isVerifiedInstagramFinalShare(snapshot: SocialAccessibilitySnapshot): Boolean {
        if (!snapshot.consistent || snapshot.packageName != "com.instagram.android") return false
        val labels = snapshot.visibleLabels().toSet()
        return labels.containsAll(setOf("About Reels", "Share", "Cancel")) &&
            labels.any { it.startsWith("Your reel will be shared publicly") }
    }

    fun isCalibratedTikTokFirstVideoPicker(snapshot: SocialAccessibilitySnapshot): Boolean =
        snapshot.consistent &&
            snapshot.packageName == "com.zhiliaoapp.musically" &&
            snapshot.nodes.any { it.visible && it.viewId == TIKTOK_MEDIA_PAGER_VIEW_ID } &&
            snapshot.nodes.count {
                it.visible && it.viewId == TIKTOK_MEDIA_TILE_VIEW_ID &&
                    it.bounds == TIKTOK_FIRST_MEDIA_TILE_BOUNDS
            } == 1

    fun isVerifiedTikTokSelectedVideoPreview(snapshot: SocialAccessibilitySnapshot): Boolean =
        snapshot.consistent && snapshot.packageName == "com.zhiliaoapp.musically" &&
            snapshot.nodes.count { node ->
                node.visible && node.viewId == TIKTOK_MEDIA_NEXT_VIEW_ID &&
                    node.bounds == TIKTOK_MEDIA_NEXT_BOUNDS && node.text.trim() == "Next"
            } == 1

    fun isVerifiedTikTokVideoEditor(snapshot: SocialAccessibilitySnapshot): Boolean =
        snapshot.consistent && snapshot.packageName == "com.zhiliaoapp.musically" &&
            snapshot.nodes.count { node ->
                node.visible && node.viewId == TIKTOK_EDITOR_NEXT_VIEW_ID &&
                    node.bounds == TIKTOK_EDITOR_NEXT_BOUNDS && node.text.trim() == "Next"
            } == 1

    fun isTikTokFinalComposerStructure(snapshot: SocialAccessibilitySnapshot): Boolean =
        snapshot.consistent && snapshot.packageName == "com.zhiliaoapp.musically" &&
            snapshot.nodes.count { it.visible && it.editable && it.viewId == TIKTOK_CAPTION_VIEW_ID } == 1 &&
          snapshot.nodes.count {
              it.visible && it.viewId in setOf(TIKTOK_PREVIEW_VIEW_ID, TIKTOK_PHOTO_PREVIEW_VIEW_ID)
          } == 1 &&
            snapshot.nodes.count { node ->
                node.visible && node.viewId == TIKTOK_DRAFTS_VIEW_ID &&
                    node.bounds == TIKTOK_DRAFTS_BOUNDS && node.text.trim() == "Drafts"
            } == 1 &&
            snapshot.nodes.count { node ->
                node.visible && node.viewId == TIKTOK_POST_VIEW_ID &&
                    node.bounds == TIKTOK_POST_BOUNDS && node.text.trim() == "Post"
            } == 1

    fun isVerifiedTikTokFinalComposer(
        snapshot: SocialAccessibilitySnapshot,
        expectedCaption: String,
    ): Boolean {
        if (!isTikTokFinalComposerStructure(snapshot)) return false
        val actual = snapshot.nodes.single {
            it.visible && it.editable && it.viewId == TIKTOK_CAPTION_VIEW_ID
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
                Regex("^(?:[0-5]?\\d) сек.*назад$", RegexOption.IGNORE_CASE).matches(label)
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
