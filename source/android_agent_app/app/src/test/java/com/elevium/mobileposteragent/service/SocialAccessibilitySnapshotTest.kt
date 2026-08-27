package com.elevium.mobileposteragent.service

import org.junit.Assert.assertEquals
import org.junit.Test

class SocialAccessibilitySnapshotTest {
    private val instagramRule = SocialAccountOwnershipRule(
        viewIds = setOf("com.instagram.android:id/account_identity"),
    )

    private fun node(
        text: String = "",
        description: String = "",
        viewId: String = "",
        visible: Boolean = true,
        editable: Boolean = false,
        path: String = "root/0",
        bounds: String = "[1,1][20,20]",
    ) = SocialAccessibilityNode(
        path, text, description, "", "android.view.View", viewId, visible, editable, bounds,
    )

    private fun snapshot(
        nodes: List<SocialAccessibilityNode>,
        packageName: String = "com.instagram.android",
        before: Long = 4,
        after: Long = 4,
        fingerprint: String = "fixture-fingerprint",
    ) = SocialAccessibilitySnapshot(packageName, before, after, fingerprint, nodes)

    @Test
    fun exactOwnedAccountIsTheOnlyPositiveMilestoneState() {
        val decision = SocialAccessibilitySnapshotPolicy.classify(
            SocialPlatform.INSTAGRAM,
            "com.instagram.android",
            "@Main Account",
            snapshot(listOf(node(description = "mainaccount", viewId = "com.instagram.android:id/account_identity"))),
            instagramRule,
        )
        assertEquals(SocialScreenKind.ACCOUNT_PROOF, decision.screen)
        assertEquals(SocialDryRunPolicy.AccountMatch.MATCH, decision.accountMatch)
    }

    @Test
    fun genericMentionsHiddenAndEditableNodesAreNeverAccountProof() {
        val nodes = listOf(
            node(text = "@mainaccount"),
            node(description = "mainaccount", viewId = "com.instagram.android:id/account_identity", visible = false),
            node(description = "mainaccount", viewId = "com.instagram.android:id/account_identity", editable = true),
        )
        val decision = SocialAccessibilitySnapshotPolicy.classify(
            SocialPlatform.INSTAGRAM, "com.instagram.android", "mainaccount", snapshot(nodes), instagramRule,
        )
        assertEquals(SocialScreenKind.UNKNOWN, decision.screen)
        assertEquals(SocialDryRunPolicy.AccountMatch.MISSING_VISIBLE, decision.accountMatch)
    }

    @Test
    fun ambiguousAndMismatchedOwnedAccountsFailClosed() {
        val ambiguous = snapshot(listOf(
            node(description = "mainaccount", viewId = "com.instagram.android:id/account_identity", path = "root/0"),
            node(description = "other", viewId = "com.instagram.android:id/account_identity", path = "root/1"),
        ))
        assertEquals(
            SocialDryRunPolicy.AccountMatch.AMBIGUOUS,
            SocialAccessibilitySnapshotPolicy.classify(
                SocialPlatform.INSTAGRAM, "com.instagram.android", "mainaccount", ambiguous, instagramRule,
            ).accountMatch,
        )
        assertEquals(
            SocialDryRunPolicy.AccountMatch.MISMATCH,
            SocialAccessibilitySnapshotPolicy.classify(
                SocialPlatform.INSTAGRAM,
                "com.instagram.android",
                "mainaccount",
                snapshot(listOf(node(description = "other", viewId = "com.instagram.android:id/account_identity"))),
                instagramRule,
            ).accountMatch,
        )
    }

    @Test
    fun legalAndLoginPrecedeAccountProof() {
        val owned = node(description = "mainaccount", viewId = "com.instagram.android:id/account_identity")
        val legal = SocialAccessibilitySnapshotPolicy.classify(
            SocialPlatform.INSTAGRAM,
            "com.instagram.android",
            "mainaccount",
            snapshot(listOf(node(text = "Meta Terms"), node(text = "Continue"), owned)),
            instagramRule,
        )
        assertEquals(SocialScreenKind.LEGAL, legal.screen)
        val login = SocialAccessibilitySnapshotPolicy.classify(
            SocialPlatform.INSTAGRAM,
            "com.instagram.android",
            "mainaccount",
            snapshot(listOf(node(text = "Log in"), owned)),
            instagramRule,
        )
        assertEquals(SocialScreenKind.LOGIN_CHALLENGE, login.screen)
    }

