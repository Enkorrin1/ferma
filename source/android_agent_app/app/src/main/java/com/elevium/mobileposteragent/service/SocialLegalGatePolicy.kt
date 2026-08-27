package com.elevium.mobileposteragent.service

internal enum class SocialLegalGate { CLEAR, INSTAGRAM_TERMS, TIKTOK_TERMS }

internal object SocialLegalGatePolicy {
    fun classify(packageName: String?, visibleLabels: List<String>): SocialLegalGate {
        val normalized = visibleLabels.map { it.trim().lowercase() }
        return when (packageName) {
            "com.instagram.android" -> if (
                normalized.any { it.contains("meta terms") || it.contains("instagram terms") } &&
                normalized.any { it == "continue" }
            ) SocialLegalGate.INSTAGRAM_TERMS else SocialLegalGate.CLEAR
            "com.zhiliaoapp.musically" -> if (
                normalized.any { it == "agree and continue" || it.contains("terms of service") }
            ) SocialLegalGate.TIKTOK_TERMS else SocialLegalGate.CLEAR
            else -> SocialLegalGate.CLEAR
        }
    }
}
