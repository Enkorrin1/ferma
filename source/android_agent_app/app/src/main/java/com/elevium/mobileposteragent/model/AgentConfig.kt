package com.elevium.mobileposteragent.model

import java.net.URI

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
        val uri = runCatching { URI(hubUrl.trim()) }.getOrNull()
        val host = uri?.host.orEmpty()
        val secureRemote = uri?.scheme.equals("https", ignoreCase = true) && host.isNotBlank()
        val localUsbBridge = uri?.scheme.equals("http", ignoreCase = true) &&
            (host == "127.0.0.1" || host.equals("localhost", ignoreCase = true))
        return runnerToken.isNotBlank() &&
            deviceLabel.isNotBlank() &&
            (secureRemote || localUsbBridge)
    }
}
