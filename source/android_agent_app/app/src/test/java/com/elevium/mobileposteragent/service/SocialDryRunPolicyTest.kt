package com.elevium.mobileposteragent.service

import org.junit.Assert.*
import org.junit.Test

class SocialDryRunPolicyTest {
    @Test fun exactTargetsArePackageScoped() {
        assertEquals("com.instagram.android", SocialDryRunPolicy.target("instagram_reel_dry_run")?.packageName)
        assertEquals("com.zhiliaoapp.musically", SocialDryRunPolicy.target("tiktok_post_dry_run")?.packageName)
        assertNull(SocialDryRunPolicy.target("instagram_reel"))
    }

    @Test fun loginAndChallengeFailClosed() {
        assertTrue(SocialDryRunPolicy.hasLoginOrChallenge(SocialPlatform.INSTAGRAM, listOf("Log in")))
        assertTrue(SocialDryRunPolicy.hasLoginOrChallenge(SocialPlatform.TIKTOK, listOf("Verify your identity")))
        assertFalse(SocialDryRunPolicy.hasLoginOrChallenge(SocialPlatform.INSTAGRAM, listOf("New reel")))
    }

    @Test fun mediaMustBelongToExactPreparedJob() {
        val path = "/storage/emulated/0/Movies/MobilePosterAgent/mobileposter_job-1.mp4"
        assertTrue(SocialDryRunPolicy.exactMediaVisible(listOf("Video: $path"), path, "mobileposter_job-1.mp4"))
        assertFalse(SocialDryRunPolicy.exactMediaVisible(listOf("mobileposter_job-2.mp4"), path, "mobileposter_job-1.mp4"))
        assertFalse(SocialDryRunPolicy.exactMediaVisible(listOf("Recent"), null, null))
    }

    @Test fun captionReadbackIsExactNormalized() {
        assertTrue(SocialDryRunPolicy.captionMatches(" caption ", "caption"))
        assertFalse(SocialDryRunPolicy.captionMatches("caption", "caption changed"))
    }

    @Test fun platformAccountNormalizationAndExactMatch() {
        assertEquals("farm.account", SocialDryRunPolicy.normalizeAccountLabel(SocialPlatform.INSTAGRAM, " @Farm.Account "))
        assertEquals(
            SocialDryRunPolicy.AccountMatch.MATCH,
            SocialDryRunPolicy.matchAccount(SocialPlatform.TIKTOK, "@Farm Account", listOf("farmaccount")),
        )
    }

    @Test fun missingAmbiguousAndMismatchAccountsFailClosed() {
        assertEquals(SocialDryRunPolicy.AccountMatch.MISSING_EXPECTED,
            SocialDryRunPolicy.matchAccount(SocialPlatform.INSTAGRAM, null, listOf("farm")))
        assertEquals(SocialDryRunPolicy.AccountMatch.MISSING_VISIBLE,
            SocialDryRunPolicy.matchAccount(SocialPlatform.INSTAGRAM, "farm", emptyList()))
        assertEquals(SocialDryRunPolicy.AccountMatch.AMBIGUOUS,
            SocialDryRunPolicy.matchAccount(SocialPlatform.INSTAGRAM, "farm", listOf("farm", "other")))
        assertEquals(SocialDryRunPolicy.AccountMatch.MISMATCH,
            SocialDryRunPolicy.matchAccount(SocialPlatform.INSTAGRAM, "farm", listOf("other")))
    }

    @Test fun dryRunAndLegacyRealTargetsNeverOwnFinalAction() {
        assertFalse(SocialDryRunPolicy.mayClickFinalAction("instagram_reel_dry_run"))
        assertFalse(SocialDryRunPolicy.mayClickFinalAction("tiktok_post_dry_run"))
        assertFalse(SocialDryRunPolicy.mayClickFinalAction("instagram_reel"))
        assertFalse(SocialDryRunPolicy.mayClickFinalAction("tiktok_post"))
    }
}
