# Shared streaming

Each participant starts an independent live broadcast. The studio's **Streaming compartido** panel creates a private invitation room or joins an existing room by code. Rooms support up to four live streamers. The creator can copy the code; the browser retains it for that tab session, while PostgreSQL stores only its SHA-256 hash. A stream may belong to only one active group. Only a member may leave a group; the primary creator leaving closes it for everyone.

Both the studio and public watch view show the room's live perspectives in independent video tiles. A two-person group appears side by side on wider screens and stacks on narrow phones. Each secondary tile has its own WebRTC signaling connection, heartbeat, reconnection, mute control through native video controls, and WSS fragment fallback. Removing a participant releases its sockets, peer, media URLs, and player buffers. Participant changes refresh within ten seconds.

The primary source stays in the same player when a group becomes active or ends. Stream-end handling closes rooms and removes membership, including host heartbeats and connection expiry. Invitations to ended primary streams are rejected even if housekeeping has not run yet. New group creation also clears stale memberships left by older deployments.

Flyway migration V6 installs the collaboration tables in the backend's configured schema. The production schema is `streamguard`, which is not exposed through the Supabase Data API. Deploy the Java backend before publishing a frontend that calls collaboration endpoints. The Render service uses manual deployment from `codex/streamguard`; pushing only to a split repository is insufficient.

Validation includes media/lens regression tests, real PostgreSQL HTTP integration tests for ownership, live-stream requirements, invitation lifecycle and four-member capacity, and browser playback of two synthetic independent streams. Real mobile hardware and phone-screen broadcasting remain separate from these simulated camera checks.
