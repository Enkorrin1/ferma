package com.elevium.mobileposteragent.ui

import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.elevium.mobileposteragent.R
import com.elevium.mobileposteragent.data.ConfigStore
import com.elevium.mobileposteragent.data.HubApi
import com.elevium.mobileposteragent.databinding.ActivityMainBinding
import com.elevium.mobileposteragent.model.AgentConfig
import com.elevium.mobileposteragent.service.DebugScreenshotCapture
import com.elevium.mobileposteragent.service.AgentForegroundService
import com.elevium.mobileposteragent.service.BootRecoveryCoordinator

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var configStore: ConfigStore
    private var savedConfig: AgentConfig? = null

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        showToast(if (granted) "Notifications allowed" else "Notifications were not allowed")
    }

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val allGranted = result.values.all { it }
        showToast(if (allGranted) "Storage access allowed" else "Storage access was not allowed")
    }

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        DebugScreenshotCapture.grant(result.resultCode, result.data)
        if (!DebugScreenshotCapture.hasPermission()) {
            getString(R.string.status_screen_capture_denied)
                .also(binding.statusText::setText)
            return@registerForActivityResult
        }
        binding.statusText.text = "Verifying screen capture…"
        Thread {
            val verified = DebugScreenshotCapture.verify(this)
            runOnUiThread {
                binding.statusText.text = if (verified) {
                    "Screen capture verified with a real frame."
                } else {
                    "Screen capture verification failed. Allow it again before starting."
                }
            }
        }.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        configStore = ConfigStore(this)
        populateExistingConfig()
        applyPairingLink(intent)

        binding.saveConfigButton.setOnClickListener {
            val config = readConfigFromUi()
            if (!config.isValid()) {
                binding.statusText.text = getString(R.string.status_missing_config)
                return@setOnClickListener
            }
            configStore.save(config)
            savedConfig = config
            resetTokenReplacementField()
            binding.statusText.text = getString(R.string.status_saved)
        }

        binding.startAgentButton.setOnClickListener {
            val config = readConfigFromUi()
            if (!config.isValid()) {
                binding.statusText.text = getString(R.string.status_missing_config)
                return@setOnClickListener
            }
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P && !hasLegacyStoragePermission()) {
                storagePermissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    ),
                )
                binding.statusText.text = getString(R.string.status_storage_permission_needed)
                return@setOnClickListener
            }
            configStore.save(config)
            savedConfig = config
            resetTokenReplacementField()
            AgentForegroundService.start(this)
            BootRecoveryCoordinator.setEnabled(this, true)
            binding.statusText.text = if (DebugScreenshotCapture.hasVerifiedPermission()) {
                getString(R.string.status_service_started)
            } else {
                "Agent started. Real publishing is enabled; screen evidence is unavailable until capture permission is granted."
            }
            binding.root.postDelayed({
                moveTaskToBack(true)
                finish()
            }, 350)
        }

        binding.stopAgentButton.setOnClickListener {
            BootRecoveryCoordinator.setEnabled(this, false)
            AgentForegroundService.stop(this)
            binding.statusText.text = getString(R.string.status_service_stopped)
        }

        binding.openAccessibilityButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        binding.allowScreenCaptureButton.setOnClickListener {
            requestScreenCapturePermission()
        }

        binding.requestNotificationButton.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                showToast("Notification permission is not required on this Android version")
            }
        }

        binding.enqueuePinterestButton.setOnClickListener {
            enqueueFromUi("pinterest_pin")
        }
        binding.enqueueInstagramButton.setOnClickListener {
            enqueueFromUi("instagram_reel")
        }
        binding.enqueueTikTokButton.setOnClickListener {
            enqueueFromUi("tiktok_post")
        }
        binding.enqueueDryRunButton.setOnClickListener {
            val target = when (binding.manualPlatformGroup.checkedRadioButtonId) {
                R.id.platformInstagram -> "instagram_reel_dry_run"
                R.id.platformTikTok -> "tiktok_post_dry_run"
                else -> "pinterest_dry_run"
            }
            enqueueFromUi(target)
        }

        if (intent.getBooleanExtra(EXTRA_REQUEST_SCREEN_CAPTURE, false)) {
            intent.removeExtra(EXTRA_REQUEST_SCREEN_CAPTURE)
            binding.root.post { requestScreenCapturePermission() }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        applyPairingLink(intent)
    }

    private fun populateExistingConfig() {
        configStore.load()?.let { config ->
            savedConfig = config
            binding.hubUrlInput.setText(config.hubUrl)
            resetTokenReplacementField()
            binding.deviceLabelInput.setText(config.deviceLabel)
            binding.accountLabelInput.setText(config.accountLabel.orEmpty())
            binding.pinterestBoardInput.setText(config.pinterestBoard.orEmpty())
        }
    }

    private fun applyPairingLink(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != "mobileposter" || data.host != "pair") return

        val hubUrl = data.getQueryParameter("hub_url").orEmpty()
        val runnerToken = data.getQueryParameter("runner_token").orEmpty()
        val deviceLabel = data.getQueryParameter("device_label").orEmpty()
        val accountLabel = data.getQueryParameter("account_label").orEmpty()
        val pinterestBoard = data.getQueryParameter("pinterest_board").orEmpty()
        intent.data = null
        setIntent(intent)

        binding.hubUrlInput.setText(hubUrl)
        binding.runnerTokenInput.setText(runnerToken)
        binding.deviceLabelInput.setText(deviceLabel)
        binding.accountLabelInput.setText(accountLabel)
        binding.pinterestBoardInput.setText(pinterestBoard)
        binding.statusText.text = "Pairing link loaded. Save config and enable permissions."
    }

    private fun readConfigFromUi(): AgentConfig {
        return AgentConfig(
            hubUrl = binding.hubUrlInput.text?.toString().orEmpty(),
            runnerToken = SetupConfigPolicy.resolveRunnerToken(
                binding.runnerTokenInput.text?.toString(),
                savedConfig?.runnerToken,
            ),
            deviceLabel = binding.deviceLabelInput.text?.toString().orEmpty(),
            accountLabel = binding.accountLabelInput.text?.toString()?.ifBlank { null },
            pinterestBoard = SetupConfigPolicy.normalizeBoardName(
                binding.pinterestBoardInput.text?.toString(),
            ),
        )
    }

    private fun resetTokenReplacementField() {
        binding.runnerTokenInput.text?.clear()
        binding.runnerTokenInput.hint = SetupConfigPolicy.SAVED_TOKEN_PLACEHOLDER
        binding.runnerTokenInput.contentDescription = getString(R.string.runner_token_replacement_description)
    }

    private fun showToast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    private fun enqueueFromUi(target: String) {
        val config = readConfigFromUi()
        val mediaUrl = binding.manualMediaUrlInput.text?.toString()?.trim().orEmpty()
        val caption = binding.manualCaptionInput.text?.toString().orEmpty()
        val platformAccount = binding.manualPlatformAccountInput.text?.toString()?.trim()?.ifBlank { null }
        if (!config.isValid() || !mediaUrl.startsWith("https://")) {
            binding.statusText.text = "Save a valid config and enter an HTTPS media URL."
            return
        }
        binding.statusText.text = "Adding job to the queue…"
        Thread {
            val result = runCatching {
                HubApi(config, AgentForegroundService.stableDeviceId(this)).enqueueJob(
                    target = target,
                    mediaUrl = mediaUrl,
                    caption = caption,
                    board = config.pinterestBoard,
                    platformAccountLabel = platformAccount,
                )
            }
            runOnUiThread {
                binding.statusText.text = result.fold(
                    onSuccess = { "Queued: $target ($it)" },
                    onFailure = { "Queue error: ${it.message}" },
                )
            }
        }.start()
    }

    private fun requestScreenCapturePermission() {
        val projectionManager = getSystemService(MediaProjectionManager::class.java)
        if (projectionManager == null) {
            binding.statusText.text = getString(R.string.status_screen_capture_denied)
            return
        }
        screenCaptureLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    private fun hasLegacyStoragePermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private companion object {
        const val EXTRA_REQUEST_SCREEN_CAPTURE = "request_screen_capture"
    }
}
