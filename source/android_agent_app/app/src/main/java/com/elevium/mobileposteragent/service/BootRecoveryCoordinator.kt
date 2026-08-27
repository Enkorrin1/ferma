package com.elevium.mobileposteragent.service

import android.content.Context
import android.os.UserManager
import com.elevium.mobileposteragent.data.ConfigStore

internal object BootRecoveryPolicy {
    fun shouldStart(enabled: Boolean, userUnlocked: Boolean, configValid: Boolean): Boolean =
        enabled && userUnlocked && configValid
}

object BootRecoveryCoordinator {
    private const val PREFS = "agent_boot_recovery"
    private const val ENABLED = "autostart_enabled"
    private const val PENDING_UNLOCK = "pending_unlock"

    fun setEnabled(context: Context, enabled: Boolean) {
        context.createDeviceProtectedStorageContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(ENABLED, enabled).apply()
    }

    fun recover(context: Context): Boolean {
        val deviceContext = context.createDeviceProtectedStorageContext()
        val prefs = deviceContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(ENABLED, false)) return false
        val unlocked = context.getSystemService(UserManager::class.java)?.isUserUnlocked == true
        if (!unlocked) {
            prefs.edit().putBoolean(PENDING_UNLOCK, true).apply()
            return false
        }
        val configValid = ConfigStore(context).load()?.isValid() == true
        if (!BootRecoveryPolicy.shouldStart(true, true, configValid)) return false
        prefs.edit().putBoolean(PENDING_UNLOCK, false).apply()
        AgentForegroundService.start(context)
        return true
    }
}
