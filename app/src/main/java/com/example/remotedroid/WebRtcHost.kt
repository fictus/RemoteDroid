package com.example.remotedroid

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.view.Surface
import android.view.WindowManager
import org.json.JSONObject
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpParameters
import org.webrtc.RtpSender
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSink
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import kotlin.math.max
import kotlin.math.min

/**
 * Owns everything WebRTC:
 *  - one screen VideoSource/VideoTrack that outlives individual MediaProjection sessions
 *    (so connected browsers resume automatically after the projection is re-granted),
 *  - the virtual display that feeds it,
 *  - one PeerConnection ("Peer") per authenticated browser.
 *
 * Signalling (SDP / ICE) is carried over the WebSocket in [RemoteServer]; input events
 * arrive on a WebRTC data channel named "ctl".
 */
class WebRtcHost(private val context: Context) {

    companion object {
        private const val MAX_LONG_SIDE = 1280
        private const val MAX_BITRATE_BPS = 8_000_000
        private const val MIN_BITRATE_BPS = 2_000_000
        private const val MAX_FPS = 60
    }

    private val egl = EglBase.create()
    private val factory: PeerConnectionFactory
    private val videoSource: VideoSource
    private val track: VideoTrack
    private val helper: SurfaceTextureHelper
    private val surface: Surface

    private val thread = HandlerThread("rd-capture").apply { start() }
    private val handler = Handler(thread.looper)
    private val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

    // Touched only on [handler].
    private var display: VirtualDisplay? = null
    private var width = 0
    private var height = 0
    private var dpi = 0
    private var nudgeFlip = false

