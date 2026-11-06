package com.example.remotedroid

import android.content.Context
import android.os.Build
import android.os.PowerManager
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Serves the web client (assets/index.html) over HTTP and WebRTC signalling over a
 * WebSocket on the same port. Video and input travel over WebRTC ([WebRtcHost]).
 *
 * Auth: the first WebSocket message must be {"type":"auth","pin":"123456"}.
 * Five wrong PINs from one IP locks that IP out for a minute.
 *
 * This server is deliberately independent of the screen-capture session: it keeps
 * running when Android ends the MediaProjection (e.g. on screen lock).
 */
class RemoteServer(
    private val context: Context,
    port: Int,
    private val pin: () -> String,
    private val host: WebRtcHost,
    private val onClientsChanged: (Int) -> Unit,
    private val onResumeRequested: () -> Unit,
) : NanoWSD(port) {

    private val clients = CopyOnWriteArrayList<Socket>()
    private val timer = Executors.newSingleThreadScheduledExecutor()

    @Volatile
    private var capturing = false

    private class Failures(var count: Int = 0, var lockedUntil: Long = 0)
    private val failures = ConcurrentHashMap<String, Failures>()

    private val indexHtml: String by lazy {
        context.assets.open("index.html").bufferedReader().use { it.readText() }
    }

    val clientCount: Int get() = clients.size

    override fun serve(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val upgrade = session.headers["upgrade"]
        if (upgrade != null && upgrade.equals("websocket", ignoreCase = true)) {
            return super.serve(session)
        }
        return if (session.uri == "/" || session.uri == "/index.html") {
            NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK, "text/html; charset=utf-8", indexHtml
            )
        } else {
            NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.NOT_FOUND, "text/plain", "Not found"
            )
        }
    }

    override fun openWebSocket(handshake: NanoHTTPD.IHTTPSession): NanoWSD.WebSocket =
        Socket(handshake)

    /** Tell every viewer whether the screen is currently being captured. */
    fun setCapturing(value: Boolean) {
        capturing = value
        val msg = statusJson()
        for (c in clients) c.sendJson(msg)
    }

    private fun statusJson() = JSONObject().put("type", "status").put("capturing", capturing)

    override fun stop() {
        for (c in clients) {
            c.shutdown()
            try {
                c.close(NanoWSD.WebSocketFrame.CloseCode.GoingAway, "Server stopped", false)
            } catch (_: Exception) {
            }
        }
        clients.clear()
        super.stop()
        timer.shutdownNow()
    }

    private inner class Socket(handshake: NanoHTTPD.IHTTPSession) : NanoWSD.WebSocket(handshake) {
        @Volatile
        var authed = false
        private val ip = handshake.remoteIpAddress ?: "unknown"
        private var peer: WebRtcHost.Peer? = null

        fun sendJson(obj: JSONObject) {
            try {
                send(obj.toString())
            } catch (_: Exception) {
            }
        }

        fun shutdown() {
            peer?.close()
            peer = null
        }

        private fun closeQuietly(reason: String) {
            try {
                close(NanoWSD.WebSocketFrame.CloseCode.NormalClosure, reason, false)
            } catch (_: Exception) {
            }
        }

        override fun onOpen() {
            // Must authenticate within 10 seconds.
            timer.schedule({ if (!authed) closeQuietly("Auth timeout") }, 10, TimeUnit.SECONDS)
        }

        override fun onClose(
            code: NanoWSD.WebSocketFrame.CloseCode?, reason: String?, initiatedByRemote: Boolean
        ) {
            shutdown()
            if (clients.remove(this)) onClientsChanged(clients.size)
        }

        override fun onMessage(message: NanoWSD.WebSocketFrame) {
            val json = try {
                JSONObject(message.textPayload)
            } catch (_: Exception) {
                return
            }

            if (!authed) {
                authenticate(json)
                return
            }

            when (json.optString("type")) {
                "offer" -> {
                    peer?.close()
                    peer = host.newPeer(signal = ::sendJson, onControl = ::control)
                    peer?.handleOffer(json.optString("sdp"))
                }
                "candidate" -> peer?.addCandidate(
                    json.optString("sdpMid").ifEmpty { null },
                    json.optInt("sdpMLineIndex"),
                    json.optString("candidate")
                )
                else -> control(json)
            }
        }

        /** Input / commands from the browser (data channel, or WebSocket as a fallback). */
        private fun control(json: JSONObject) {
            when (json.optString("type")) {
                "resume" -> { onResumeRequested(); return }
                "refresh" -> { host.requestFrame(); return }
            }
            val svc = InputAccessibilityService.instance
            if (svc == null) {
                sendJson(
                    JSONObject().put("type", "error")
                        .put("msg", "Accessibility service is off on the phone, so control is disabled.")
                )
                return
            }
            svc.handle(json)
        }

        private fun authenticate(json: JSONObject) {
            val now = System.currentTimeMillis()
            val f = failures.getOrPut(ip) { Failures() }
            if (f.lockedUntil > now) {
                sendJson(JSONObject().put("type", "auth").put("ok", false).put("msg", "Too many attempts. Try again in a minute."))
                closeQuietly("Locked out")
                return
            }
            val given = json.optString("pin")
            val ok = json.optString("type") == "auth" &&
                MessageDigest.isEqual(pin().toByteArray(), given.toByteArray())
            if (!ok) {
                f.count++
                if (f.count >= 5) {
                    f.lockedUntil = now + 60_000
                    f.count = 0
                }
                sendJson(JSONObject().put("type", "auth").put("ok", false).put("msg", "Wrong PIN"))
                closeQuietly("Bad PIN")
                return
            }
            failures.remove(ip)
            authed = true
            clients.add(this)
            onClientsChanged(clients.size)
            sendJson(JSONObject().put("type", "auth").put("ok", true).put("model", Build.MODEL))
            sendJson(statusJson())

            // Someone is connecting: if the phone's screen is off, turn it on.
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isInteractive) {
                InputAccessibilityService.instance?.handle(JSONObject().put("type", "wake"))
            }
        }

        override fun onPong(pong: NanoWSD.WebSocketFrame?) {}
        override fun onException(exception: IOException?) {}
    }
}
