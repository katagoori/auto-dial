package com.pixel.autodialer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat

class RedialService : Service() {

    companion object {
        private const val TAG = "RedialService"
        private const val CHANNEL_ID = "redial_service_channel"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.pixel.autodialer.ACTION_START"
        const val ACTION_STOP = "com.pixel.autodialer.ACTION_STOP"
        const val EXTRA_PHONE_NUMBER = "extra_phone_number"
        const val EXTRA_TIMEOUT = "extra_timeout"
        const val EXTRA_DELAY = "extra_delay"
    }

    private val handler = Handler(Looper.getMainLooper())
    private var nextCallRunnable: Runnable? = null
    private lateinit var telephonyManager: TelephonyManager

    // Modern API 31+ Callback
    private var telephonyCallback: TelephonyCallback? = null

    // Legacy PhoneStateListener
    private var legacyPhoneStateListener: PhoneStateListener? = null

    override fun onCreate() {
        super.onCreate()
        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        createNotificationChannel()
        registerCallStateListener()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action

        if (action == ACTION_STOP) {
            stopRedialing()
            return START_NOT_STICKY
        }

        if (action == ACTION_START) {
            val phone = intent.getStringExtra(EXTRA_PHONE_NUMBER) ?: ""
            val timeout = intent.getIntExtra(EXTRA_TIMEOUT, 20)
            val delay = intent.getIntExtra(EXTRA_DELAY, 3)

            CallStateManager.targetPhoneNumber = phone
            CallStateManager.callTimeoutSeconds = timeout
            CallStateManager.redialDelaySeconds = delay
            CallStateManager.isRunning = true
            CallStateManager.resetAttempts()

            startForeground(NOTIFICATION_ID, buildNotification("شروع تماس با $phone"))
            startDialCycle()
        }

        return START_STICKY
    }

    private fun startDialCycle() {
        if (!CallStateManager.isRunning) return

        CallStateManager.incrementAttempt()
        CallStateManager.updateState(CallStateManager.State.CALLING)

        updateNotification("تلاش #${CallStateManager.attemptCount} - در حال شماره‌گیری...")

        try {
            val callIntent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:${CallStateManager.targetPhoneNumber}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(callIntent)
            Log.d(TAG, "Placed call to: ${CallStateManager.targetPhoneNumber} (Attempt ${CallStateManager.attemptCount})")
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException: CALL_PHONE permission missing!", e)
            stopRedialing()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start call intent", e)
            scheduleNextCall()
        }
    }

    private fun scheduleNextCall() {
        if (!CallStateManager.isRunning || CallStateManager.currentState == CallStateManager.State.ANSWERED) {
            return
        }

        CallStateManager.updateState(CallStateManager.State.WAITING_NEXT)
        updateNotification("تماس قطع شد. تلاش بعدی در ${CallStateManager.redialDelaySeconds} ثانیه...")

        nextCallRunnable?.let { handler.removeCallbacks(it) }
        nextCallRunnable = Runnable {
            if (CallStateManager.isRunning && CallStateManager.currentState != CallStateManager.State.ANSWERED) {
                startDialCycle()
            }
        }
        handler.postDelayed(nextCallRunnable!!, CallStateManager.redialDelaySeconds * 1000L)
    }

    private fun stopRedialing() {
        CallStateManager.stop()
        FlashlightManager.stopStrobe()
        nextCallRunnable?.let { handler.removeCallbacks(it) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun registerCallStateListener() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) {
                        handleCallState(state)
                    }
                }
                telephonyManager.registerTelephonyCallback(mainExecutor, callback)
                telephonyCallback = callback
            } else {
                @Suppress("DEPRECATION")
                val listener = object : PhoneStateListener() {
                    @Deprecated("Deprecated in Java")
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                        handleCallState(state)
                    }
                }
                @Suppress("DEPRECATION")
                telephonyManager.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
                legacyPhoneStateListener = listener
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing READ_PHONE_STATE permission", e)
        }
    }

    private fun handleCallState(state: Int) {
        when (state) {
            TelephonyManager.CALL_STATE_IDLE -> {
                Log.d(TAG, "Telephony state: IDLE")
                if (CallStateManager.isRunning && CallStateManager.currentState != CallStateManager.State.ANSWERED) {
                    // Call was disconnected (either remote hung up, carrier dropped after 20s, or accessibility clicked end call)
                    scheduleNextCall()
                } else if (CallStateManager.currentState == CallStateManager.State.ANSWERED) {
                    Log.d(TAG, "Call ended after being answered.")
                }
            }
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                Log.d(TAG, "Telephony state: OFFHOOK (Call placed/active)")
            }
            TelephonyManager.CALL_STATE_RINGING -> {
                Log.d(TAG, "Telephony state: RINGING")
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "نمایش وضعیت تماس خودکار و امکان توقف سریع"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val stopIntent = Intent(this, RedialService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val mainActivityIntent = Intent(this, MainActivity::class.java)
        val mainPendingIntent = PendingIntent.getActivity(
            this,
            0,
            mainActivityIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_call)
            .setContentIntent(mainPendingIntent)
            .addAction(R.drawable.ic_stop, getString(R.string.notification_stop), stopPendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onDestroy() {
        super.onDestroy()
        nextCallRunnable?.let { handler.removeCallbacks(it) }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                telephonyCallback?.let { telephonyManager.unregisterTelephonyCallback(it) }
            } else {
                @Suppress("DEPRECATION")
                legacyPhoneStateListener?.let {
                    telephonyManager.listen(it, PhoneStateListener.LISTEN_NONE)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering listener", e)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
