package com.elevium.mobileposteragent.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import java.io.File
import com.elevium.mobileposteragent.data.ConfigStore
import com.elevium.mobileposteragent.data.UsbPairingClient
import com.elevium.mobileposteragent.model.AgentConfig

/** Internal entry point used by an ADB `run-as` command on the farm computer. */
class UsbFarmControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_PAIR_AGENT -> pairAgent(context, intent)
            ACTION_REPORT_STATUS -> {
                val capture = DebugScreenshotCapture.hasPermission()
                val captureVerified = DebugScreenshotCapture.hasVerifiedPermission()
                val accessibility = AgentAccessibilityService.instance?.canAutomate() == true
                File(context.filesDir, STATUS_FILENAME).writeText(
                    "capture=$capture;capture_verified=$captureVerified;accessibility=$accessibility",
                    Charsets.US_ASCII,
                )
            }
            ACTION_ENABLE_USB_MODE, ACTION_START_AGENT, ACTION_RESTART_AGENT -> {
                val requestedUrl = intent.getStringExtra(EXTRA_HUB_URL).orEmpty()
                if (!UsbFarmControlPolicy.isAllowedHubUrl(requestedUrl)) return
                val store = ConfigStore(context)
                val current = store.load() ?: return
                val updated = current.copy(hubUrl = UsbFarmControlPolicy.CANONICAL_HUB_URL)
                if (!updated.isValid()) return
                store.save(updated)
                if (intent.action == ACTION_RESTART_AGENT) {
                    AgentForegroundService.stop(context)
                }
                BootRecoveryCoordinator.setEnabled(context, true)
                AgentForegroundService.start(context)
            }
            ACTION_STOP_AGENT -> {
                BootRecoveryCoordinator.setEnabled(context, false)
                AgentForegroundService.stop(context)
            }
        }
    }

    private fun pairAgent(context: Context, intent: Intent) {
        val requestedUrl = intent.getStringExtra(EXTRA_HUB_URL).orEmpty()
        val code = intent.getStringExtra(EXTRA_PAIRING_CODE).orEmpty().trim()
        if (!UsbFarmControlPolicy.isAllowedHubUrl(requestedUrl) ||
            !UsbFarmControlPolicy.isAllowedPairingCode(code)
        ) return
        val pending = goAsync()
        Thread({
            try {
                val store = ConfigStore(context)
                val current = store.load()
                val deviceId = AgentForegroundService.stableDeviceId(context)
                val label = current?.deviceLabel?.takeIf(String::isNotBlank) ?: Build.MODEL.orEmpty().ifBlank { "Android" }
                val paired = UsbPairingClient().pair(
                    UsbFarmControlPolicy.CANONICAL_HUB_URL,
                    code,
                    deviceId,
                    label,
                )
                val updated = AgentConfig(
                    hubUrl = paired.hubUrl,
                    runnerToken = paired.runnerToken,
                    deviceLabel = label,
                    accountLabel = current?.accountLabel,
                    pinterestBoard = current?.pinterestBoard,
                )
                if (!updated.isValid()) return@Thread
                store.save(updated)
                BootRecoveryCoordinator.setEnabled(context, true)
                AgentForegroundService.start(context)
            } catch (_: Exception) {
                // Pairing fails closed; the one-time code can be regenerated from the desktop panel.
            } finally {
                pending.finish()
            }
        }, "usb-pairing").start()
    }

    companion object {
        const val ACTION_ENABLE_USB_MODE =
            "com.elevium.mobileposteragent.action.ENABLE_USB_FARM_MODE"
        const val ACTION_START_AGENT =
            "com.elevium.mobileposteragent.action.START_USB_FARM_AGENT"
        const val ACTION_STOP_AGENT =
            "com.elevium.mobileposteragent.action.STOP_USB_FARM_AGENT"
        const val ACTION_RESTART_AGENT =
            "com.elevium.mobileposteragent.action.RESTART_USB_FARM_AGENT"
        const val ACTION_PAIR_AGENT =
            "com.elevium.mobileposteragent.action.PAIR_USB_FARM_AGENT"
        const val ACTION_REPORT_STATUS =
            "com.elevium.mobileposteragent.action.REPORT_USB_FARM_STATUS"
        const val EXTRA_HUB_URL = "hub_url"
        const val EXTRA_PAIRING_CODE = "pairing_code"
        const val STATUS_FILENAME = "usb_farm_status.txt"
    }
}

object UsbFarmControlPolicy {
    const val CANONICAL_HUB_URL = "http://127.0.0.1:18082"

    fun isAllowedHubUrl(value: String): Boolean = value.trim() == CANONICAL_HUB_URL

    fun isAllowedPairingCode(value: String): Boolean = value.matches(Regex("^[A-Z2-9]{8,16}$"))
}
