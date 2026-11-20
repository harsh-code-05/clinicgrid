# ClinicGrid Video Consultation Security and Architecture Guide

## 1. Executive Summary

ClinicGrid incorporates real-time WebRTC video consultation between verified healthcare providers and patients for scheduled telemedicine appointments.

To ensure production security, data privacy, and HIPAA/telehealth compliance, the video consultation subsystem features:
1. **Configurable Feature Gating (`HMS_WEBRTC_ENABLED`)**: Defaults to `false` during security and infrastructure evaluations.
2. **Authoritative Server-Side Identity**: Participant roles (`DOCTOR`, `PATIENT`) and display names are enforced by the Spring Security context, preventing spoofing.
3. **Strict Appointment Authorization**: Only the specific doctor and patient assigned to an active, `CONFIRMED` video appointment can query room details or exchange signaling messages. Administrators cannot eavesdrop or join video rooms.
4. **Signaling Payload Validation & Bounded Queues**: All STOMP signaling frames are type-checked and validated against an explicit whitelist of signal types (`JOIN`, `READY`, `OFFER`, `ANSWER`, `ICE_CANDIDATE`, `LEAVE`, `MEDIA_STATE`, `RECONNECT`). SDP payloads must contain `type` and `sdp`; ICE payloads must contain `candidate`. Incoming ICE queues are bounded to 100 entries.
5. **Fail-Safe Client Degradation & Immediate Media Revocation**: When disabled or revoked, the frontend disables video action buttons with explanatory tooltips, returns empty ICE server configurations (`iceServers: []`), halts media device acquisition, stops active media tracks, and destroys the peer connection.
6. **Appointment Existence Oracle Prevention**: Non-existent appointment queries fail closed with `ForbiddenException` (HTTP 403 Forbidden) rather than 404, preventing ID enumeration.

---

## 2. Architecture & Signaling Flow

```
[ Patient Browser ]                     [ ClinicGrid Spring Boot ]                     [ Doctor Browser ]
        |                                           |                                          |
        |--- 1. GET /api/appointments/video-status->|                                          |
        |<-- { enabled: true/false } ---------------|                                          |
        |                                           |                                          |
        |--- 2. GET /api/appointments/{id}/video-room ---------------------------------------->|
        |<-- { canJoin, roomId, iceServers: [...] } |                                          |
        |                                           |                                          |
        |--- 3. STOMP CONNECT /ws/signaling ------->|                                          |
        |--- 4. SUBSCRIBE /topic/video/room/{id} -->|                                          |
        |                                           |<-- 5. STOMP CONNECT & SUBSCRIBE ---------|
        |                                           |                                          |
        |--- 6. SEND /app/video/signal/{id} ------->| (Validates role, status, stamps sender)  |
        |       [type: JOIN / OFFER]                |--- 7. BROADCAST /topic/video/room/{id} ->|
        |                                           |                                          |
        |<-- 8. BROADCAST /topic/video/room/{id} ---|<-- 9. SEND /app/video/signal/{id} -------|
        |       [type: ANSWER / ICE]                |       [type: ANSWER / ICE]               |
        |                                           |                                          |
        |================= 10. Direct WebRTC P2P Media / DTLS-SRTP ===========================|
```

### Components:
- **`VideoConsultationController`**: REST endpoint (`/api/appointments/{id}/video-room` and `/api/appointments/video-status`) providing pre-flight access checks, status checks, and ephemeral ICE configurations.
- **`VideoSignalingController`**: WebSocket/STOMP handler (`/app/video/signal/{appointmentId}`) for exchanging SDP offers, SDP answers, and ICE candidate records.
- **`VideoConsultationService`**: Business logic enforcing appointment status, participant authorization, consultation mode (`VIDEO` only), and feature-gate gating.
- **`WebSocketConfig` & `SecurityConfig`**: WebSocket message broker configuration and HTTP security filters.
- **Frontend `useWebRTC.js`**: React hook handling `RTCPeerConnection`, local media streams, STOMP subscriptions, and ICE candidate negotiation.

---

## 3. STUN vs. TURN Reliability Analysis

