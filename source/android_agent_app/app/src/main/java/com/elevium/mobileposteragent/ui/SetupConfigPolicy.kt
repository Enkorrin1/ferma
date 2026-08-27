package com.elevium.mobileposteragent.ui

internal object SetupConfigPolicy {
    const val SAVED_TOKEN_PLACEHOLDER = "Saved securely — enter a new token to replace"

    fun resolveRunnerToken(replacement: String?, savedToken: String?): String =
        replacement?.trim()?.takeIf(String::isNotEmpty) ?: savedToken.orEmpty()

    fun normalizeBoardName(value: String?): String? =
        value?.trim()?.takeIf(String::isNotEmpty)
}
