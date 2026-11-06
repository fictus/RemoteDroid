package com.example.remotedroid

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.text.InputFilter
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var urlText: TextView
    private lateinit var pinEdit: EditText
    private lateinit var a11yButton: Button
    private lateinit var batteryButton: Button
    private lateinit var startStop: Button
    private lateinit var stopButton: Button
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
        root.addView(tv(14f).apply { text = "Login PIN (4–8 digits)" })
        pinEdit = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(8))
            textSize = 28f
            gravity = Gravity.CENTER
            setText(Pin.get(this@MainActivity))
        }
        root.addView(pinEdit, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val pinRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val half = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        pinRow.addView(Button(this).apply {
            text = "Save PIN"
            setOnClickListener { savePin() }
        }, half)
        pinRow.addView(Button(this).apply {
            text = "Use device default"
            setOnClickListener {
                Pin.reset(this@MainActivity)
                pinEdit.setText(Pin.get(this@MainActivity))
                Toast.makeText(this@MainActivity, "PIN reset to device default", Toast.LENGTH_SHORT).show()
            }
        }, half)
        root.addView(pinRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        a11yButton = Button(this).apply {
            text = "1. Enable remote control (Accessibility)"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        batteryButton = Button(this).apply {
            text = "2. Allow running in background (battery)"
            setOnClickListener { requestBatteryExemption() }
        }
        startStop = Button(this).apply {
            setOnClickListener { startOrResume() }
        }
        stopButton = Button(this).apply {
            text = "Stop sharing"
            setOnClickListener {
                startService(Intent(this@MainActivity, RemoteService::class.java).setAction(RemoteService.ACTION_STOP))
            }
        }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        root.addView(a11yButton, lp)
        root.addView(batteryButton, lp)
        root.addView(startStop, lp)
        root.addView(stopButton, lp)
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

    private fun savePin() {
        val v = pinEdit.text.toString().trim()
        if (!Pin.isValid(v)) {
            pinEdit.error = "Use 4 to 8 digits"
            return
        }
        Pin.set(this, v)
        Toast.makeText(this, "PIN saved (applies to new logins)", Toast.LENGTH_SHORT).show()
    }

    private fun startOrResume() {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun batteryExempt(): Boolean {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    @android.annotation.SuppressLint("BatteryLife")
    private fun requestBatteryExemption() {
        startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
        )
    }

    private fun refresh() {
        val a11yOn = InputAccessibilityService.instance != null
        a11yButton.text = if (a11yOn) "Remote control: ON ✓" else "1. Enable remote control (Accessibility)"
        a11yButton.isEnabled = !a11yOn

        val exempt = batteryExempt()
        batteryButton.text = if (exempt) "Background running: ALLOWED ✓" else "2. Allow running in background (battery)"
        batteryButton.isEnabled = !exempt

        if (RemoteState.running) {
            stopButton.visibility = android.view.View.VISIBLE
            urlText.text = RemoteState.url
            if (RemoteState.capturing) {
                startStop.visibility = android.view.View.GONE
                status.text = "Sharing • ${RemoteState.clients} viewer(s) connected\nOpen this address on your computer (same Wi-Fi):"
            } else {
                // Server is still up; Android ended capture (screen lock). One tap to resume.
                startStop.visibility = android.view.View.VISIBLE
                startStop.text = "Resume screen capture"
                status.text = "Server running • ${RemoteState.clients} viewer(s)\nScreen capture paused (Android stops it when the phone locks)."
            }
        } else {
            stopButton.visibility = android.view.View.GONE
            startStop.visibility = android.view.View.VISIBLE
            startStop.text = "3. Start sharing"
            status.text = RemoteState.error ?: "Not sharing"
            urlText.text = ""
        }
    }
}
