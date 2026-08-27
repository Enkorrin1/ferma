package com.elevium.mobileposteragent.service

import org.junit.Assert.assertEquals
import org.junit.Test

class SocialLegalGatePolicyTest {
    @Test fun instagramMetaTermsIsBlockedOnlyWithOwnedContinue() {
        assertEquals(SocialLegalGate.INSTAGRAM_TERMS, SocialLegalGatePolicy.classify(
            "com.instagram.android", listOf("Review the Meta Terms", "Continue"),
        ))
        assertEquals(SocialLegalGate.CLEAR, SocialLegalGatePolicy.classify(
            "com.instagram.android", listOf("Continue editing your reel"),
        ))
    }

    @Test fun tiktokAgreeAndContinueIsBlocked() {
        assertEquals(SocialLegalGate.TIKTOK_TERMS, SocialLegalGatePolicy.classify(
            "com.zhiliaoapp.musically", listOf("Agree and continue"),
        ))
    }

    @Test fun crossPackageLabelsCannotTriggerOrAuthorizeAnotherApp() {
        assertEquals(SocialLegalGate.CLEAR, SocialLegalGatePolicy.classify(
            "com.instagram.android", listOf("Agree and continue"),
        ))
        assertEquals(SocialLegalGate.CLEAR, SocialLegalGatePolicy.classify(
            "com.miui.home", listOf("Meta Terms", "Continue"),
        ))
    }
}
