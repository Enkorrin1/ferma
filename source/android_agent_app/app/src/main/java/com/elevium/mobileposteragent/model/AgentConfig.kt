package com.elevium.mobileposteragent.model

import android.net.Uri

data class AgentConfig(
    val hubUrl: String,
    val runnerToken: String,
    val deviceLabel: String,
    val accountLabel: String?,
    val pinterestBoard: String? = null,
) {
    override fun toString(): String =
        "AgentConfig(hubUrl=$hubUrl, runnerToken=<redacted>, deviceLabel=$deviceLabel, " +
            "accountLabel=$accountLabel, pinterestBoard=$pinterestBoard)"

    fun isValid(): Boolean {
        val uri = runCatching { Uri.parse(hubUrl.trim()) }.getOrNull()
        return runnerToken.isNotBlank() &&
            deviceLabel.isNotBlank() &&
            uri?.scheme.equals("https", ignoreCase = true) &&
            !uri?.host.isNullOrBlank()
    }
}
