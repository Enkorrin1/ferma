package com.elevium.mobileposteragent.service

internal enum class SocialPlatform { INSTAGRAM, TIKTOK }

internal data class SocialDryRunTarget(
    val platform: SocialPlatform,
    val packageName: String,
)

/** Pure, fail-closed policy shared by the accessibility flow and host tests. */
internal object SocialDryRunPolicy {
    internal enum class AccountMatch { MATCH, MISSING_EXPECTED, MISSING_VISIBLE, AMBIGUOUS, MISMATCH }

    fun target(value: String): SocialDryRunTarget? = when (value) {
        "instagram_reel_dry_run" -> SocialDryRunTarget(SocialPlatform.INSTAGRAM, "com.instagram.android")
        "tiktok_post_dry_run" -> SocialDryRunTarget(SocialPlatform.TIKTOK, "com.zhiliaoapp.musically")
        else -> null
    }

    fun automationTarget(value: String): SocialDryRunTarget? = when (value) {
        "instagram_reel", "instagram_reel_dry_run" ->
            SocialDryRunTarget(SocialPlatform.INSTAGRAM, "com.instagram.android")
        "tiktok_post", "tiktok_post_dry_run" ->
            SocialDryRunTarget(SocialPlatform.TIKTOK, "com.zhiliaoapp.musically")
        else -> null
    }

    fun isDryRun(value: String): Boolean = target(value) != null

    fun hasLoginOrChallenge(platform: SocialPlatform, labels: List<String>): Boolean {
        val values = labels.map { it.trim().lowercase() }
        return when (platform) {
            SocialPlatform.INSTAGRAM -> values.any {
                it == "log in" || it == "sign up" || it.contains("confirm it's you") ||
                    it.contains("suspicious login") || it.contains("challenge required")
            }
            SocialPlatform.TIKTOK -> values.any {
                it == "log in" || it == "sign up" || it.contains("verify your identity") ||
                    it.contains("security verification")
            }
        }
    }

    fun exactMediaVisible(labels: List<String>, absolutePath: String?, displayName: String?): Boolean {
        val path = absolutePath?.trim().orEmpty()
        val name = displayName?.trim().orEmpty()
        if (path.isEmpty() || name.isEmpty()) return false
        return labels.any { label ->
            val value = label.trim()
            value == path || value == name || value.endsWith("/$name") || value.contains(path)
        }
    }

    fun captionMatches(expected: String, actual: String?): Boolean =
        expected.trim() == actual?.trim().orEmpty()

    fun normalizeAccountLabel(platform: SocialPlatform, value: String?): String? {
        var normalized = value?.trim()?.lowercase()?.takeIf(String::isNotEmpty) ?: return null
        normalized = normalized.removePrefix("@")
        return when (platform) {
            SocialPlatform.INSTAGRAM -> normalized.replace(" ", "")
            SocialPlatform.TIKTOK -> normalized.replace(" ", "")
        }.takeIf(String::isNotEmpty)
    }

    fun matchAccount(
        platform: SocialPlatform,
        expected: String?,
        visibleCandidates: List<String>,
    ): AccountMatch {
        val canonicalExpected = normalizeAccountLabel(platform, expected)
            ?: return AccountMatch.MISSING_EXPECTED
        val candidates = visibleCandidates.mapNotNull { normalizeAccountLabel(platform, it) }.distinct()
        if (candidates.isEmpty()) return AccountMatch.MISSING_VISIBLE
        if (candidates.size != 1) return AccountMatch.AMBIGUOUS
        return if (candidates.single() == canonicalExpected) AccountMatch.MATCH else AccountMatch.MISMATCH
    }

    /** Diagnostic-only containment check; never sufficient to authorize account ownership. */
    fun mentionsAccount(platform: SocialPlatform, expected: String?, value: String?): Boolean {
        val canonicalExpected = normalizeAccountLabel(platform, expected) ?: return false
        val candidate = normalizeAccountLabel(platform, value) ?: return false
        return candidate.contains(canonicalExpected)
    }

    /** Real posting remains disabled until an app-specific receipt verifier exists. */
    fun realPublicationAllowed(target: String): Boolean = false

    /** A dry run never owns the final external mutation action. */
    fun mayClickFinalAction(target: String): Boolean = !isDryRun(target) && realPublicationAllowed(target)
}
