package com.elevium.mobileposteragent.model

data class PublishJob(
    val jobId: String,
    val target: String,
    val caption: String,
    val title: String?,
    val description: String?,
    val link: String?,
    val board: String?,
    val mediaPath: String?,
    val mediaUrl: String?,
    val leaseToken: String,
    val leaseExpiresAt: String,
    val attemptNumber: Int,
    /** Canonical Hub account_label expected for account-sensitive social automation. */
    val accountLabel: String? = null,
    /** Exact visible account identity inside Instagram/TikTok; not used for device routing. */
    val platformAccountLabel: String? = null,
)