    @Test
    fun invalidPackageGenerationNodesOrFingerprintFailClosed() {
        val owned = listOf(node(description = "mainaccount", viewId = "com.instagram.android:id/account_identity"))
        val invalid = listOf(
            snapshot(owned, packageName = "com.other"),
            snapshot(owned, before = 4, after = 5),
            snapshot(emptyList()),
            snapshot(owned, fingerprint = ""),
        )
        invalid.forEach {
            assertEquals(
                SocialScreenKind.UNKNOWN,
                SocialAccessibilitySnapshotPolicy.classify(
                    SocialPlatform.INSTAGRAM, "com.instagram.android", "mainaccount", it, instagramRule,
                ).screen,
            )
        }
    }

    @Test
    fun emptyProductionAllowlistsCannotAuthorizeAccountOrFinalAction() {
        val decision = SocialAccessibilitySnapshotPolicy.classify(
            SocialPlatform.INSTAGRAM,
            "com.instagram.android",
            "mainaccount",
            snapshot(listOf(node(description = "mainaccount", viewId = "com.instagram.android:id/account_identity"))),
        )
        assertEquals(SocialScreenKind.UNKNOWN, decision.screen)
        assertEquals(false, SocialDryRunPolicy.mayClickFinalAction("instagram_reel_dry_run"))
        assertEquals(false, SocialDryRunPolicy.mayClickFinalAction("tiktok_post_dry_run"))
    }

    @Test
    fun instagramPublicationReceiptRequiresExactFreshOwnedConfirmation() {
        val receipt = snapshot(
            listOf(
                node(text = "Posted! All set.", path = "root/0"),
                node(text = "Want to send it to friends?", path = "root/1"),
            ),
            fingerprint = "post-share",
        )
        assertEquals(
            true,
            SocialAccessibilitySnapshotPolicy.isVerifiedInstagramPublicationReceipt(receipt, "pre-share"),
        )
        assertEquals(
            false,
            SocialAccessibilitySnapshotPolicy.isVerifiedInstagramPublicationReceipt(receipt, "post-share"),
        )
        assertEquals(
            false,
            SocialAccessibilitySnapshotPolicy.isVerifiedInstagramPublicationReceipt(
                receipt.copy(nodes = receipt.nodes.take(1)),
                "pre-share",
            ),
        )
    }

    @Test
    fun threadsFlowRequiresOwnedAccountExactComposerAndFreshReceipt() {
        val home = snapshot(
            listOf(node(text = "pinv786"), node(text = "What's new?", path = "root/1")),
            packageName = "com.instagram.barcelona",
        )
        assertEquals(true, SocialAccessibilitySnapshotPolicy.isThreadsOwnedHome(home, "@pinv786"))
        val composer = home.copy(
            fingerprint = "composer",
            nodes = home.nodes + listOf(
                node(text = "New thread", path = "root/2"),
                node(text = "caption", editable = true, path = "root/3"),
                node(text = "Post", path = "root/4"),
                node(text = "Post options", path = "root/5"),
                node(text = "Add to thread", path = "root/6"),
            ),
        )
        assertEquals(true, SocialAccessibilitySnapshotPolicy.isThreadsComposer(composer, "pinv786"))
        assertEquals(
            true,
            SocialAccessibilitySnapshotPolicy.isThreadsReadyComposer(composer, "pinv786", "caption"),
        )
        val receipt = snapshot(
            listOf(node(text = "Posted"), node(text = "View", path = "root/1")),
            packageName = "com.instagram.barcelona",
            fingerprint = "receipt",
        )
        assertEquals(true, SocialAccessibilitySnapshotPolicy.isThreadsPublicationReceipt(receipt, "composer"))
        assertEquals(false, SocialAccessibilitySnapshotPolicy.isThreadsPublicationReceipt(receipt, "receipt"))
    }

    @Test
    fun threadsInstagramStoryPromptRequiresExactOwnedOverlayLabels() {
        val prompt = snapshot(
            nodes = listOf(
                node(text = "Add this to your Instagram story"),
                node(text = "Try it", path = "root/1"),
            ),
            packageName = "com.instagram.barcelona",
        )
        assertEquals(true, SocialAccessibilitySnapshotPolicy.isThreadsInstagramStoryPrompt(prompt))
        assertEquals(false,
            SocialAccessibilitySnapshotPolicy.isThreadsInstagramStoryPrompt(
                prompt.copy(nodes = prompt.nodes.filterNot { it.text == "Try it" })
            )
        )
        assertEquals(false,
            SocialAccessibilitySnapshotPolicy.isThreadsInstagramStoryPrompt(
                prompt.copy(packageName = "com.instagram.android")
            )
        )
    }

