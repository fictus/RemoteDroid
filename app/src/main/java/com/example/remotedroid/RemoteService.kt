package com.example.remotedroid

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom

class RemoteService : Service() {

    companion object {
        const val ACTION_STOP = "com.example.remotedroid.STOP"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val PORT = 8088
        private const val CHANNEL_ID = "remote"
        private const val NOTIF_ID = 1
    }

    private var projection: MediaProjection? = null
    private var capturer: ScreenCapturer? = null
    private var server: RemoteServer? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (server != null) return START_NOT_STICKY

        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        val data = intent?.let { IntentCompat.getParcelableExtra(it, EXTRA_DATA, Intent::class.java) }
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        // On Android 14+ the foreground service must be running BEFORE getMediaProjection().
        ServiceCompat.startForeground(
            this, NOTIF_ID, buildNotification("Starting…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = mpm.getMediaProjection(code, data)
        projection = proj
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopSelf()
            }
        }, Handler(Looper.getMainLooper()))

        val pin = "%06d".format(SecureRandom().nextInt(1_000_000))
        val ip = localIp() ?: "<phone-ip>"
        val url = "http://$ip:$PORT"

        lateinit var cap: ScreenCapturer
        val srv = RemoteServer(
            context = this,
            port = PORT,
            pin = pin,
            lastFrame = { cap.lastFrame },
            onClientsChanged = { RemoteState.clients = it },
        )
        cap = ScreenCapturer(
            context = this,
            projection = proj,
            hasClients = { srv.clientCount > 0 },
            onFrame = { srv.broadcastFrame(it) },
        )

        try {
            srv.start(0, false)
        } catch (e: IOException) {
            RemoteState.error = "Could not start server on port $PORT: ${e.message}"
            stopSelf()
            return START_NOT_STICKY
        }

        server = srv
        capturer = cap
        cap.start()

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "remotedroid:cpu").apply { acquire() }

        RemoteState.error = null
        RemoteState.pin = pin
        RemoteState.url = url
        RemoteState.clients = 0
        RemoteState.running = true

        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification("Open $url in your browser"))
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        capturer?.stop()
        server?.stop()
        projection?.stop()
        wakeLock?.let { if (it.isHeld) it.release() }
        capturer = null
        server = null
        projection = null
        RemoteState.running = false
        RemoteState.clients = 0
        RemoteState.pin = ""
        RemoteState.url = ""
        super.onDestroy()
    }

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Remote access", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, RemoteService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentTitle("RemoteDroid is sharing this screen")
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