    private val peers = java.util.concurrent.CopyOnWriteArrayList<Peer>()

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            handler.post { resizeIfNeeded() }
        }
    }

    init {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                .createInitializationOptions()
        )
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .createPeerConnectionFactory()

        videoSource = // false = motion-first. Screencast mode drops frames to keep text sharp, which makes
        // swipes and animations look slow.
        factory.createVideoSource(/* isScreencast = */ false)
        helper = SurfaceTextureHelper.create("rd-capture-tex", egl.eglBaseContext)
        surface = Surface(helper.surfaceTexture)
        helper.startListening(VideoSink { frame -> videoSource.capturerObserver.onFrameCaptured(frame) })
        videoSource.capturerObserver.onCapturerStarted(true)
        track = factory.createVideoTrack("screen0", videoSource)

        displayManager.registerDisplayListener(displayListener, handler)
    }

    // ------------------------------------------------------------------ capture

    /** Start (or restart) feeding the video track from a freshly granted projection. */
    fun attach(projection: MediaProjection) {
        handler.post {
            display?.release()
            display = null
            computeSize()
            helper.setTextureSize(width, height)
            // One virtual display per projection (Android 14+); rotation uses resize().
            display = projection.createVirtualDisplay(
                "RemoteDroid", width, height, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface, null, handler
            )
        }
    }

    /** The projection ended (e.g. the phone was locked). Peers stay connected, video freezes. */
    fun detach() {
        handler.post {
            display?.release()
            display = null
        }
    }

    /**
     * A screen that isn't changing produces no frames, so a browser that connects to a static
     * screen would see nothing. Poking the virtual display forces it to render a fresh frame.
     */
    fun requestFrame() {
        handler.post {
            val d = display ?: return@post
            nudgeFlip = !nudgeFlip
            try {
                d.resize(width, height, if (nudgeFlip) dpi + 1 else dpi)
            } catch (_: Exception) {
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun computeSize() {
        val dm = DisplayMetrics()
        (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
            .defaultDisplay.getRealMetrics(dm)
        val scale = min(1f, MAX_LONG_SIDE.toFloat() / max(dm.widthPixels, dm.heightPixels))
        width = ((dm.widthPixels * scale).toInt() and 1.inv()).coerceAtLeast(2)
        height = ((dm.heightPixels * scale).toInt() and 1.inv()).coerceAtLeast(2)
        dpi = dm.densityDpi
    }

    private fun resizeIfNeeded() {
        val d = display ?: return
        val ow = width
        val oh = height
        computeSize()
        if (ow == width && oh == height) return
        helper.setTextureSize(width, height)
        try {
            d.resize(width, height, dpi)
        } catch (_: Exception) {
        }
    }

    // ------------------------------------------------------------------ peers

    fun newPeer(
        signal: (JSONObject) -> Unit,
        onControl: (JSONObject) -> Unit,
    ): Peer = Peer(signal, onControl).also { peers.add(it) }

    inner class Peer(
        private val signal: (JSONObject) -> Unit,
        private val onControl: (JSONObject) -> Unit,
    ) {
        private val pendingCandidates = ArrayList<IceCandidate>()
        private var remoteSet = false
        private var closed = false
        private var sender: RtpSender? = null

        private val pc: PeerConnection

        init {
            val cfg = PeerConnection.RTCConfiguration(emptyList()).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            }
            pc = factory.createPeerConnection(cfg, object : PeerConnection.Observer {
                override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
                override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) {
                    if (s == PeerConnection.IceConnectionState.CONNECTED ||
                        s == PeerConnection.IceConnectionState.COMPLETED
                    ) requestFrame()
                }
                override fun onIceConnectionReceivingChange(r: Boolean) {}
                override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {}
                override fun onIceCandidate(c: IceCandidate) {
                    signal(
                        JSONObject().put("type", "candidate")
                            .put("candidate", c.sdp)
                            .put("sdpMid", c.sdpMid)
                            .put("sdpMLineIndex", c.sdpMLineIndex)
                    )
                }
                override fun onIceCandidatesRemoved(c: Array<out IceCandidate>?) {}
                override fun onAddStream(s: MediaStream?) {}
                override fun onRemoveStream(s: MediaStream?) {}
                override fun onDataChannel(dc: DataChannel) {
                    dc.registerObserver(object : DataChannel.Observer {
                        override fun onBufferedAmountChange(previous: Long) {}
                        override fun onStateChange() {}
                        override fun onMessage(buffer: DataChannel.Buffer) {
                            if (buffer.binary) return
                            val bytes = ByteArray(buffer.data.remaining())
                            buffer.data.get(bytes)
                            try {
                                onControl(JSONObject(String(bytes, Charsets.UTF_8)))
                            } catch (_: Exception) {
                            }
                        }
                    })
                }
                override fun onRenegotiationNeeded() {}
            }) ?: throw IllegalStateException("createPeerConnection failed")
        }

        /** The browser sends an offer (recvonly video + the "ctl" data channel); we answer. */
        fun handleOffer(sdp: String) {
            if (closed) return
            pc.setRemoteDescription(object : Sdp() {
                override fun onSetSuccess() {
                    if (closed) return
                    remoteSet = true
                    sender = pc.addTrack(track, listOf("screen"))
                    pendingCandidates.forEach { pc.addIceCandidate(it) }
                    pendingCandidates.clear()
                    pc.createAnswer(object : Sdp() {
                        override fun onCreateSuccess(desc: SessionDescription) {
                            pc.setLocalDescription(object : Sdp() {
                                override fun onSetSuccess() {
                                    tune()
                                    signal(
                                        JSONObject().put("type", "answer").put("sdp", desc.description)
                                    )
                                }
                            }, desc)
                        }
                    }, MediaConstraints())
                }
            }, SessionDescription(SessionDescription.Type.OFFER, sdp))
        }

        fun addCandidate(mid: String?, index: Int, sdp: String) {
            if (closed) return
            val c = IceCandidate(mid, index, sdp)
            if (remoteSet) pc.addIceCandidate(c) else pendingCandidates.add(c)
        }

        private fun tune() {
            try {
                val s = sender ?: return
                val p = s.parameters
                for (e in p.encodings) {
                    e.maxBitrateBps = MAX_BITRATE_BPS
                    e.maxFramerate = MAX_FPS
                    e.minBitrateBps = MIN_BITRATE_BPS // LAN: skip the slow bandwidth ramp-up
                }
                // Under load, drop resolution before frame rate so animations stay smooth.
                p.degradationPreference = RtpParameters.DegradationPreference.MAINTAIN_FRAMERATE
                s.setParameters(p)
            } catch (_: Exception) {
            }
        }

        fun close() {
            if (closed) return
            closed = true
            peers.remove(this)
            try {
                pc.close()
                pc.dispose()
            } catch (_: Exception) {
            }
        }
    }

    // ------------------------------------------------------------------ teardown

    fun dispose() {
        for (p in peers) p.close()
        peers.clear()
        displayManager.unregisterDisplayListener(displayListener)
        val done = java.util.concurrent.CountDownLatch(1)
        handler.post {
            display?.release()
            display = null
            done.countDown()
        }
        done.await(2, java.util.concurrent.TimeUnit.SECONDS)
        helper.stopListening()
        track.dispose()
        videoSource.dispose()
        surface.release()
        helper.dispose()
        factory.dispose()
        egl.release()
        thread.quitSafely()
    }

    /** SdpObserver with no-op defaults. */
    private open class Sdp : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) {}
        override fun onSetFailure(error: String?) {}
    }
}