    @Test
    fun accountAndMediaAreEvaluatedFromTheSameImmutableSnapshot() {
        val media = "/storage/emulated/0/Pictures/MobilePosterAgent/current.png"
        val immutable = snapshot(listOf(
            node(description = "mainaccount", viewId = "com.instagram.android:id/account_identity"),
            node(description = media, path = "root/1"),
        ))
        val flow = SocialAccessibilitySnapshotPolicy.evaluateFlow(
            SocialPlatform.INSTAGRAM,
            "com.instagram.android",
            "mainaccount",
            media,
            "current.png",
            immutable,
            instagramRule,
        )
        assertEquals(SocialScreenKind.ACCOUNT_PROOF, flow.snapshotDecision.screen)
        assertEquals(true, flow.exactMediaVisible)
        val staleDifferentSnapshot = immutable.copy(
            generationAfter = 5,
            nodes = immutable.nodes.filterNot { it.description == media },
        )
        val rejected = SocialAccessibilitySnapshotPolicy.evaluateFlow(
            SocialPlatform.INSTAGRAM,
            "com.instagram.android",
            "mainaccount",
            media,
            "current.png",
            staleDifferentSnapshot,
            instagramRule,
        )
        assertEquals(SocialScreenKind.UNKNOWN, rejected.snapshotDecision.screen)
        assertEquals(false, rejected.exactMediaVisible)
    }

    @Test
    fun fixtureDiagnosticReportsOnlyStructuralExactAccountSignals() {
        val diagnostic = SocialAccessibilitySnapshotPolicy.accountFixtureDiagnostic(
            SocialPlatform.INSTAGRAM,
            "@pinv768",
            snapshot(listOf(
                node(text = "pinv768", viewId = "com.instagram.android:id/profile_header_username"),
                node(description = "@pinv768", path = "root/1"),
                node(text = "pinv768 fan", viewId = "com.instagram.android:id/unrelated", path = "root/2"),
            )),
        )
        assertEquals(2, diagnostic.exactLabelNodeCount)
        assertEquals(3, diagnostic.mentionNodeCount)
        assertEquals(3, diagnostic.visibleNonEditableCount)
        assertEquals(true, diagnostic.textMatches)
        assertEquals(true, diagnostic.descriptionMatches)
        assertEquals(
            listOf(
                "com.instagram.android:id/profile_header_username",
                "com.instagram.android:id/unrelated",
            ),
            diagnostic.viewIds,
        )
        assertEquals(false, diagnostic.redactedMessage().contains("pinv768"))
        assertEquals(
            listOf(
                "com.instagram.android:id/profile_header_username",
                "com.instagram.android:id/unrelated",
            ),
            diagnostic.labeledViewIds,
        )
        assertEquals(2, diagnostic.labeledNodeShapes.size)
    }

    @Test
    fun productionTikTokOwnershipRuleAcceptsOnlyCapturedExactAccountNode() {
        val tiktok = snapshot(
            nodes = listOf(node(text = "@pin.van4", viewId = "com.zhiliaoapp.musically:id/sxa")),
            packageName = "com.zhiliaoapp.musically",
        )
        val accepted = SocialAccessibilitySnapshotPolicy.classify(
            SocialPlatform.TIKTOK,
            "com.zhiliaoapp.musically",
            "pin.van4",
            tiktok,
        )
        assertEquals(SocialScreenKind.ACCOUNT_PROOF, accepted.screen)
        assertEquals(SocialDryRunPolicy.AccountMatch.MATCH, accepted.accountMatch)
    }

