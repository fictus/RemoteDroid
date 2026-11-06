# RemoteDroid

View and control your phone from a browser on the same Wi-Fi.

- **Video**: WebRTC (screen is a video track). **Input**: WebRTC data channel `ctl`.
- **Signalling**: WebSocket on the same port (8088) that serves the web page; PIN-authenticated.

## Staying alive when the phone locks
Android ends every screen-capture (MediaProjection) session when the device locks, and a
token can't be reused. RemoteDroid therefore separates the two:

- The **server** (HTTP, signalling, WebRTC, CPU + Wi-Fi wake locks, foreground notification)
  keeps running until you press Stop. Browsers stay connected.
- The **capture** pauses on lock. On unlock the app asks for a new capture approval
  (also available from the notification, the app, or the "Resume capture" button in the browser).
  Video resumes in the same browser tab without reconnecting.
- Connecting from a browser wakes the phone screen if it is off.

Setup: enable the Accessibility service, allow background running (battery), then Start sharing.
Keeping the phone plugged in avoids Doze.
