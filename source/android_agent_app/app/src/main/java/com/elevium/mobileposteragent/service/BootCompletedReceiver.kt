package com.elevium.mobileposteragent.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action !in setOf(
                Intent.ACTION_LOCKED_BOOT_COMPLETED,
                Intent.ACTION_BOOT_COMPLETED,
                Intent.ACTION_USER_UNLOCKED,
                "android.intent.action.QUICKBOOT_POWERON",
            )) return
        BootRecoveryCoordinator.recover(context)
    }
}