    @Test
    fun tiktokCreateEntryRequiresExactAccountProofAndOneExactVisibleNode() {
        val account = node(text = "@pin.van4", viewId = "com.zhiliaoapp.musically:id/sxa")
        val create = node(
            description = "Create",
            viewId = SocialAccessibilitySnapshotPolicy.TIKTOK_CREATE_ENTRY_VIEW_ID,
            path = "root/create",
        )
        val current = snapshot(listOf(account, create), packageName = "com.zhiliaoapp.musically")
        val decision = SocialAccessibilitySnapshotPolicy.classify(
            SocialPlatform.TIKTOK, "com.zhiliaoapp.musically", "pin.van4", current,
        )
        assertEquals(true, SocialAccessibilitySnapshotPolicy.mayOpenTikTokCreate(decision, current))
        assertEquals(
            false,
            SocialAccessibilitySnapshotPolicy.mayOpenTikTokCreate(
                decision,
                current.copy(nodes = listOf(account, create.copy(visible = false))),
            ),
        )
        assertEquals(
            false,
            SocialAccessibilitySnapshotPolicy.mayOpenTikTokCreate(
                decision.copy(screen = SocialScreenKind.UNKNOWN),
                current,
            ),
        )
    }

    @Test
    fun tiktokEmptyCaptionAcceptsCurrentPlaceholderVariantsOnlyOnExactFinalComposer() {
        fun finalSnapshot(captionText: String, extraNodes: List<SocialAccessibilityNode> = emptyList()) = snapshot(
            packageName = "com.zhiliaoapp.musically",
            nodes = listOf(
                node(
                    text = captionText,
                    viewId = SocialAccessibilitySnapshotPolicy.TIKTOK_CAPTION_VIEW_ID,
                    editable = true,
                    path = "root/caption",
                ),
                node(
                    description = "Selected image preview",
                    viewId = SocialAccessibilitySnapshotPolicy.TIKTOK_PHOTO_PREVIEW_VIEW_ID,
                    path = "root/preview",
                ),
                node(
                    text = "Drafts",
                    viewId = SocialAccessibilitySnapshotPolicy.TIKTOK_DRAFTS_VIEW_ID,
                    bounds = SocialAccessibilitySnapshotPolicy.TIKTOK_DRAFTS_BOUNDS,
                    path = "root/drafts",
                ),
                node(
                    text = "Post",
                    viewId = SocialAccessibilitySnapshotPolicy.TIKTOK_POST_VIEW_ID,
                    bounds = SocialAccessibilitySnapshotPolicy.TIKTOK_POST_BOUNDS,
                    path = "root/post",
                ),
            ) + extraNodes,
        )

        assertEquals(
            true,
            SocialAccessibilitySnapshotPolicy.isVerifiedTikTokFinalComposer(
                finalSnapshot("Add a catchy title"), "",
            ),
        )
        assertEquals(
            true,
            SocialAccessibilitySnapshotPolicy.isVerifiedTikTokFinalComposer(
                finalSnapshot("Add a catchy title\nWriting a long description can help get more views"), "",
            ),
        )
        assertEquals(
            true,
            SocialAccessibilitySnapshotPolicy.isVerifiedTikTokFinalComposer(
                finalSnapshot("Writing a long description can help get 3x more views on average."), "",
            ),
        )
        assertEquals(
            true,
            SocialAccessibilitySnapshotPolicy.isVerifiedTikTokFinalComposer(
                finalSnapshot("", listOf(node(text = "0/4000", path = "root/counter"))), "",
            ),
        )
        assertEquals(
            false,
            SocialAccessibilitySnapshotPolicy.isVerifiedTikTokFinalComposer(
                finalSnapshot("A real user title"), "",
            ),
        )
    }

    @Test
    fun tiktokDirectPublicationReceiptRequiresFreshOwnedFeedForPhotoOrVideo() {
        val freshReceipt = snapshot(
            packageName = "com.zhiliaoapp.musically",
            fingerprint = "fresh-feed",
            nodes = listOf("Home", "Friends", "Inbox", "Profile", "Photo", "2s ago").mapIndexed { index, label ->
                node(text = label, path = "root/$index")
            },
        )
        assertEquals(
            true,
            SocialAccessibilitySnapshotPolicy.isTikTokDirectPublicationReceipt(freshReceipt, "composer"),
        )
        assertEquals(
            false,
            SocialAccessibilitySnapshotPolicy.isTikTokDirectPublicationReceipt(
                freshReceipt.copy(fingerprint = "composer"), "composer",
            ),
        )
        assertEquals(
            false,
            SocialAccessibilitySnapshotPolicy.isTikTokDirectPublicationReceipt(
                freshReceipt.copy(nodes = freshReceipt.nodes.filterNot { it.text == "2s ago" }), "composer",
            ),
        )
        assertEquals(
            true,
            SocialAccessibilitySnapshotPolicy.isTikTokDirectPublicationReceipt(
                freshReceipt.copy(nodes = freshReceipt.nodes.filterNot { it.text == "Photo" }), "composer",
            ),
        )
    }

