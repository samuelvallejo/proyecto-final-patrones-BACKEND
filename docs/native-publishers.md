# Native relay publishers

Android publishers reuse the current session, stream ownership checks, one-use connection tickets, signaling hub and media relay. No separate RTMP listener, new database schema, public database access or additional cloud service is introduced.

An owner joins `/ws` with `host=true` and `mediaTransport=relay`. The hub includes that transport in the joined/presence events. Viewers can immediately open `/ws/media` instead of waiting for a WebRTC offer. Other callers default to `webrtc`, preserving existing browser publishers. Transport metadata never grants ownership or changes viewer limits.

The Android owner independently obtains a second ticket and joins `/ws/media` with `format=video/mp4` and `fragmentMode=file`. Completed hardware-encoded MP4 files are delivered through the existing bounded binary path. The relay includes the fragment mode in `relay-format` for both existing and late viewers. Browser publishers default to the previous stream/MSE mode. Fragments remain transient and are subject to the existing one-megabyte maximum, queue bounds, stream state checks and authenticated room membership.

The companion combines approximately fifteen seconds of files on Android before uploading to `/streams/{id}/recording`. Existing ownership, file validation, FFmpeg conversion and archive budgets apply. Marked highlights use the same `/segments` endpoint. The native service sends heartbeats and uses bounded reconnect attempts; backend host expiry remains the final cleanup after power loss.

This is a small-audience relay implementation, subject to the current viewer count and hosting bandwidth/compute quotas. It is not a new unlimited streaming service. Android file playback has several seconds of latency and can show segment boundaries. The initial Android app records screen and microphone; it does not capture internal game audio or add a native camera overlay.

Verification: `NativeTransportTest` checks owner signaling and audience counting; `MediaRelayTest` checks MP4/file metadata, authentication, limits, late viewers and reconnects. The frontend capture-mode browser check plays independent MP4 files. A physical Android capture-to-viewer check remains necessary after installation.