### The Network Traversal Problem
WebRTC connections require bidirectional UDP media transmission between two peer browsers. In practice:
- **STUN (Session Traversal Utilities for NAT)** resolves a client's public reflexive IP and port. This works for Full Cone, Restricted Cone, and Port-Restricted Cone NATs (~80-85% of home residential connections).
- **Symmetric NAT**: Many enterprise networks, hospital campus firewalls, universities, and cellular carrier networks (4G/5G CGNAT) implement symmetric NAT. In symmetric NAT, the router allocates a unique external port for every external destination IP/port combination.
  - When Peer A (behind Symmetric NAT) attempts to connect to Peer B, the STUN server sees IP:Port_1, but Peer B receives packets from IP:Port_2. The binding cannot be predicted or traversed directly.
  - **Direct P2P fails 100% of the time between two symmetric NATs or between symmetric NAT and port-restricted NAT.**

### Why TURN is Required for Production Telehealth
- **TURN (Traversal Using Relays around NAT)**: Acts as an encrypted relay server. If direct P2P connection fails via STUN, both peers relay their encrypted media (SRTP) through the TURN server.
- **Current ClinicGrid State**:
  - The codebase includes STUN (`stun:stun.l.google.com:19302`) and has built-in code for RFC 5766 HMAC-SHA1 ephemeral TURN token generation (`hms.webrtc.turn-url`, `hms.webrtc.turn-username`, `hms.webrtc.turn-secret`).
  - However, in the free-tier Render production deployment, no dedicated TURN server (e.g., coturn or Cloudflare Calls) is configured.
  - Consequently, video consultation between users on cellular data or corporate hospital Wi-Fi would fail without TURN.
- **Security Recommendation**: Video consultation must remain gated (`HMS_WEBRTC_ENABLED=false`) until a reliable TURN relay (such as coturn on an inexpensive VPS or Cloudflare Calls TURN service) is provisioned.

---

## 4. Security Safeguards Implemented

### 4.1 Feature Gate (`HMS_WEBRTC_ENABLED`)
- Configured in `application.properties` and `application-prod.properties`:
  ```properties
  hms.webrtc.enabled=${HMS_WEBRTC_ENABLED:false}
  ```
- Defaults to `false`. Missing or invalid property fails closed.
- When disabled:
  - REST endpoint `/api/appointments/{id}/video-room` returns `canJoin: false`, empty ICE server list (`[]`), and status message: `"Video consultation is temporarily disabled pending security review."`
  - STOMP signaling attempts throw `ForbiddenException: Video consultation is temporarily disabled pending security review.`
  - Dedicated status endpoint `/api/appointments/video-status` returns `{"enabled": false, "message": "..."}`.
  - Frontend patient and doctor dashboards render a disabled `📹 Video Call (Disabled)` button with an explanatory tooltip, preventing modal initialization and camera/mic acquisition.

### 4.2 Server-Side Participant Identity (Anti-Spoofing)
- `VideoSignalingController` resolves participant identity from the authenticated `LocalAccount.Principal` via `videoConsultationService.validateSignalingAccess(...)`:
  ```java
  SignalingParticipant participant = videoConsultationService.validateSignalingAccess(
      appointmentId, actor
  );
  message.setAppointmentId(appointmentId);
  message.setSenderRole(participant.role());
  message.setSenderName(participant.name());
  ```
- Any client-submitted `senderRole`, `senderName`, or conflicting `appointmentId` is completely overwritten with verified server-side values.

### 4.3 Appointment Access & Ownership Verification
Before permitting room access or message exchange:
1. Appointment ID must be positive (`> 0`) and within bounds (`<= 10,000,000,000L`).
2. Appointment must exist in the database (non-existent appointments throw `ForbiddenException` to prevent existence leakage).
3. Appointment status must be `CONFIRMED` (canceling or completing an appointment immediately revokes video access).
4. Doctor verification status must be `VERIFIED` (unverified doctors cannot access video rooms).
5. Consultation mode must be `VIDEO` (appointments scheduled for `IN_CLINIC` are rejected).
6. The authenticated user must be:
   - The specific patient assigned to `appointment.getPatient()`, OR
   - The specific doctor assigned to `appointment.getDoctor()`.
   - Administrators (`ROLE_ADMIN`) are strictly prohibited from observing or participating in clinical video consultations.
7. Unauthorized attempts return HTTP 403 Forbidden or STOMP error.

