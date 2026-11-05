package com.example.remotedroid

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject

/**
 * Injects touch gestures, text and global navigation actions.
 * Must be enabled by the user in Settings > Accessibility.
 */
class InputAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: InputAccessibilityService? = null
    }

    private val main = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    /** Called from the server thread; hops to the main thread. */
    fun handle(m: JSONObject) {
        main.post {
            try {
                when (m.optString("type")) {
                    "tap" -> {
                        val (x, y) = point(m, "x", "y")
                        gesture(x, y, x, y, 50)
                    }
                    "longpress" -> {
                        val (x, y) = point(m, "x", "y")
                        gesture(x, y, x, y, 700)
                    }
                    "swipe" -> {
                        val (x1, y1) = point(m, "x1", "y1")
                        val (x2, y2) = point(m, "x2", "y2")
                        gesture(x1, y1, x2, y2, m.optLong("dur", 300).coerceIn(50, 3000))
                    }
                    "key" -> globalKey(m.optString("key"))
                    "text" -> editText { current -> current + m.optString("text") }
                    "backspace" -> editText { current -> current.dropLast(1) }
                    "enter" -> enter()
                    "wake" -> wake()
                }
            } catch (_: Exception) {
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun screenSize(): Pair<Int, Int> {
        val dm = DisplayMetrics()
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(dm)
        return dm.widthPixels to dm.heightPixels
    }

    /** Client sends coordinates normalised to 0..1; convert to real pixels. */
    private fun point(m: JSONObject, kx: String, ky: String): Pair<Float, Float> {
        val (w, h) = screenSize()
        val x = (m.getDouble(kx).coerceIn(0.0, 1.0) * (w - 1)).toFloat()
        val y = (m.getDouble(ky).coerceIn(0.0, 1.0) * (h - 1)).toFloat()
        return x to y
    }

    private fun gesture(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long) {
        val path = Path().apply {
            moveTo(x1, y1)
            if (x1 != x2 || y1 != y2) lineTo(x2, y2)
        }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(1)))
            .build()
        dispatchGesture(g, null, null)
    }

    private fun globalKey(key: String) {
        when (key) {
            "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
            "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            "notifications" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            "quicksettings" -> performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
            "power" -> performGlobalAction(GLOBAL_ACTION_POWER_DIALOG)
            "lock" -> if (Build.VERSION.SDK_INT >= 28) performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
            "screenshot" -> if (Build.VERSION.SDK_INT >= 28) performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
        }
    }

    /** Edits the text of whichever input field currently has focus. */
    private fun editText(transform: (String) -> String) {
        val node = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return
        val current = if (node.isShowingHintText) "" else node.text?.toString() ?: ""
        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                transform(current)
            )
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun enter() {
        val node = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return
        if (Build.VERSION.SDK_INT >= 30) {
            val ok = node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            if (ok) return
        }
        editText { it + "\n" }
    }

    @Suppress("DEPRECATION")
    private fun wake() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "remotedroid:wake"
        ).acquire(3000)
    }
}
