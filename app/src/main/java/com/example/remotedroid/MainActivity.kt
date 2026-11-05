package com.example.remotedroid

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var urlText: TextView
    private lateinit var pinText: TextView
    private lateinit var a11yButton: Button
    private lateinit var startStop: Button
    private val ui = Handler(Looper.getMainLooper())

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            val data = r.data
            if (r.resultCode == RESULT_OK && data != null) {
                val i = Intent(this, RemoteService::class.java)
                    .putExtra(RemoteService.EXTRA_CODE, r.resultCode)
                    .putExtra(RemoteService.EXTRA_DATA, data)
                ContextCompat.startForegroundService(this, i)
            }
        }

    private val notifLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            ui.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        fun tv(size: Float, bold: Boolean = false) = TextView(this).apply {
            textSize = size
            gravity = Gravity.CENTER
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, pad / 2, 0, pad / 2)
        }

        root.addView(tv(26f, true).apply { text = "RemoteDroid" })
        status = tv(16f).also { root.addView(it) }
        urlText = tv(20f, true).also { root.addView(it) }
        pinText = tv(32f, true).also { root.addView(it) }

        a11yButton = Button(this).apply {
            text = "1. Enable remote control (Accessibility)"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        startStop = Button(this).apply {
            setOnClickListener { toggle() }
        }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        root.addView(a11yButton, lp)
        root.addView(startStop, lp)
        setContentView(root)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onResume() {
        super.onResume()
        ui.post(tick)
    }

    override fun onPause() {
        ui.removeCallbacks(tick)
        super.onPause()
    }

    private fun toggle() {
        if (RemoteState.running) {
            startService(Intent(this, RemoteService::class.java).setAction(RemoteService.ACTION_STOP))
        } else {
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projectionLauncher.launch(mpm.createScreenCaptureIntent())
        }
    }

    private fun refresh() {
        val a11yOn = InputAccessibilityService.instance != null
        a11yButton.text = if (a11yOn) "Remote control: ON ✓" else "1. Enable remote control (Accessibility)"
        a11yButton.isEnabled = !a11yOn
        if (RemoteState.running) {
            startStop.text = "Stop sharing"
            status.text = "Sharing • ${RemoteState.clients} viewer(s) connected\nOpen this address on your computer (same Wi-Fi):"
            urlText.text = RemoteState.url
            pinText.text = "PIN  ${RemoteState.pin}"
        } else {
            startStop.text = "2. Start sharing"
            status.text = RemoteState.error ?: "Not sharing"
            urlText.text = ""
            pinText.text = ""
        }
    }
}