### 4.4 STOMP Protocol & Channel Scoping
- Inbound STOMP messages are strictly validated:
  - Whitelist of allowed signal types: `JOIN`, `READY`, `OFFER`, `ANSWER`, `ICE_CANDIDATE`, `LEAVE`, `MEDIA_STATE`, `RECONNECT`.
  - Non-null payload required for `OFFER`, `ANSWER`, and `ICE_CANDIDATE`.
  - Permitted client STOMP commands restricted to: `CONNECT`, `STOMP`, `SUBSCRIBE`, `UNSUBSCRIBE`, `SEND`, `DISCONNECT`, `ACK`, `NACK`. Other commands (`BEGIN`, `COMMIT`, `ABORT`, etc.) are rejected with `AccessDeniedException`.
- Direct publishing to `/topic/**` or `/queue/**` broker destinations is blocked.
- Subscriptions and broadcasts are strictly scoped to `/topic/video/room/{appointmentId}`.
- Inter-appointment leakage is prevented: a user in appointment 101 cannot send signals into appointment 102.
- Session ID length is capped at 128 characters; generation counter must be non-negative.
- STOMP ERROR frames are sanitized (line breaks stripped, truncated to 200 chars).

### 4.5 Media Session Revocation & Technical Boundary Qualification
- **Signaling Channel Revocation**: If room eligibility is lost during a call (e.g., appointment is cancelled or completed, doctor verification status is revoked, or feature gate is disabled):
  - Any subsequent inbound WebSocket/STOMP signaling command (`SUBSCRIBE`, `SEND`, renegotiation, media toggle, or ICE trickling) is immediately rejected by `WebSocketConfig` and `VideoConsultationService` with `ForbiddenException`.
  - The server emits a sanitized STOMP `ERROR` frame to the client.
  - Upon receiving the terminal authorization error (`denied`, `forbidden`, `disabled`, `not confirmed`, `closed`, `not authorized`, `unauthorized`, `not verified`, `locked`, `in-clinic`), the client immediately terminates all local camera and microphone tracks (`track.stop()`), closes `RTCPeerConnection`, updates state to `failed`, and clears media streams.
- **Established Direct P2P Media Boundary**: Because ClinicGrid uses direct peer-to-peer WebRTC without an intermediate media server (SFU/MCU) or active out-of-band revocation push/polling, direct media packets (SRTP) flow between peer browsers. If an appointment is cancelled while neither participant emits any further signaling message, direct P2P media can continue until a signaling event, tab closure, or modal exit occurs. A future enhancement can introduce periodic room polling or active server-initiated disconnect frames to terminate active P2P media channels without requiring client signaling activity.

---

## 5. Production Re-enablement Runbook

When ready to activate video consultations in production:

### Step 1: Provision a TURN Relay
Set up a TURN server (e.g. coturn on Ubuntu / AWS EC2 / DigitalOcean, or Twilio Network Traversal / Cloudflare Calls TURN):
```bash
# Example Coturn configuration:
listening-port=3478
tls-listening-port=5349
realm=turn.clinicgrid.tech
use-auth-secret
static-auth-secret=<GENERATE_SECURE_RANDOM_HEX>
cert=/etc/letsencrypt/live/turn.clinicgrid.tech/fullchain.pem
pkey=/etc/letsencrypt/live/turn.clinicgrid.tech/privkey.pem
```

### Step 2: Configure Render Environment Variables
Add the following environment variables to Render Web Service (`clinicgrid-api`):
| Variable | Value | Description |
|---|---|---|
| `HMS_WEBRTC_ENABLED` | `true` | Enables video consultation subsystem |
| `HMS_WEBRTC_STUN_URLS` | `stun:stun.l.google.com:19302` | STUN discovery server |
| `HMS_WEBRTC_TURN_URL` | `turn:turn.clinicgrid.tech:3478?transport=udp` | TURN relay endpoint |
| `HMS_WEBRTC_TURN_USERNAME` | `clinicgrid-turn` | TURN username or auth key |
| `HMS_WEBRTC_TURN_SECRET` | `<CONFIGURED_TURN_SECRET>` | Shared secret for HMAC-SHA1 tokens |

### Step 3: Verify Post-Deployment
1. Log in as a doctor and a patient with a confirmed appointment.
2. Verify the `📹 Join Video Call` button becomes active.
3. Confirm audio/video stream exchange across distinct networks (e.g., cellular mobile vs. home broadband).
