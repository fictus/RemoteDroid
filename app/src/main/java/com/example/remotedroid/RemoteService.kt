package com.example.remotedroid

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Keeps the web server alive for as long as the user wants sharing on.
 *
 * Android ends a MediaProjection session whenever the device locks (and the token can't be
 * reused), so the service is split in two lifetimes:
 *   - the SERVER (HTTP + signalling + WebRTC host), CPU/Wi-Fi locks and the foreground
 *     notification live until the user taps Stop;
 *   - the CAPTURE (MediaProjection + virtual display) comes and goes. When it ends we stay up,
 *     tell the viewers, and ask the user to approve a new capture session once the phone is
 *     unlocked.
 */
class RemoteService : Service() {

    companion object {
        const val ACTION_STOP = "com.example.remotedroid.STOP"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val PORT = 8088
        private const val CHANNEL_ID = "remote"
        private const val NOTIF_ID = 1
    }

    private val main = Handler(Looper.getMainLooper())

    private var projection: MediaProjection? = null
    private var host: WebRtcHost? = null
    private var server: RemoteServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var url = ""

    private val userPresent = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // Phone just got unlocked: if capture ended because of the lock, ask to resume.
            if (server != null && projection == null) requestResume()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        val data = intent?.let { IntentCompat.getParcelableExtra(it, EXTRA_DATA, Intent::class.java) }
        if (data == null) {
            // Nothing to capture with. If the server is already running just keep it.
            if (server == null) stopSelf()
            return START_NOT_STICKY
        }

        // On Android 14+ the foreground service must be running BEFORE getMediaProjection().
        showForeground(capturing = true, text = "Starting…")

        if (server == null && !startServer()) {
            stopSelf()
            return START_NOT_STICKY
        }
        startCapture(code, data)
        return START_NOT_STICKY
    }

    // ---------------------------------------------------------------- server lifetime

    private fun startServer(): Boolean {
        val ip = localIp() ?: "<phone-ip>"
        url = "http://$ip:$PORT"

        val h = WebRtcHost(this)
        val srv = RemoteServer(
            context = this,
            port = PORT,
            pin = { Pin.get(this) },
            host = h,
            onClientsChanged = { RemoteState.clients = it },
            onResumeRequested = { main.post { if (projection == null) requestResume() } },
        )
        try {
            srv.start(0, false)
        } catch (e: IOException) {
            RemoteState.error = "Could not start server on port $PORT: ${e.message}"
            h.dispose()
            return false
        }
        host = h
        server = srv

        // Keep the CPU and Wi-Fi radio awake while the screen is off / locked.
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "remotedroid:cpu").apply {
            setReferenceCounted(false)
            acquire()
        }
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        else WifiManager.WIFI_MODE_FULL_HIGH_PERF
        wifiLock = wm.createWifiLock(mode, "remotedroid:wifi").apply {
            setReferenceCounted(false)
            acquire()
        }

        ContextCompat.registerReceiver(
            this, userPresent, IntentFilter(Intent.ACTION_USER_PRESENT),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        RemoteState.error = null
        RemoteState.url = url
        RemoteState.clients = 0
        RemoteState.running = true
        return true
    }

    // ---------------------------------------------------------------- capture lifetime

    private fun startCapture(code: Int, data: Intent) {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = try {
            mpm.getMediaProjection(code, data)
        } catch (e: Exception) {
            RemoteState.error = "Screen capture was refused: ${e.message}"
            onCaptureEnded(null)
            return
        }
        projection?.let { old ->
            // Replacing a live session (user re-approved while one was running).
            try { old.stop() } catch (_: Exception) {}
        }
        projection = proj
        proj.registerCallback(object : MediaProjection.Callback() {
            // Called when the user stops sharing, the phone locks, or the system reclaims it.
            // The server MUST survive this.
            override fun onStop() = onCaptureEnded(proj)
        }, main)

        host?.attach(proj)
        RemoteState.capturing = true
        server?.setCapturing(true)
        showForeground(capturing = true, text = "Open $url in your browser")
    }

    private fun onCaptureEnded(proj: MediaProjection?) {
        if (proj != null && proj !== projection) return // stale callback from a replaced session
        projection = null
        host?.detach()
        RemoteState.capturing = false
        server?.setCapturing(false)
        if (server != null) {
            // Stay alive: demote to a plain foreground service that doesn't depend on a projection.
            showForeground(capturing = false, text = "Screen locked – unlock and tap to resume capture")
            requestResume()
        }
    }

    /** Ask the user to approve a new capture session. Works once the phone is unlocked. */
    private fun requestResume() {
        val i = Intent(this, ProjectionRequestActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            // An enabled accessibility service is allowed to start activities from the background.
            (InputAccessibilityService.instance ?: this).startActivity(i)
        } catch (_: Exception) {
            // Blocked: the notification (tap to resume) is the fallback.
        }
    }

    override fun onDestroy() {
        try { unregisterReceiver(userPresent) } catch (_: Exception) {}
        server?.stop()
        projection?.let { try { it.stop() } catch (_: Exception) {} }
        host?.dispose()
        wakeLock?.let { if (it.isHeld) it.release() }
        wifiLock?.let { if (it.isHeld) it.release() }
        server = null
        host = null
        projection = null
        RemoteState.running = false
        RemoteState.capturing = false
        RemoteState.clients = 0
        RemoteState.url = ""
        super.onDestroy()
    }

    // ---------------------------------------------------------------- notification

    private fun showForeground(capturing: Boolean, text: String) {
        val type = if (capturing || Build.VERSION.SDK_INT < 34) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        } else {
            // No projection right now; don't tie the service's lifetime to one.
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        }
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(capturing, text), type)
    }

    private fun buildNotification(capturing: Boolean, text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Remote access", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, if (capturing) MainActivity::class.java else ProjectionRequestActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, RemoteService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentTitle(if (capturing) "RemoteDroid is sharing this screen" else "RemoteDroid is waiting")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(0, "Stop", stop)
            .build()
    }

    private fun localIp(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
            ?.hostAddress
    } catch (_: Exception) {
        null
    }
}
