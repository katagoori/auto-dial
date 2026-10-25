package com.pixel.autodialer

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.regex.Pattern

class CallAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "CallAccessibility"
        var isServiceConnected = false
            private set

        // Pattern matching call duration timer like 00:01, 0:05, 1:23:45
        private val DURATION_PATTERN = Pattern.compile("^\\d{1,2}:\\d{2}(:\\d{2})?$")
    }

    private val handler = Handler(Looper.getMainLooper())
    private var hangupRunnable: Runnable? = null
    private var isCallInProgress = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        isServiceConnected = true
        Log.d(TAG, "CallAccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // Only monitor when our redialer is actively running
        if (!CallStateManager.isRunning) {
            cancelHangupTimeout()
            isCallInProgress = false
            return
        }

        val packageName = event.packageName?.toString() ?: return

        // Check if event is from phone dialers (Google Phone or standard dialers)
        if (packageName.contains("dialer") || packageName.contains("telecom") || packageName.contains("phone")) {
            val rootNode = rootInActiveWindow ?: return
            inspectCallScreen(rootNode)
        }
    }

    /**
     * Inspects the dialer UI nodes for:
     * 1. Call timer (signifies the call is answered by the remote party)
     * 2. Starting the hang-up watchdog timer (if call just started)
     */
    private fun inspectCallScreen(rootNode: AccessibilityNodeInfo) {
        // 1. Check if the call timer has appeared (remote party picked up)
        if (detectCallTimer(rootNode)) {
            Log.d(TAG, ">>> CALL ANSWERED! Timer detected <<<")
            cancelHangupTimeout()
            isCallInProgress = false

            // Mark answered in state manager
            CallStateManager.onCallAnswered()

            // Turn on the camera flashlight (strobe effect to grab attention)
            FlashlightManager.startStrobeFlash(applicationContext, 250)
            return
        }

        // 2. If call is active and ringing, start watchdog timeout if not already started
        if (!isCallInProgress && CallStateManager.currentState == CallStateManager.State.CALLING) {
            isCallInProgress = true
            scheduleHangupWatchdog(CallStateManager.callTimeoutSeconds)
        }
    }

    /**
     * Recursively searches for text matching duration timer format (e.g. 00:01).
     */
    private fun detectCallTimer(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false

        val text = node.text?.toString()?.trim()
        if (!text.isNullOrEmpty() && DURATION_PATTERN.matcher(text).matches()) {
            return true
        }

        val contentDesc = node.contentDescription?.toString()?.trim()
        if (!contentDesc.isNullOrEmpty() && DURATION_PATTERN.matcher(contentDesc).matches()) {
            return true
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (detectCallTimer(child)) {
                return true
            }
        }
        return false
    }

    /**
     * Schedules a timer to hang up if the call is not answered within timeoutSeconds.
     */
    private fun scheduleHangupWatchdog(timeoutSeconds: Int) {
        cancelHangupTimeout()
        Log.d(TAG, "Watchdog scheduled for $timeoutSeconds seconds")

        hangupRunnable = Runnable {
            if (CallStateManager.isRunning && CallStateManager.currentState != CallStateManager.State.ANSWERED) {
                Log.d(TAG, "Timeout of $timeoutSeconds seconds reached without answer. Hanging up...")
                hangUpActiveCall()
            }
        }
        handler.postDelayed(hangupRunnable!!, timeoutSeconds * 1000L)
    }

    private fun cancelHangupTimeout() {
        hangupRunnable?.let { handler.removeCallbacks(it) }
        hangupRunnable = null
    }

    /**
     * Finds and clicks the "End call" button in the Google Phone UI.
     */
    fun hangUpActiveCall() {
        val rootNode = rootInActiveWindow ?: return
        val clicked = findAndClickEndCallButton(rootNode)
        if (!clicked) {
            Log.w(TAG, "Could not locate End Call button in current UI window")
        }
    }

    private fun findAndClickEndCallButton(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false

        val viewId = node.viewIdResourceName?.lowercase() ?: ""
        val contentDesc = node.contentDescription?.toString()?.lowercase() ?: ""
        val text = node.text?.toString()?.lowercase() ?: ""

        val isEndCall = viewId.contains("incall_end_call") ||
                viewId.contains("end_call") ||
                contentDesc.contains("end call") ||
                contentDesc.contains("قطع تماس") ||
                contentDesc.contains("hang up") ||
                text.contains("end call") ||
                text.contains("قطع تماس")

        if (isEndCall && (node.isClickable || node.parent?.isClickable == true)) {
            Log.d(TAG, "Found End Call button: id=$viewId, desc=$contentDesc. Clicking...")
            if (node.isClickable) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            } else {
                node.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            return true
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (findAndClickEndCallButton(child)) {
                return true
            }
        }
        return false
    }

    override fun onInterrupt() {
        Log.w(TAG, "CallAccessibilityService interrupted")
        cancelHangupTimeout()
        isServiceConnected = false
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelHangupTimeout()
        isServiceConnected = false
    }
}
