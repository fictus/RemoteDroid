package com.example.remotedroid

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.WindowManager
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min

/**
 * Mirrors the screen into an ImageReader and emits JPEG frames.
 * Handles rotation by resizing the existing virtual display
 * (Android 14+ forbids creating a second one from the same projection).
 */
class ScreenCapturer(
    private val context: Context,
    private val projection: MediaProjection,
    private val hasClients: () -> Boolean,
    private val onFrame: (ByteArray) -> Unit,
) {
    private val maxLongSide = 1280f
    private val jpegQuality = 55
    private val activeIntervalMs = 66L   // ~15 fps while someone is watching
    private val idleIntervalMs = 1000L   // keep a fresh "last frame" for new clients

    private val thread = HandlerThread("screen-capture").apply { start() }
    private val handler = Handler(thread.looper)
    private val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var width = 0
    private var height = 0
    private var bitmap: Bitmap? = null
    private var lastSent = 0L
    private val out = ByteArrayOutputStream(256 * 1024)

    @Volatile
    var lastFrame: ByteArray? = null
        private set

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            handler.post { setup() }
        }
    }

    fun start() {
        handler.post { setup() }
        displayManager.registerDisplayListener(displayListener, handler)
    }

    @Suppress("DEPRECATION")
    private fun setup() {
        val dm = DisplayMetrics()
        (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
            .defaultDisplay.getRealMetrics(dm)
        val scale = min(1f, maxLongSide / max(dm.widthPixels, dm.heightPixels))
        val nw = (dm.widthPixels * scale).toInt().coerceAtLeast(2)
        val nh = (dm.heightPixels * scale).toInt().coerceAtLeast(2)
        if (display != null && nw == width && nh == height) return
        width = nw
        height = nh

        val old = reader
        val r = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        r.setOnImageAvailableListener({ handleImage(it) }, handler)
        reader = r

        val vd = display
        if (vd == null) {
            display = projection.createVirtualDisplay(
                "RemoteDroid", width, height, dm.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                r.surface, null, handler
            )
        } else {
            vd.resize(width, height, dm.densityDpi)
            vd.surface = r.surface
        }
        old?.close()
        bitmap?.recycle()
        bitmap = null
    }

    private fun handleImage(rd: ImageReader) {
        val image = try {
            rd.acquireLatestImage()
        } catch (_: Exception) {
            null
        } ?: return
        try {
            val now = SystemClock.elapsedRealtime()
            val interval = if (hasClients()) activeIntervalMs else idleIntervalMs
            if (now - lastSent < interval) return

            val plane = image.planes[0]
            val bufWidth = plane.rowStride / plane.pixelStride
            var bmp = bitmap
            if (bmp == null || bmp.width != bufWidth || bmp.height != image.height) {
                bmp?.recycle()
                bmp = Bitmap.createBitmap(bufWidth, image.height, Bitmap.Config.ARGB_8888)
                bitmap = bmp
            }
            bmp.copyPixelsFromBuffer(plane.buffer)

            out.reset()
            if (bufWidth == image.width) {
                bmp.compress(Bitmap.CompressFormat.JPEG, jpegQuality, out)
            } else {
                // Row padding: crop it off before compressing.
                val cropped = Bitmap.createBitmap(bmp, 0, 0, image.width, image.height)
                cropped.compress(Bitmap.CompressFormat.JPEG, jpegQuality, out)
                cropped.recycle()
            }
            val bytes = out.toByteArray()
            lastFrame = bytes
            lastSent = now
            onFrame(bytes)
        } finally {
            image.close()
        }
    }

    fun stop() {
        displayManager.unregisterDisplayListener(displayListener)
        handler.post {
            display?.release()
            display = null
            reader?.close()
            reader = null
            bitmap?.recycle()
            bitmap = null
            thread.quitSafely()
        }
    }
}
