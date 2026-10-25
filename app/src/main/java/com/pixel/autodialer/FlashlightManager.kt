package com.pixel.autodialer

import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import android.util.Log

object FlashlightManager {
    private const val TAG = "FlashlightManager"
    private var isTorchOn = false
    private var isStrobeRunning = false
    private val handler = Handler(Looper.getMainLooper())
    private var strobeRunnable: Runnable? = null

    /**
     * Turns on the flashlight continuously.
     */
    fun turnOnFlash(context: Context) {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return
        try {
            val cameraId = getCameraIdWithFlash(cameraManager) ?: return
            cameraManager.setTorchMode(cameraId, true)
            isTorchOn = true
            Log.d(TAG, "Flashlight turned ON")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to turn on flashlight", e)
        }
    }

    /**
     * Turns off the flashlight and stops any ongoing strobe effect.
     */
    fun turnOffFlash(context: Context) {
        stopStrobe()
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return
        try {
            val cameraId = getCameraIdWithFlash(cameraManager) ?: return
            cameraManager.setTorchMode(cameraId, false)
            isTorchOn = false
            Log.d(TAG, "Flashlight turned OFF")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to turn off flashlight", e)
        }
    }

    /**
     * Starts blinking/strobing the flashlight to grab immediate attention when call is answered.
     */
    fun startStrobeFlash(context: Context, intervalMs: Long = 300) {
        if (isStrobeRunning) return
        isStrobeRunning = true

        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return
        val cameraId = getCameraIdWithFlash(cameraManager) ?: return

        var state = false
        strobeRunnable = object : Runnable {
            override fun run() {
                if (!isStrobeRunning) return
                try {
                    state = !state
                    cameraManager.setTorchMode(cameraId, state)
                    isTorchOn = state
                } catch (e: Exception) {
                    Log.e(TAG, "Strobe error", e)
                }
                handler.postDelayed(this, intervalMs)
            }
        }
        handler.post(strobeRunnable!!)
    }

    fun stopStrobe() {
        isStrobeRunning = false
        strobeRunnable?.let { handler.removeCallbacks(it) }
        strobeRunnable = null
    }

    fun toggleFlash(context: Context) {
        if (isTorchOn || isStrobeRunning) {
            turnOffFlash(context)
        } else {
            turnOnFlash(context)
        }
    }

    fun isFlashOn(): Boolean = isTorchOn || isStrobeRunning

    private fun getCameraIdWithFlash(cameraManager: CameraManager): String? {
        try {
            for (id in cameraManager.cameraIdList) {
                val characteristics = cameraManager.getCameraCharacteristics(id)
                val hasFlash = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                if (hasFlash && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    return id
                }
            }
            // Fallback to any camera with flash
            for (id in cameraManager.cameraIdList) {
                val characteristics = cameraManager.getCameraCharacteristics(id)
                if (characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true) {
                    return id
                }
            }
        } catch (e: CameraAccessException) {
            Log.e(TAG, "CameraAccessException", e)
        }
        return null
    }
}
