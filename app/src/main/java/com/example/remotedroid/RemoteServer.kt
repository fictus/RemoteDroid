package com.example.remotedroid

import android.content.Context
import android.os.Build
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Serves the web client (assets/index.html) over HTTP and the screen/control
 * channel over a WebSocket on the same port.
 *
 * Auth: the first WebSocket message must be {"type":"auth","pin":"123456"}.
 * Five wrong PINs from one IP locks that IP out for a minute.
 */
class RemoteServer(
    private val context: Context,
    port: Int,
    private val pin: String,
    private val lastFrame: () -> ByteArray?,
    private val onClientsChanged: (Int) -> Unit,
) : NanoWSD(port) {

    private val clients = CopyOnWriteArrayList<Socket>()
    private val sendExecutor = Executors.newSingleThreadExecutor()
    private val timer = Executors.newSingleThreadScheduledExecutor()
    private val busy = AtomicBoolean(false)

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

    /** Push a JPEG frame to all authenticated clients; drops frames if the network is slow. */
    fun broadcastFrame(frame: ByteArray) {
        if (clients.isEmpty()) return
        if (!busy.compareAndSet(false, true)) return
        sendExecutor.execute {
            try {
                for (c in clients) {
                    try {
                        c.send(frame)
                    } catch (_: IOException) {
                        if (clients.remove(c)) onClientsChanged(clients.size)
                    }
                }
            } finally {
                busy.set(false)
            }
        }
    }

    override fun stop() {
        for (c in clients) {
            try {
                c.close(NanoWSD.WebSocketFrame.CloseCode.GoingAway, "Server stopped", false)
            } catch (_: Exception) {
            }
        }
        clients.clear()
        super.stop()
        sendExecutor.shutdownNow()
        timer.shutdownNow()
    }

    private inner class Socket(handshake: NanoHTTPD.IHTTPSession) : NanoWSD.WebSocket(handshake) {
        @Volatile
        var authed = false
        private val ip = handshake.remoteIpAddress ?: "unknown"

        private fun reply(obj: JSONObject) {
            try {
                send(obj.toString())
            } catch (_: IOException) {
            }
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

            val svc = InputAccessibilityService.instance
            if (svc == null) {
                reply(
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
                reply(JSONObject().put("type", "auth").put("ok", false).put("msg", "Too many attempts. Try again in a minute."))
                closeQuietly("Locked out")
                return
            }
            val given = json.optString("pin")
            val ok = json.optString("type") == "auth" &&
                MessageDigest.isEqual(pin.toByteArray(), given.toByteArray())
            if (!ok) {
                f.count++
                if (f.count >= 5) {
                    f.lockedUntil = now + 60_000
                    f.count = 0
                }
                reply(JSONObject().put("type", "auth").put("ok", false).put("msg", "Wrong PIN"))
                closeQuietly("Bad PIN")
                return
            }
            failures.remove(ip)
            authed = true
            clients.add(this)
            onClientsChanged(clients.size)
            reply(JSONObject().put("type", "auth").put("ok", true).put("model", Build.MODEL))
            lastFrame()?.let {
                try {
                    send(it)
                } catch (_: IOException) {
                }
            }
        }

        override fun onPong(pong: NanoWSD.WebSocketFrame?) {}
        override fun onException(exception: IOException?) {}
    }
}