    @Test
    fun tiktokAuthenticatedShellAllowsHiddenProfileHandleButRequiresExactOwnedNavigation() {
        val shell = snapshot(
            packageName = "com.zhiliaoapp.musically",
            nodes = listOf("Home", "Friends", "Inbox", "Profile").mapIndexed { index, label ->
                node(text = label, path = "root/nav/$index")
            } + node(
                description = "Create",
                viewId = SocialAccessibilitySnapshotPolicy.TIKTOK_CREATE_ENTRY_VIEW_ID,
                path = "root/create",
            ),
        )
        assertEquals(true, SocialAccessibilitySnapshotPolicy.isTikTokAuthenticatedShell(shell))
        assertEquals(true, SocialAccessibilitySnapshotPolicy.hasTikTokOwnedNavigation(shell))
        assertEquals(
            true,
            SocialAccessibilitySnapshotPolicy.hasTikTokOwnedNavigation(
                shell.copy(nodes = shell.nodes.filterNot {
                    it.viewId == SocialAccessibilitySnapshotPolicy.TIKTOK_CREATE_ENTRY_VIEW_ID
                }),
            ),
        )
        assertEquals(
            false,
            SocialAccessibilitySnapshotPolicy.isTikTokAuthenticatedShell(shell.copy(nodes = emptyList())),
        )
        assertEquals(
            false,
            SocialAccessibilitySnapshotPolicy.isTikTokAuthenticatedShell(
                shell.copy(nodes = shell.nodes.filterNot { it.text == "Profile" }),
            ),
        )
        assertEquals(
            false,
            SocialAccessibilitySnapshotPolicy.isTikTokAuthenticatedShell(
                shell.copy(nodes = shell.nodes.map { node ->
                    if (node.viewId == SocialAccessibilitySnapshotPolicy.TIKTOK_CREATE_ENTRY_VIEW_ID) {
                        node.copy(description = "Add friends")
                    } else node
                }),
            ),
        )
    }
    @Test
    fun instagramCreationPickerAcceptsCurrentPostToReelSwitcher() {
        val picker = snapshot(
            listOf(
                node(text = "New post", path = "root/0"),
                node(text = "Recents", path = "root/1"),
                node(text = "Select", path = "root/2"),
                node(text = "POST", path = "root/3"),
                node(text = "STORY", path = "root/4"),
                node(text = "REEL", path = "root/5"),
            ),
        )
        assertEquals(true, SocialAccessibilitySnapshotPolicy.isInstagramCreationPicker(picker))
        assertEquals(
            false,
            SocialAccessibilitySnapshotPolicy.isInstagramCreationPicker(
                picker.copy(nodes = picker.nodes.filterNot { it.text == "REEL" }),
            ),
        )
    }

    @Test
    fun instagramReelPickerAcceptsArbitraryVideoDurationButNotMissingMedia() {
        val picker = snapshot(
            listOf(
                node(text = "New reel", path = "root/0"),
                node(text = "Recents", path = "root/1"),
                node(text = "Select", path = "root/2"),
                node(text = "0:37", path = "root/3"),
            ),
        )
        assertEquals(true, SocialAccessibilitySnapshotPolicy.isCalibratedInstagramReelPicker(picker))
        assertEquals(
            false,
            SocialAccessibilitySnapshotPolicy.isCalibratedInstagramReelPicker(
                picker.copy(nodes = picker.nodes.filterNot { it.text == "0:37" }),
            ),
        )
    }

    @Test
    fun instagramBlankCaptionReadbackAcceptsOwnedUnfocusedComposerHint() {
        val composer = snapshot(
            listOf(
                node(text = "New reel", path = "root/0"),
                node(text = "Write a caption and add hashtags…", path = "root/1"),
                node(text = "Edit cover", path = "root/1a"),
                node(text = "Save draft", path = "root/2"),
                node(text = "Next", path = "root/3"),
            ),
        )
        assertEquals(
            true,
            SocialAccessibilitySnapshotPolicy.isVerifiedInstagramCaptionComposer(composer, ""),
        )
        assertEquals(
            false,
            SocialAccessibilitySnapshotPolicy.isVerifiedInstagramCaptionComposer(
                composer.copy(nodes = composer.nodes + node(text = "unexpected", editable = true, path = "root/9")),
                "",
            ),
        )
    }
}
