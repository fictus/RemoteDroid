# RemoteDroid

A minimal AirDroid-style app: view and control your Android phone from a browser on your PC.
No cloud, no account. The phone runs a small web server; you open its address on your computer.

## Build & run
1. Open the `RemoteDroid` folder in **Android Studio** (Koala / 2024.1+). Accept the Gradle sync.
2. Run on a phone (USB debugging) or build an APK: *Build > Build APK(s)*.
3. On the phone:
   - Tap **Enable remote control** and turn on *RemoteDroid* under Accessibility.
     (If the toggle is greyed out on Android 13+: App info > ⋮ > *Allow restricted settings*.)
   - Tap **Start sharing**, accept the screen-capture prompt.
4. On your PC (same Wi-Fi or phone hotspot) open the URL shown on the phone, enter the PIN.

## Controls in the browser
| Action | How |
|---|---|
| Tap | Click |
| Long-press | Hold click 0.5 s+ |
| Swipe / drag | Click-drag (sent on release) |
| Scroll | Mouse wheel |
| Back | Right-click, Esc, or toolbar |
| Home / Recents / Notifications / Quick settings / Screenshot / Lock | Toolbar |
| Type | Click an input field on the phone, then type on your keyboard or paste; or use the text box |
| Wake screen | Toolbar |

## How it works
- `ScreenCapturer` — MediaProjection -> ImageReader -> JPEG frames (~15 fps, max 1280 px long side)
- `RemoteServer` — NanoHTTPD/NanoWSD: serves `assets/index.html` and one WebSocket (binary = frames, text = JSON commands)
- `InputAccessibilityService` — `dispatchGesture` for tap/swipe, `ACTION_SET_TEXT` for typing, global actions for nav keys
- `RemoteService` — foreground service tying it together; random 6-digit PIN each session

## Security notes (read these)
- Traffic is **plain HTTP/WS**. Use only on networks you trust, or run it over a VPN such as WireGuard/Tailscale. Adding TLS is the first upgrade to make for use on untrusted networks.
- PIN is regenerated every time you press Start. 5 wrong tries lock that IP for 60 s.
- Sharing stops when you press Stop in the app or the notification.

## Known limitations
- Apps that set `FLAG_SECURE` (banking apps, some password fields, DRM video) show black.
- Swipes are sent on mouse release, not as a live drag.
- Typing works on standard text fields; some custom/game fields ignore `ACTION_SET_TEXT`.
- Android asks for screen-capture permission each time you start sharing (a platform rule).
- Control cannot unlock a PIN/password-locked screen; Wake only turns the display on.

## Ideas for next steps
TLS + fixed pairing key, live drag gestures, WebRTC/H.264 for lower latency, clipboard sync,
file transfer, notification mirroring, send SMS from the PC.
