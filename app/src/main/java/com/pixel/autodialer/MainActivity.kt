package com.pixel.autodialer

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.pixel.autodialer.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val requiredPermissions = buildList {
        add(Manifest.permission.CALL_PHONE)
        add(Manifest.permission.READ_PHONE_STATE)
        add(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (!allGranted) {
            Toast.makeText(this, R.string.permission_required, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupListeners()
        checkAndRequestPermissions()
    }

    override fun onResume() {
        super.onResume()
        updateAccessibilityWarningCard()
        CallStateManager.addListener(::onStateChanged)
    }

    override fun onPause() {
        super.onPause()
        CallStateManager.removeListener(::onStateChanged)
    }

    private fun setupListeners() {
        binding.btnEnableAccessibility.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
            Toast.makeText(this, "سرویس Pixel Auto Dialer را در لیست فعال کنید", Toast.LENGTH_LONG).show()
        }

        binding.btnStart.setOnClickListener {
            handleStartClicked()
        }

        binding.btnStop.setOnClickListener {
            handleStopClicked()
        }

        binding.btnTestFlash.setOnClickListener {
            FlashlightManager.toggleFlash(this)
            val isFlashOn = FlashlightManager.isFlashOn()
            binding.btnTestFlash.text = if (isFlashOn) "خاموش کردن فلش" else getString(R.string.btn_test_flash)
        }
    }

    private fun handleStartClicked() {
        val phoneNumber = binding.etPhoneNumber.text?.toString()?.trim() ?: ""
        if (phoneNumber.isEmpty()) {
            binding.etPhoneNumber.error = "لطفاً شماره تلفن را وارد کنید"
            binding.etPhoneNumber.requestFocus()
            return
        }

        if (!hasAllPermissions()) {
            requestPermissionLauncher.launch(requiredPermissions.toTypedArray())
            return
        }

        if (!isAccessibilityServiceEnabled(this, CallAccessibilityService::class.java)) {
            Toast.makeText(this, R.string.accessibility_prompt, Toast.LENGTH_LONG).show()
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
            return
        }

        val timeout = binding.etCallTimeout.text?.toString()?.toIntOrNull() ?: 20
        val delay = binding.etRedialDelay.text?.toString()?.toIntOrNull() ?: 3

        val serviceIntent = Intent(this, RedialService::class.java).apply {
            action = RedialService.ACTION_START
            putExtra(RedialService.EXTRA_PHONE_NUMBER, phoneNumber)
            putExtra(RedialService.EXTRA_TIMEOUT, timeout)
            putExtra(RedialService.EXTRA_DELAY, delay)
        }

        ContextCompat.startForegroundService(this, serviceIntent)
        Toast.makeText(this, "تماس متناوب شروع شد", Toast.LENGTH_SHORT).show()
    }

    private fun handleStopClicked() {
        val serviceIntent = Intent(this, RedialService::class.java).apply {
            action = RedialService.ACTION_STOP
        }
        startService(serviceIntent)
        FlashlightManager.turnOffFlash(this)
        binding.btnTestFlash.text = getString(R.string.btn_test_flash)
        Toast.makeText(this, "تماس متوقف شد", Toast.LENGTH_SHORT).show()
    }

    private fun onStateChanged(state: CallStateManager.State, attempt: Int) {
        runOnUiThread {
            binding.tvAttemptCount.text = "تعداد تلاش‌ها: $attempt"

            when (state) {
                CallStateManager.State.IDLE -> {
                    binding.tvStatus.text = getString(R.string.status_idle)
                    binding.tvStatus.setTextColor(getColor(R.color.text_primary))
                    binding.btnStart.isEnabled = true
                    binding.btnStop.isEnabled = false
                }
                CallStateManager.State.CALLING -> {
                    binding.tvStatus.text = getString(R.string.status_calling)
                    binding.tvStatus.setTextColor(getColor(R.color.primary))
                    binding.btnStart.isEnabled = false
                    binding.btnStop.isEnabled = true
                }
                CallStateManager.State.WAITING_NEXT -> {
                    binding.tvStatus.text = getString(R.string.status_waiting)
                    binding.tvStatus.setTextColor(getColor(R.color.warning))
                    binding.btnStart.isEnabled = false
                    binding.btnStop.isEnabled = true
                }
                CallStateManager.State.ANSWERED -> {
                    binding.tvStatus.text = getString(R.string.status_answered)
                    binding.tvStatus.setTextColor(getColor(R.color.accent))
                    binding.btnStart.isEnabled = true
                    binding.btnStop.isEnabled = false
                    binding.btnTestFlash.text = "خاموش کردن فلش"
                }
                CallStateManager.State.STOPPED -> {
                    binding.tvStatus.text = getString(R.string.status_stopped)
                    binding.tvStatus.setTextColor(getColor(R.color.danger))
                    binding.btnStart.isEnabled = true
                    binding.btnStop.isEnabled = false
                }
            }
        }
    }

    private fun updateAccessibilityWarningCard() {
        val enabled = isAccessibilityServiceEnabled(this, CallAccessibilityService::class.java)
        binding.cardAccessibilityWarning.visibility = if (enabled) View.GONE else View.VISIBLE
    }

    private fun hasAllPermissions(): Boolean {
        return requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun checkAndRequestPermissions() {
        if (!hasAllPermissions()) {
            requestPermissionLauncher.launch(requiredPermissions.toTypedArray())
        }
    }

    private fun isAccessibilityServiceEnabled(context: Context, serviceClass: Class<*>): Boolean {
        val expectedComponentName = "${context.packageName}/${serviceClass.name}"
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServices)
        while (colonSplitter.hasNext()) {
            val componentName = colonSplitter.next()
            if (componentName.equals(expectedComponentName, ignoreCase = true)) {
                return true
            }
        }
        return false
    }
}
