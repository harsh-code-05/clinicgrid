import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';
import { SignalingClient, resolveSignalingUrl } from '../src/api/signalingClient.js';

class MockWebSocket {
  static OPEN = 1;
  static CLOSED = 3;

  constructor(url) {
    this.url = url;
    this.readyState = MockWebSocket.OPEN;
    this.sentFrames = [];
    this.onopen = null;
    this.onmessage = null;
    this.onclose = null;
    this.onerror = null;

    queueMicrotask(() => {
      if (this.onopen) this.onopen();
    });
  }

  send(frame) {
    this.sentFrames.push(frame);
  }

  close() {
    this.readyState = MockWebSocket.CLOSED;
    if (this.onclose) this.onclose();
  }

  simulateServerMessage(msg) {
    if (this.onmessage) this.onmessage({ data: msg });
  }
}

test('resolveSignalingUrl - correctly maps HTTP/HTTPS to WS/WSS and preserves custom URLs', () => {
  // Explicit URL takes priority
  assert.equal(
    resolveSignalingUrl('wss://custom-stream.hospital.com/ws'),
    'wss://custom-stream.hospital.com/ws',
  );

  // HTTPS API base URL maps to WSS
  assert.equal(
    resolveSignalingUrl('', 'https://api.hospital.com/api'),
    'wss://api.hospital.com/ws/signaling',
  );

  // HTTP API base URL maps to WS
  assert.equal(
    resolveSignalingUrl('', 'http://192.168.1.50:8080/api'),
    'ws://192.168.1.50:8080/ws/signaling',
  );

  // Default fallback
  const fallback = resolveSignalingUrl('', '/api');
  assert.match(fallback, /^wss?:\/\//);
});

test('SignalingClient - connects, sends CONNECT frame, and handles CONNECTED response', async () => {
  globalThis.WebSocket = MockWebSocket;

  let connectedHeaders = null;
  const client = new SignalingClient({
    url: 'ws://localhost:8080/ws/signaling',
    onConnected: (headers) => {
      connectedHeaders = headers;
    },
  });

  client.connect();

  await new Promise((resolve) => setTimeout(resolve, 10));

  assert.equal(client.ws.sentFrames.length, 1);
  assert.match(client.ws.sentFrames[0], /^CONNECT\n/);

  // Simulate server sending CONNECTED frame
  client.ws.simulateServerMessage('CONNECTED\nversion:1.2\nsession:sess-123\n\n\0');

  assert.equal(client.connected, true);
  assert.equal(connectedHeaders.version, '1.2');
  assert.equal(connectedHeaders.session, 'sess-123');

  client.disconnect();
});

test('SignalingClient - subscribe sends SUBSCRIBE frame and dispatches incoming MESSAGE', async () => {
  globalThis.WebSocket = MockWebSocket;

  const client = new SignalingClient({
    url: 'ws://localhost:8080/ws/signaling',
  });

  client.connect();
  await new Promise((resolve) => setTimeout(resolve, 10));

  // Server marks connected
  client.ws.simulateServerMessage('CONNECTED\nversion:1.2\n\n\0');

  let receivedSignal = null;
  const unsubscribe = client.subscribe('/topic/video/room/42', (message) => {
    receivedSignal = message;
  });

  const subFrame = client.ws.sentFrames.find((f) => f.startsWith('SUBSCRIBE'));
  assert.ok(subFrame, 'SUBSCRIBE frame should be sent');
  assert.match(subFrame, /destination:\/topic\/video\/room\/42/);

  // Simulate incoming MESSAGE frame on topic
  client.ws.simulateServerMessage(
    'MESSAGE\nsubscription:sub-1\nmessage-id:msg-1\ndestination:/topic/video/room/42\ncontent-type:application/json\n\n{"type":"OFFER","senderRole":"DOCTOR","payload":{"sdp":"mock-sdp"}}\0'
  );

  assert.deepEqual(receivedSignal, {
    type: 'OFFER',
    senderRole: 'DOCTOR',
    payload: { sdp: 'mock-sdp' },
  });

  unsubscribe();
  const unsubFrame = client.ws.sentFrames.find((f) => f.startsWith('UNSUBSCRIBE'));
  assert.ok(unsubFrame, 'UNSUBSCRIBE frame should be sent');

  client.disconnect();
});

test('SignalingClient - send formats STOMP SEND frame with destination and JSON body', async () => {
  globalThis.WebSocket = MockWebSocket;

  const client = new SignalingClient({
    url: 'ws://localhost:8080/ws/signaling',
  });

  client.connect();
  await new Promise((resolve) => setTimeout(resolve, 10));
  client.ws.simulateServerMessage('CONNECTED\nversion:1.2\n\n\0');

  const sent = client.send('/app/video/signal/42', {
    type: 'ICE_CANDIDATE',
    payload: { candidate: 'candidate:1 1 UDP 1234' },
  });

  assert.equal(sent, true);
  const sendFrame = client.ws.sentFrames.find((f) => f.startsWith('SEND'));
  assert.ok(sendFrame, 'SEND frame should be dispatched');
  assert.match(sendFrame, /destination:\/app\/video\/signal\/42/);
  assert.match(sendFrame, /"type":"ICE_CANDIDATE"/);

  const ws = client.ws;
  client.disconnect();
  const disconnFrame = ws.sentFrames.find((f) => f.startsWith('DISCONNECT'));
  assert.ok(disconnFrame, 'DISCONNECT frame should be sent upon client disconnect');
});

test('SignalingClient - async subscriber errors are intercepted by client onError', async () => {
  globalThis.WebSocket = MockWebSocket;

  let capturedError = null;
  const client = new SignalingClient({
    url: 'ws://localhost:8080/ws/signaling',
    onError: (err) => {
      capturedError = err;
    },
  });

  client.connect();
  await new Promise((resolve) => setTimeout(resolve, 10));
  client.ws.simulateServerMessage('CONNECTED\nversion:1.2\n\n\0');

  client.subscribe('/topic/video/room/99', async () => {
    throw new Error('Async SDP negotiation failure');
  });

  client.ws.simulateServerMessage(
    'MESSAGE\nsubscription:sub-1\nmessage-id:msg-1\ndestination:/topic/video/room/99\ncontent-type:application/json\n\n{"type":"OFFER"}\0'
  );

  await new Promise((resolve) => setTimeout(resolve, 10));
  assert.ok(capturedError, 'onError should capture async subscriber rejection');
  assert.equal(capturedError.message, 'Async SDP negotiation failure');

  client.disconnect();
});

test('SignalingClient - reciprocal negotiation message exchange between two participants', async () => {
  globalThis.WebSocket = MockWebSocket;

  // Participant 1 (Patient)
  const patient = new SignalingClient({ url: 'ws://localhost:8080/ws/signaling' });
  patient.connect();
  await new Promise((r) => setTimeout(r, 10));
  patient.ws.simulateServerMessage('CONNECTED\nversion:1.2\n\n\0');

  // Participant 2 (Doctor)
  const doctor = new SignalingClient({ url: 'ws://localhost:8080/ws/signaling' });
  doctor.connect();
  await new Promise((r) => setTimeout(r, 10));
  doctor.ws.simulateServerMessage('CONNECTED\nversion:1.2\n\n\0');

  const doctorReceived = [];
  doctor.subscribe('/topic/video/room/10', (msg) => doctorReceived.push(msg));

  const patientReceived = [];
  patient.subscribe('/topic/video/room/10', (msg) => patientReceived.push(msg));

  // Patient sends JOIN
  patient.send('/app/video/signal/10', { type: 'JOIN', senderRole: 'PATIENT' });
  const joinFrame = patient.ws.sentFrames.find((f) => f.includes('"type":"JOIN"'));
  assert.ok(joinFrame, 'Patient should dispatch JOIN frame');

  // Simulate server broadcasting JOIN to doctor
  doctor.ws.simulateServerMessage(
    'MESSAGE\nsubscription:sub-1\ndestination:/topic/video/room/10\ncontent-type:application/json\n\n{"type":"JOIN","senderRole":"PATIENT"}\0'
  );
  assert.equal(doctorReceived.length, 1);
  assert.equal(doctorReceived[0].type, 'JOIN');

  // Doctor sends OFFER in response
  doctor.send('/app/video/signal/10', {
    type: 'OFFER',
    senderRole: 'DOCTOR',
    payload: { sdp: 'v=0...' },
  });
  const offerFrame = doctor.ws.sentFrames.find((f) => f.includes('"type":"OFFER"'));
  assert.ok(offerFrame, 'Doctor should dispatch OFFER frame');

  // Simulate server broadcasting OFFER to patient
  patient.ws.simulateServerMessage(
    'MESSAGE\nsubscription:sub-1\ndestination:/topic/video/room/10\ncontent-type:application/json\n\n{"type":"OFFER","senderRole":"DOCTOR","payload":{"sdp":"v=0..."}}\0'
  );
  assert.equal(patientReceived.length, 1);
  assert.equal(patientReceived[0].type, 'OFFER');

  patient.disconnect();
  doctor.disconnect();
});

test('SignalingClient - disconnect when socket not connected fails safely', () => {
  const client = new SignalingClient({ url: 'ws://localhost:8080/ws/signaling' });
  client.disconnect();
  assert.equal(client.connected, false);
});

test('WebRTC negotiation - Scenario B: Doctor joins first, Patient joins second produces single offer/answer', async () => {
  globalThis.WebSocket = MockWebSocket;

  const doctor = new SignalingClient({ url: 'ws://localhost:8080/ws/signaling' });
  const patient = new SignalingClient({ url: 'ws://localhost:8080/ws/signaling' });

  doctor.connect();
  patient.connect();
  await new Promise((r) => setTimeout(r, 10));
  doctor.ws.simulateServerMessage('CONNECTED\nversion:1.2\n\n\0');
  patient.ws.simulateServerMessage('CONNECTED\nversion:1.2\n\n\0');

  // Doctor joins first
  doctor.send('/app/video/signal/17', { type: 'JOIN', senderRole: 'DOCTOR' });

  // Patient joins second
  patient.send('/app/video/signal/17', { type: 'JOIN', senderRole: 'PATIENT' });

  // Doctor receives Patient JOIN -> sends OFFER once
  let offerSentCount = 0;
  let offerSent = false;
  function handleDoctorJoinReceived() {
    if (!offerSent) {
      offerSent = true;
      offerSentCount++;
      doctor.send('/app/video/signal/17', {
        type: 'OFFER',
        senderRole: 'DOCTOR',
        payload: { type: 'offer', sdp: 'v=0...' },
      });
    }
  }
  handleDoctorJoinReceived();

  // If a delayed READY message arrives afterwards, offer guard must prevent duplicate offer
  function handleDoctorReadyReceived() {
    if (!offerSent) {
      offerSent = true;
      offerSentCount++;
    }
  }
  handleDoctorReadyReceived();

  assert.equal(offerSentCount, 1, 'Doctor should produce exactly one OFFER, ignoring duplicate READY');
  const offerFrame = doctor.ws.sentFrames.find((f) => f.includes('"type":"OFFER"'));
  assert.ok(offerFrame, 'Doctor must send OFFER');

  // Patient receives OFFER -> Patient sends ANSWER
  patient.send('/app/video/signal/17', {
    type: 'ANSWER',
    senderRole: 'PATIENT',
    payload: { type: 'answer', sdp: 'v=0...' },
  });
  const answerFrame = patient.ws.sentFrames.find((f) => f.includes('"type":"ANSWER"'));
  assert.ok(answerFrame, 'Patient must send ANSWER');

  doctor.disconnect();
  patient.disconnect();
});

test('SignalingClient - server ERROR frame is dispatched to onError callback', async () => {
  globalThis.WebSocket = MockWebSocket;

  let capturedError = null;
  const client = new SignalingClient({
    url: 'ws://localhost:8080/ws/signaling',
    onError: (err) => {
      capturedError = err;
    },
  });

  client.connect();
  await new Promise((r) => setTimeout(r, 10));
  client.ws.simulateServerMessage('CONNECTED\nversion:1.2\n\n\0');

  client.ws.simulateServerMessage(
    'ERROR\nmessage:Client command not permitted: MESSAGE\n\nCommand not allowed\0'
  );

  assert.ok(capturedError, 'Client should notify onError on receiving ERROR frame');
  assert.match(capturedError.message, /Client command not permitted/);

  client.disconnect();
});

test('useWebRTC - iceServers fallback and custom ICE configuration resolution', () => {
  const defaultIce = [{ urls: 'stun:stun.l.google.com:19302' }];
  const customIce = [{ urls: 'turn:turn.hospital.com:3478', username: 'user', credential: 'pwd' }];

  function resolveIce(room) {
    return room?.iceServers && room.iceServers.length > 0 ? room.iceServers : defaultIce;
  }

  assert.deepEqual(resolveIce({}), defaultIce);
  assert.deepEqual(resolveIce({ iceServers: [] }), defaultIce);
  assert.deepEqual(resolveIce({ iceServers: customIce }), customIce);
});

// --- Direct useWebRTC Hook Execution & Re-entry / Race Tests ---

class MockHookRTCPeerConnection {
  static instances = [];
  static createOfferHandler = null;
  static createAnswerHandler = null;
  static setLocalDescriptionHandler = null;
  static setRemoteDescriptionHandler = null;

  constructor(config = {}) {
    this.config = config;
    this.signalingState = 'stable';
    this.connectionState = 'new';
    this.remoteDescription = null;
    this.localDescription = null;
    this.tracks = [];
    this.onicecandidate = null;
    this.ontrack = null;
    this.onconnectionstatechange = null;
    MockHookRTCPeerConnection.instances.push(this);
  }

  addTrack(track, stream) {
    this.tracks.push({ track, stream });
  }

  async createOffer() {
    if (MockHookRTCPeerConnection.createOfferHandler) {
      return await MockHookRTCPeerConnection.createOfferHandler(this);
    }
    return { type: 'offer', sdp: 'mock-offer-sdp' };
  }

  async createAnswer() {
    if (MockHookRTCPeerConnection.createAnswerHandler) {
      return await MockHookRTCPeerConnection.createAnswerHandler(this);
    }
    return { type: 'answer', sdp: 'mock-answer-sdp' };
  }

  async setLocalDescription(desc) {
    if (MockHookRTCPeerConnection.setLocalDescriptionHandler) {
      await MockHookRTCPeerConnection.setLocalDescriptionHandler(desc, this);
    }
    this.localDescription = desc;
    if (desc.type === 'offer') this.signalingState = 'have-local-offer';
    else if (desc.type === 'answer') this.signalingState = 'stable';
  }

  async setRemoteDescription(desc) {
    if (MockHookRTCPeerConnection.setRemoteDescriptionHandler) {
      await MockHookRTCPeerConnection.setRemoteDescriptionHandler(desc, this);
    }
    this.remoteDescription = desc;
    if (desc.type === 'offer') this.signalingState = 'have-remote-offer';
    else if (desc.type === 'answer') this.signalingState = 'stable';
  }

  async addIceCandidate(cand) {
    if (MockHookRTCPeerConnection.addIceCandidateHandler) {
      await MockHookRTCPeerConnection.addIceCandidateHandler(cand, this);
    }
    this.candidate = cand;
  }

  close() {
    this.signalingState = 'closed';
    this.connectionState = 'closed';
    if (this.onconnectionstatechange) this.onconnectionstatechange();
  }
}

class MockMediaStream {
  constructor() {
    this.tracks = [
      { kind: 'audio', stop() {}, enabled: true },
      { kind: 'video', stop() {}, enabled: true },
    ];
  }
  getTracks() { return this.tracks; }
  getAudioTracks() { return this.tracks.filter((t) => t.kind === 'audio'); }
  getVideoTracks() { return this.tracks.filter((t) => t.kind === 'video'); }
}

function createHookHarness() {
  const hooks = [];
  let cursor = 0;
  let pendingEffects = [];
  const activeSockets = [];
  const signalingClients = [];

  class HarnessWebSocket extends MockWebSocket {
    constructor(url) {
      super(url);
      activeSockets.push(this);
    }
  }

  class ScopedSignalingClient extends SignalingClient {
    constructor(...args) {
      super(...args);
      signalingClients.push(this);
    }

    connect() {
      const prev = globalThis.WebSocket;
      globalThis.WebSocket = HarnessWebSocket;
      try {
        return super.connect();
      } finally {
        globalThis.WebSocket = prev;
      }
    }
  }

  function sameDeps(a, b) {
    if (!a || !b || a.length !== b.length) return false;
    return a.every((v, i) => Object.is(v, b[i]));
  }

  Object.defineProperty(globalThis.navigator, 'mediaDevices', {
    value: { getUserMedia: async () => new MockMediaStream() },
    configurable: true,
    writable: true,
  });
  globalThis.RTCPeerConnection = MockHookRTCPeerConnection;
  globalThis.RTCSessionDescription = class { constructor(d) { Object.assign(this, d); } };
  globalThis.RTCIceCandidate = class { constructor(c) { Object.assign(this, c); } };
  globalThis.WebSocket = HarnessWebSocket;

  const context = vm.createContext({
    useState(init) {
      const idx = cursor++;
      if (!hooks[idx]) hooks[idx] = { value: typeof init === 'function' ? init() : init };
      const cell = hooks[idx];
      return [cell.value, (n) => { cell.value = typeof n === 'function' ? n(cell.value) : n; }];
    },
    useRef(init) {
      const idx = cursor++;
      if (!hooks[idx]) hooks[idx] = { current: init };
      return hooks[idx];
    },
    useCallback(fn, deps) {
      const idx = cursor++;
      if (!hooks[idx] || !sameDeps(hooks[idx].deps, deps)) hooks[idx] = { fn, deps };
      return hooks[idx].fn;
    },
    useEffect(effect, deps) {
      const idx = cursor++;
      if (!hooks[idx] || !sameDeps(hooks[idx].deps, deps)) {
        const prevCleanup = hooks[idx]?.cleanup;
        hooks[idx] = { deps };
        pendingEffects.push(() => {
          if (prevCleanup) prevCleanup();
          const cl = effect();
          if (typeof cl === 'function') hooks[idx].cleanup = cl;
        });
      }
    },
    SignalingClient: ScopedSignalingClient,
    RTCPeerConnection: MockHookRTCPeerConnection,
    RTCSessionDescription: class { constructor(d) { Object.assign(this, d); } },
    RTCIceCandidate: class { constructor(c) { Object.assign(this, c); } },
    navigator: globalThis.navigator,
    WebSocket: HarnessWebSocket,
    setTimeout,
    clearTimeout,
  });

  const hookSrc = readFileSync(new URL('../src/hooks/useWebRTC.js', import.meta.url), 'utf8')
    .replace(/^import\s+[\s\S]*?\s+from\s+['"][^'"]+['"];?\r?\n?/gm, '')
    .replace(/^import\s+['"][^'"]+['"];?\r?\n?/gm, '')
    .replace('export function useWebRTC', 'function useWebRTC');

  const useWebRTC = vm.runInContext(hookSrc + '\nuseWebRTC;', context);

  function render(props) {
    cursor = 0;
    pendingEffects = [];
    const res = useWebRTC(props);
    const effects = [...pendingEffects];
    pendingEffects = [];
    effects.forEach((e) => e());
    return res;
  }

  function getLatestSocket() {
    return activeSockets[activeSockets.length - 1];
  }

  function unmount() {
    hooks.forEach((c) => c?.cleanup?.());
  }

  return {
    render,
    getLatestSocket,
    getSignalingClient: () => signalingClients[signalingClients.length - 1],
    unmount,
    activeSockets,
    HarnessWebSocket,
    getConnectionState: () => hooks[2]?.value,
    getError: () => hooks[6]?.value,
    hooks,
  };
}

test('useWebRTC hook - Doctor re-entry: Patient resets peer connection on LEAVE and emits READY on Doctor re-entry JOIN', async () => {
  MockHookRTCPeerConnection.instances = [];

  const roomDetails = { canJoin: true, userRole: 'PATIENT', userName: 'Patient Alice' };
  const patientHarness = createHookHarness();
  patientHarness.render({ appointmentId: 17, roomDetails });

  // Await media acquisition and signaling connection
  await new Promise((r) => setTimeout(r, 50));

  const patWs = patientHarness.getLatestSocket();
  assert.ok(patWs, 'Patient WebSocket must be created');
  patWs.simulateServerMessage('CONNECTED\nversion:1.2\n\n\0');
  await new Promise((r) => setTimeout(r, 20));

  const patPc = MockHookRTCPeerConnection.instances[0];
  assert.ok(patPc, 'Patient RTCPeerConnection must be instantiated');

  // Initial call negotiation: Doctor sends OFFER
  patWs.simulateServerMessage(
    'MESSAGE\nsubscription:sub-1\ndestination:/topic/video/room/17\n\n{"type":"OFFER","senderRole":"DOCTOR","payload":{"type":"offer","sdp":"mock-sdp"}}\0'
  );
  await new Promise((r) => setTimeout(r, 20));
  assert.ok(patPc.remoteDescription, 'Patient should have set remoteDescription from OFFER');
  assert.equal(patPc.remoteDescription.type, 'offer');
  assert.equal(patPc.signalingState, 'stable');

  // Doctor leaves call: Doctor sends LEAVE
  patWs.simulateServerMessage(
    'MESSAGE\nsubscription:sub-1\ndestination:/topic/video/room/17\n\n{"type":"LEAVE","senderRole":"DOCTOR"}\0'
  );
  await new Promise((r) => setTimeout(r, 20));

  // Assert Patient peer connection was reset: new instance created in stable state with NO remoteDescription
  const resetPatPc = MockHookRTCPeerConnection.instances[MockHookRTCPeerConnection.instances.length - 1];
  assert.notEqual(resetPatPc, patPc, 'Patient must recreate a fresh RTCPeerConnection on LEAVE');
  assert.equal(resetPatPc.remoteDescription, null, 'Reset connection must have no remoteDescription');
  assert.equal(resetPatPc.signalingState, 'stable', 'Reset connection must be in stable state');

  // Doctor re-enters room and sends JOIN
  const sentFramesBefore = patWs.sentFrames.length;
  patWs.simulateServerMessage(
    'MESSAGE\nsubscription:sub-1\ndestination:/topic/video/room/17\n\n{"type":"JOIN","senderRole":"DOCTOR"}\0'
  );
  await new Promise((r) => setTimeout(r, 20));

  // Assert Patient responds with READY to Doctor new JOIN
  const readyFrame = patWs.sentFrames.slice(sentFramesBefore).find((f) => f.includes('"type":"READY"'));
  assert.ok(readyFrame, 'Patient MUST emit READY upon receiving Doctor re-entry JOIN');

  patientHarness.unmount();
});

test('useWebRTC hook - Abandoned offer: Doctor resets peer connection on LEAVE so subsequent Patient JOIN creates fresh offer', async () => {
  MockHookRTCPeerConnection.instances = [];

  const roomDetails = { canJoin: true, userRole: 'DOCTOR', userName: 'Dr. House' };
  const docHarness = createHookHarness();
  docHarness.render({ appointmentId: 17, roomDetails });

  // Await media acquisition and signaling connection
  await new Promise((r) => setTimeout(r, 50));

  const docWs = docHarness.getLatestSocket();
  assert.ok(docWs, 'Doctor WebSocket must be created');
  docWs.simulateServerMessage('CONNECTED\nversion:1.2\n\n\0');
  await new Promise((r) => setTimeout(r, 20));

  const docPc = MockHookRTCPeerConnection.instances[0];
  assert.ok(docPc, 'Doctor RTCPeerConnection must be instantiated');

  // Doctor receives READY from Patient -> Doctor creates OFFER
  docWs.simulateServerMessage(
    'MESSAGE\nsubscription:sub-1\ndestination:/topic/video/room/17\n\n{"type":"READY","senderRole":"PATIENT"}\0'
  );
  await new Promise((r) => setTimeout(r, 20));
  assert.equal(docPc.signalingState, 'have-local-offer');
  assert.equal(docPc.localDescription.type, 'offer');

  // Patient leaves before answering: Patient sends LEAVE
  docWs.simulateServerMessage(
    'MESSAGE\nsubscription:sub-1\ndestination:/topic/video/room/17\n\n{"type":"LEAVE","senderRole":"PATIENT"}\0'
  );
  await new Promise((r) => setTimeout(r, 20));

  // Assert Doctor peer connection was reset out of have-local-offer back into stable state
  const resetDocPc = MockHookRTCPeerConnection.instances[MockHookRTCPeerConnection.instances.length - 1];
  assert.notEqual(resetDocPc, docPc, 'Doctor must recreate a fresh RTCPeerConnection on LEAVE');
  assert.equal(resetDocPc.signalingState, 'stable', 'Reset Doctor connection must be in stable state');

  // Patient rejoins and sends JOIN
  const sentFramesBefore = docWs.sentFrames.length;
  docWs.simulateServerMessage(
    'MESSAGE\nsubscription:sub-1\ndestination:/topic/video/room/17\n\n{"type":"JOIN","senderRole":"PATIENT"}\0'
  );
  await new Promise((r) => setTimeout(r, 20));

  // Assert Doctor generates a fresh OFFER without being blocked in have-local-offer
  const newOfferFrame = docWs.sentFrames.slice(sentFramesBefore).find((f) => f.includes('"type":"OFFER"'));
  assert.ok(newOfferFrame, 'Doctor MUST emit fresh OFFER upon Patient re-entry after abandoned offer');

  docHarness.unmount();
});
test('useWebRTC hook - Simultaneous join deduplication: Doctor produces exactly one OFFER when JOIN and delayed READY arrive', async () => {
  MockHookRTCPeerConnection.instances = [];

  const roomDetails = { canJoin: true, userRole: 'DOCTOR', userName: 'Dr. House' };
  const docHarness = createHookHarness();
  docHarness.render({ appointmentId: 17, roomDetails });

  // Await media acquisition and signaling connection
  await new Promise((r) => setTimeout(r, 50));

  const docWs = docHarness.getLatestSocket();
  assert.ok(docWs, 'Doctor WebSocket must be created');
  docWs.simulateServerMessage('CONNECTED\nversion:1.2\n\n\0');
  await new Promise((r) => setTimeout(r, 20));

  const sentFramesBefore = docWs.sentFrames.length;

  // Simultaneous Patient JOIN arrives
  docWs.simulateServerMessage(
    'MESSAGE\nsubscription:sub-1\ndestination:/topic/video/room/17\n\n{"type":"JOIN","senderRole":"PATIENT"}\0'
  );
  await new Promise((r) => setTimeout(r, 20));

  // Followed by delayed Patient READY
  docWs.simulateServerMessage(
    'MESSAGE\nsubscription:sub-1\ndestination:/topic/video/room/17\n\n{"type":"READY","senderRole":"PATIENT"}\0'
  );
  await new Promise((r) => setTimeout(r, 20));

  // Filter offers sent
  const offerFrames = docWs.sentFrames.slice(sentFramesBefore).filter((f) => f.includes('"type":"OFFER"'));
  assert.equal(offerFrames.length, 1, 'Doctor must produce exactly one OFFER, ignoring second triggered offer');

  docHarness.unmount();
});
test('useWebRTC hook - Locked room: canJoin=false returns locked connectionState without acquiring media', async () => {
  const harness = createHookHarness();
  const res = harness.render({
    appointmentId: 146,
    roomDetails: { canJoin: false, doctorVerified: false, statusMessage: 'Doctor verification pending' },
  });

  assert.equal(res.connectionState, 'locked');
  assert.equal(res.localStream, null);
  harness.unmount();
});

test('useWebRTC hook - Reconnect during pending SDP: Doctor resets interrupted offer on Patient rejoin and completes negotiation', async () => {
  MockHookRTCPeerConnection.instances = [];
  MockHookRTCPeerConnection.createOfferHandler = null;

  const docRoom = { canJoin: true, userRole: 'DOCTOR', userName: 'Dr. House' };
  const docHarness = createHookHarness();
  docHarness.render({ appointmentId: 17, roomDetails: docRoom });

  const patRoom = { canJoin: true, userRole: 'PATIENT', userName: 'Patient Alice' };
  const patHarness = createHookHarness();
  patHarness.render({ appointmentId: 17, roomDetails: patRoom });

  await new Promise((r) => setTimeout(r, 50));
  const docWs = docHarness.getLatestSocket();
  const patWs1 = patHarness.getLatestSocket();
  assert.ok(docWs && patWs1);

  docWs.simulateServerMessage('CONNECTED\nversion:1.2\n\n\0');
  patWs1.simulateServerMessage('CONNECTED\nversion:1.2\n\n\0');
  await new Promise((r) => setTimeout(r, 20));

  // 1. Initial presence: Patient emits JOIN on patWs1
  const patJoinFrame1 = patWs1.sentFrames.find((f) => f.includes('"type":"JOIN"'));
  assert.ok(patJoinFrame1, 'Patient must emit initial JOIN frame');
  const patJoinBody1 = patJoinFrame1.split('\n\n')[1].replace(/\0$/, '');

  // Forward initial JOIN to Doctor -> Doctor creates Offer A (generation: 1)
  const sentBeforeDoc1 = docWs.sentFrames.length;
  docWs.simulateServerMessage(
    `MESSAGE\nsubscription:sub-1\ndestination:/topic/video/room/17\n\n${patJoinBody1}\0`
  );
  await new Promise((r) => setTimeout(r, 20));

  const offerFrameA = docWs.sentFrames.slice(sentBeforeDoc1).find((f) => f.includes('"type":"OFFER"'));
  assert.ok(offerFrameA, 'Doctor must emit Offer A');
  assert.ok(offerFrameA.includes('"generation":1'), 'Offer A must carry generation 1');
  const offerBodyA = offerFrameA.split('\n\n')[1].replace(/\0$/, '');

  const docPc1 = MockHookRTCPeerConnection.instances[0];
  assert.equal(docPc1.signalingState, 'have-local-offer', 'Doctor must be in have-local-offer waiting for answer');

  // 2. Deliver Offer A to Patient and defer Patient's answer creation
  let answerResolveA;
  let answerRejectA;
  MockHookRTCPeerConnection.createAnswerHandler = () => new Promise((resolve, reject) => {
    answerResolveA = resolve;
    answerRejectA = reject;
  });

  patWs1.simulateServerMessage(
    `MESSAGE\nsubscription:sub-1\ndestination:/topic/video/room/17\n\n${offerBodyA}\0`
  );
  await new Promise((r) => setTimeout(r, 20));

  // Patient is answering Offer A, connection state is received_offer
  const patDuringAnswerA = patHarness.render({ appointmentId: 17, roomDetails: patRoom });
  assert.equal(patDuringAnswerA.connectionState, 'received_offer');

  // 3. Interruption: Patient's signaling drops before answer completes. Reconnects via SignalingClient
  const patSignaling = patHarness.getSignalingClient();
  assert.ok(patSignaling, 'Patient SignalingClient must be accessible');

  patSignaling.connect();
  const patWs2 = patHarness.getLatestSocket();
  assert.notEqual(patWs2, patWs1, 'Patient must have created a fresh replacement WebSocket');

  // Connect replacement socket -> SignalingClient re-subscribes and emits fresh JOIN
  patWs2.simulateServerMessage('CONNECTED\nversion:1.2\n\n\0');
  await new Promise((r) => setTimeout(r, 20));

  const patJoinFrame2 = patWs2.sentFrames.find((f) => f.includes('"type":"JOIN"'));
  assert.ok(patJoinFrame2, 'Patient replacement socket must emit fresh JOIN frame upon reconnect');
  const patJoinBody2 = patJoinFrame2.split('\n\n')[1].replace(/\0$/, '');

  // 4. Forward fresh JOIN to Doctor
  const sentBeforeDoc2 = docWs.sentFrames.length;
  docWs.simulateServerMessage(
    `MESSAGE\nsubscription:sub-1\ndestination:/topic/video/room/17\n\n${patJoinBody2}\0`
  );
  await new Promise((r) => setTimeout(r, 20));

  // Doctor resets interrupted offer and emits replacement Offer B with generation 2
  const offerFrameB = docWs.sentFrames.slice(sentBeforeDoc2).find((f) => f.includes('"type":"OFFER"'));
  assert.ok(offerFrameB, 'Doctor MUST emit replacement Offer B');
  assert.ok(offerFrameB.includes('"generation":2'), 'Offer B must carry generation 2');
  const offerBodyB = offerFrameB.split('\n\n')[1].replace(/\0$/, '');

  const docPc2 = MockHookRTCPeerConnection.instances[MockHookRTCPeerConnection.instances.length - 1];
  assert.notEqual(docPc2, docPc1, 'Doctor must have replaced peer connection');
  assert.equal(docPc2.signalingState, 'have-local-offer', 'Doctor PC must have local offer set');

  // 5. Restore normal answer creation so Offer B can answer cleanly
  MockHookRTCPeerConnection.createAnswerHandler = null;

  // Deliver replacement Offer B to Patient on replacement socket patWs2
  const sentBeforePat2 = patWs2.sentFrames.length;
  patWs2.simulateServerMessage(
    `MESSAGE\nsubscription:sub-1\ndestination:/topic/video/room/17\n\n${offerBodyB}\0`
  );
  await new Promise((r) => setTimeout(r, 20));

  // Patient supersedes Offer A, applies Offer B, and automatically sends Answer B with generation 2
  const answerFrameB = patWs2.sentFrames.slice(sentBeforePat2).find((f) => f.includes('"type":"ANSWER"'));
  assert.ok(answerFrameB, 'Patient MUST emit Answer B with generation 2');
  assert.ok(answerFrameB.includes('"generation":2'), 'Answer B must carry generation 2');
  const answerBodyB = answerFrameB.split('\n\n')[1].replace(/\0$/, '');

  // 6. Asynchronous obsolete answer rejection simulation:
  // Stale Answer A promise resolves with failure or late answer
  if (answerRejectA) {
    answerRejectA(new Error('Obsolete Answer A rejected'));
  } else if (answerResolveA) {
    answerResolveA({ type: 'answer', sdp: 'stale-answer-a' });
  }
  await new Promise((r) => setTimeout(r, 20));

  // Patient hook must strictly ignore obsolete Answer A failure and NOT enter error state
  const patRecovered = patHarness.render({ appointmentId: 17, roomDetails: patRoom });
  assert.equal(patRecovered.error, null, 'Patient hook must not capture error from superseded Answer A');

  // Stale Answer A arriving at Doctor must be discarded
  docWs.simulateServerMessage(
    `MESSAGE\nsubscription:sub-1\ndestination:/topic/video/room/17\n\n${JSON.stringify({
      type: 'ANSWER',
      senderRole: 'PATIENT',
      generation: 1, // stale generation!
      payload: { type: 'answer', sdp: 'stale-answer-a' },
    })}\0`
  );
  await new Promise((r) => setTimeout(r, 20));

  // Doctor must NOT accept stale Answer A: Doctor PC remains in have-local-offer
  assert.equal(docPc2.signalingState, 'have-local-offer', 'Doctor must discard stale Answer A');

  // 7. Deliver valid matching Answer B to Doctor
  docWs.simulateServerMessage(
    `MESSAGE\nsubscription:sub-1\ndestination:/topic/video/room/17\n\n${answerBodyB}\0`
  );
  await new Promise((r) => setTimeout(r, 20));

  // Doctor applies Answer B and reaches stable
  assert.equal(docPc2.signalingState, 'stable', 'Doctor reaches stable state on matching Answer B');

  docPc2.connectionState = 'connected';
  if (docPc2.onconnectionstatechange) docPc2.onconnectionstatechange();
  const finalDocState = docHarness.render({ appointmentId: 17, roomDetails: docRoom });
  assert.equal(finalDocState.connectionState, 'connected', 'Doctor reaches connected successfully');

  docHarness.unmount();
  patHarness.unmount();
});

test('useWebRTC hook - Patient generation tracking rejects older offers and never moves backward', async () => {
  MockHookRTCPeerConnection.instances = [];
  MockHookRTCPeerConnection.createAnswerHandler = null;

  const patRoom = { canJoin: true, userRole: 'PATIENT', userName: 'Patient Alice' };
  const patHarness = createHookHarness();
  patHarness.render({ appointmentId: 17, roomDetails: patRoom });

  await new Promise((r) => setTimeout(r, 50));
  const patWs = patHarness.getLatestSocket();
  patWs.simulateServerMessage('CONNECTED\nversion:1.2\n\n\0');
  await new Promise((r) => setTimeout(r, 20));

  // Patient completes Offer generation 2
  patWs.simulateServerMessage(
    `MESSAGE\nsubscription:sub-1\ndestination:/topic/video/room/17\n\n${JSON.stringify({
      type: 'OFFER',
      senderRole: 'DOCTOR',
      generation: 2,
      payload: { type: 'offer', sdp: 'gen-2-offer' },
    })}\0`
  );
  await new Promise((r) => setTimeout(r, 20));

  const patPc = MockHookRTCPeerConnection.instances[0];
  assert.equal(patPc.signalingState, 'stable', 'Patient must answer offer 2 and reach stable');
  const answersCountGen2 = patWs.sentFrames.filter((f) => f.includes('"type":"ANSWER"')).length;
  assert.equal(answersCountGen2, 1, 'Patient sent Answer for generation 2');

  // Now simulate stale Offer generation 1 arriving late
  patWs.simulateServerMessage(
    `MESSAGE\nsubscription:sub-1\ndestination:/topic/video/room/17\n\n${JSON.stringify({
      type: 'OFFER',
      senderRole: 'DOCTOR',
      generation: 1, // older generation!
      payload: { type: 'offer', sdp: 'gen-1-stale-offer' },
    })}\0`
  );
  await new Promise((r) => setTimeout(r, 20));

  // Assert: Patient did NOT create a new peer connection, did NOT send Answer for generation 1, and remains in stable
  const instancesAfter = MockHookRTCPeerConnection.instances.length;
  assert.equal(instancesAfter, 1, 'Patient must not recreate peer connection on older generation offer');
  const answersCountAfter = patWs.sentFrames.filter((f) => f.includes('"type":"ANSWER"')).length;
  assert.equal(answersCountAfter, 1, 'Patient must not emit answer for older generation offer');
  assert.equal(patPc.signalingState, 'stable', 'Patient must remain in stable state');

  patHarness.unmount();
});

test('useWebRTC hook - Peer media state reset: Camera-off state is reset on LEAVE and synchronized on re-entry', async () => {
  MockHookRTCPeerConnection.instances = [];

  const roomDetails = { canJoin: true, userRole: 'DOCTOR', userName: 'Dr. House' };
  const docHarness = createHookHarness();
  docHarness.render({ appointmentId: 17, roomDetails });

  await new Promise((r) => setTimeout(r, 50));
  const docWs = docHarness.getLatestSocket();
  docWs.simulateServerMessage('CONNECTED\nversion:1.2\n\n\0');
  await new Promise((r) => setTimeout(r, 20));

  // Remote peer turns off video
  docWs.simulateServerMessage(
    'MESSAGE\nsubscription:sub-1\ndestination:/topic/video/room/17\n\n{"type":"MEDIA_STATE","senderRole":"PATIENT","payload":{"isAudioMuted":false,"isVideoOff":true}}\0'
  );
  await new Promise((r) => setTimeout(r, 20));
  const afterVideoOff = docHarness.render({ appointmentId: 17, roomDetails });
  assert.equal(afterVideoOff.peerMediaState.isVideoOff, true, 'peerMediaState must reflect camera off');

  // Remote peer leaves call
  docWs.simulateServerMessage(
    'MESSAGE\nsubscription:sub-1\ndestination:/topic/video/room/17\n\n{"type":"LEAVE","senderRole":"PATIENT"}\0'
  );
  await new Promise((r) => setTimeout(r, 20));

  // Assert peerMediaState is reset back to camera on (isVideoOff = false)
  const afterLeave = docHarness.render({ appointmentId: 17, roomDetails });
  assert.equal(afterLeave.peerMediaState.isVideoOff, false, 'peerMediaState.isVideoOff must reset to false on LEAVE');

  // Remote peer rejoins with camera enabled
  docWs.simulateServerMessage(
    'MESSAGE\nsubscription:sub-1\ndestination:/topic/video/room/17\n\n{"type":"JOIN","senderRole":"PATIENT"}\0'
  );
  docWs.simulateServerMessage(
    'MESSAGE\nsubscription:sub-1\ndestination:/topic/video/room/17\n\n{"type":"MEDIA_STATE","senderRole":"PATIENT","payload":{"isAudioMuted":false,"isVideoOff":false}}\0'
  );
  await new Promise((r) => setTimeout(r, 20));

  const afterRejoin = docHarness.render({ appointmentId: 17, roomDetails });
  assert.equal(afterRejoin.peerMediaState.isVideoOff, false, 'Returning peer camera must remain visible');

  docHarness.unmount();
});
test('useWebRTC hook - Deferred async SDP resolution, rejection recovery, and reset during pending SDP', async () => {
  MockHookRTCPeerConnection.instances = [];

  // Phase 1: Deferred SDP resolution
  let deferredResolve;
  MockHookRTCPeerConnection.createOfferHandler = () => {
    return new Promise((resolve) => {
      deferredResolve = resolve;
    });
  };

  const roomDetails = { canJoin: true, userRole: 'DOCTOR', userName: 'Dr. House' };
  const docHarness = createHookHarness();
  docHarness.render({ appointmentId: 17, roomDetails });

  await new Promise((r) => setTimeout(r, 50));
  const docWs = docHarness.getLatestSocket();
  docWs.simulateServerMessage('CONNECTED\nversion:1.2\n\n\0');
  await new Promise((r) => setTimeout(r, 20));

  // Trigger offer creation via READY
  docWs.simulateServerMessage(
    'MESSAGE\nsubscription:sub-1\ndestination:/topic/video/room/17\n\n{"type":"READY","senderRole":"PATIENT"}\0'
  );
  await new Promise((r) => setTimeout(r, 10));

  // Hook must be in creating_offer while deferred SDP promise is unresolved
  const duringOffer = docHarness.render({ appointmentId: 17, roomDetails });
  assert.equal(duringOffer.connectionState, 'creating_offer', 'State must be creating_offer while deferred SDP resolves');

  // Phase 2: Peer LEAVE occurs during pending deferred SDP
  docWs.simulateServerMessage(
    'MESSAGE\nsubscription:sub-1\ndestination:/topic/video/room/17\n\n{"type":"LEAVE","senderRole":"PATIENT"}\0'
  );
  await new Promise((r) => setTimeout(r, 20));

  const afterLeaveDuringSdp = docHarness.render({ appointmentId: 17, roomDetails });
  assert.equal(afterLeaveDuringSdp.connectionState, 'peer_left', 'Hook must reset to peer_left on LEAVE during pending SDP');

  // Now resolve the stale deferred offer
  const framesBeforeLateResolve = docWs.sentFrames.length;
  deferredResolve({ type: 'offer', sdp: 'stale-deferred-offer' });
  await new Promise((r) => setTimeout(r, 20));
  const lateFrames = docWs.sentFrames.slice(framesBeforeLateResolve).filter((f) => f.includes('stale-deferred-offer'));
  assert.equal(lateFrames.length, 0, 'Late resolved offer from canceled negotiation must NOT be sent');

  // Phase 3: SDP rejection captures error and exits creating_offer to error state
  MockHookRTCPeerConnection.createOfferHandler = () => Promise.reject(new Error('Simulated SDP hardware error'));
  docWs.simulateServerMessage(
    'MESSAGE\nsubscription:sub-1\ndestination:/topic/video/room/17\n\n{"type":"READY","senderRole":"PATIENT"}\0'
  );
  await new Promise((r) => setTimeout(r, 20));

  const errorState = docHarness.render({ appointmentId: 17, roomDetails });
  assert.equal(errorState.connectionState, 'error', 'Failed offer must transition connectionState to error, not creating_offer');
  assert.match(errorState.error, /Simulated SDP hardware error/);

  // Phase 4: Full recovery from error state upon renegotiation
  MockHookRTCPeerConnection.createOfferHandler = null; // restore normal offer creation
  // Peer rejoins
  docWs.simulateServerMessage(
    'MESSAGE\nsubscription:sub-1\ndestination:/topic/video/room/17\n\n{"type":"LEAVE","senderRole":"PATIENT"}\0'
  );
  await new Promise((r) => setTimeout(r, 20));

  const recoveredLeaveState = docHarness.render({ appointmentId: 17, roomDetails });
  assert.equal(recoveredLeaveState.error, null, 'Error banner must be cleared upon reset');

  // Peer announces presence and re-negotiates
  docWs.simulateServerMessage(
    'MESSAGE\nsubscription:sub-1\ndestination:/topic/video/room/17\n\n{"type":"JOIN","senderRole":"PATIENT"}\0'
  );
  await new Promise((r) => setTimeout(r, 20));

  // Remote ANSWER arrives
  docWs.simulateServerMessage(
    'MESSAGE\nsubscription:sub-1\ndestination:/topic/video/room/17\n\n{"type":"ANSWER","senderRole":"PATIENT","payload":{"type":"answer","sdp":"recovered-answer"}}\0'
  );
  await new Promise((r) => setTimeout(r, 20));

  const activePc = MockHookRTCPeerConnection.instances[MockHookRTCPeerConnection.instances.length - 1];
  activePc.connectionState = 'connected';
  if (activePc.onconnectionstatechange) activePc.onconnectionstatechange();

  const connectedState = docHarness.render({ appointmentId: 17, roomDetails });
  assert.equal(connectedState.connectionState, 'connected', 'Call must reach connected');
  assert.equal(connectedState.error, null, 'Connected call must strictly retain no obsolete error banner');

  docHarness.unmount();
});

test('useWebRTC hook - Doctor re-entry: Patient accepts fresh offer generation 1 from re-entered doctor session', async () => {
  MockHookRTCPeerConnection.instances = [];

  const patRoom = { canJoin: true, userRole: 'PATIENT', userName: 'Patient Alice' };
  const patHarness = createHookHarness();
  patHarness.render({ appointmentId: 17, roomDetails: patRoom });

  await new Promise((r) => setTimeout(r, 50));
  const patWs = patHarness.getLatestSocket();
  patWs.simulateServerMessage('CONNECTED\nversion:1.2\n\n\0');
  await new Promise((r) => setTimeout(r, 20));

  // Doctor session 1: negotiates offer generation 2
  const docSessionId1 = 's_' + (Date.now() - 5000) + '_doc1';
  patWs.simulateServerMessage(
    `MESSAGE\nsubscription:sub-1\ndestination:/topic/video/room/17\n\n${JSON.stringify({
      type: 'OFFER',
      senderRole: 'DOCTOR',
      sessionId: docSessionId1,
      generation: 2,
      payload: { type: 'offer', sdp: 'doc1-gen2-sdp' },
    })}\0`
  );
  await new Promise((r) => setTimeout(r, 20));

  const answersGen2 = patWs.sentFrames.filter((f) => f.includes('"type":"ANSWER"'));
  assert.equal(answersGen2.length, 1, 'Patient answered generation 2 of session 1');

  // Doctor leaves and remounts with fresh session 2 (re-entry)
  const docSessionId2 = 's_' + (Date.now() + 1000) + '_doc2';
  patWs.simulateServerMessage(
    `MESSAGE\nsubscription:sub-1\ndestination:/topic/video/room/17\n\n${JSON.stringify({
      type: 'JOIN',
      senderRole: 'DOCTOR',
      senderName: 'Dr. House',
      sessionId: docSessionId2,
    })}\0`
  );
  await new Promise((r) => setTimeout(r, 20));

  // Remounted doctor sends fresh offer generation 1
  patWs.simulateServerMessage(
    `MESSAGE\nsubscription:sub-1\ndestination:/topic/video/room/17\n\n${JSON.stringify({
      type: 'OFFER',
      senderRole: 'DOCTOR',
      sessionId: docSessionId2,
      generation: 1,
      payload: { type: 'offer', sdp: 'doc2-gen1-sdp' },
    })}\0`
  );
  await new Promise((r) => setTimeout(r, 20));

  // Patient must NOT reject generation 1 from the new session!
  const answersAfter = patWs.sentFrames.filter((f) => f.includes('"type":"ANSWER"'));
  assert.equal(answersAfter.length, 2, 'Patient must answer fresh generation 1 from re-entered doctor');
  const activePc = MockHookRTCPeerConnection.instances[MockHookRTCPeerConnection.instances.length - 1];
  assert.equal(activePc.signalingState, 'stable', 'Patient must reach stable for re-entered doctor');

  patHarness.unmount();
});

test('useWebRTC hook - Obsolete offer cleanup guard does not clear newer in-flight offer guard', async () => {
  MockHookRTCPeerConnection.instances = [];
  let offer1Resolve;
  let offer2Resolve;
  let offerCount = 0;

  MockHookRTCPeerConnection.createOfferHandler = () => {
    offerCount++;
    if (offerCount === 1) {
      return new Promise((resolve) => {
        offer1Resolve = resolve;
      });
    }
    if (offerCount === 2) {
      return new Promise((resolve) => {
        offer2Resolve = resolve;
      });
    }
    return Promise.resolve({ type: 'offer', sdp: `mock-offer-sdp-${offerCount}` });
  };

  const roomDetails = { canJoin: true, userRole: 'DOCTOR', userName: 'Dr. House' };
  const docHarness = createHookHarness();
  docHarness.render({ appointmentId: 17, roomDetails });

  await new Promise((r) => setTimeout(r, 50));
  const docWs = docHarness.getLatestSocket();
  docWs.simulateServerMessage('CONNECTED\nversion:1.2\n\n\0');
  await new Promise((r) => setTimeout(r, 20));

  // Patient sends READY -> Doctor starts Offer 1 (deferred via offer1Resolve)
  docWs.simulateServerMessage(
    'MESSAGE\nsubscription:sub-1\ndestination:/topic/video/room/17\n\n{"type":"READY","senderRole":"PATIENT","sessionId":"s_100_p"}\0'
  );
  await new Promise((r) => setTimeout(r, 20));
  assert.equal(offerCount, 1, 'Offer 1 started');

  // Patient rejoins with JOIN -> Doctor resets connection and starts Offer 2 (deferred via offer2Resolve)
  docWs.simulateServerMessage(
    'MESSAGE\nsubscription:sub-1\ndestination:/topic/video/room/17\n\n{"type":"JOIN","senderRole":"PATIENT","sessionId":"s_200_p"}\0'
  );
  await new Promise((r) => setTimeout(r, 20));
  assert.equal(offerCount, 2, 'Offer 2 started');

  // Now Offer 1 resolves late while Offer 2 is STILL in-flight (awaiting offer2Resolve)
  offer1Resolve({ type: 'offer', sdp: 'stale-offer-1' });
  await new Promise((r) => setTimeout(r, 20));

  // Offer 1's finally MUST NOT clear isMakingOfferRef while Offer 2 is in-flight!
  // Send another READY: Doctor must NOT start a third concurrent offer!
  docWs.simulateServerMessage(
    'MESSAGE\nsubscription:sub-1\ndestination:/topic/video/room/17\n\n{"type":"READY","senderRole":"PATIENT","sessionId":"s_200_p"}\0'
  );
  await new Promise((r) => setTimeout(r, 20));
  assert.equal(offerCount, 2, 'Doctor must not create overlapping Offer 3 while Offer 2 is in-flight');

  // Now resolve Offer 2
  offer2Resolve({ type: 'offer', sdp: 'offer-2' });
  await new Promise((r) => setTimeout(r, 20));

  const offerFrames = docWs.sentFrames.filter((f) => f.includes('"type":"OFFER"'));
  assert.equal(offerFrames.length, 1, 'Only valid Offer 2 emitted');

  MockHookRTCPeerConnection.createOfferHandler = null;
  docHarness.unmount();
});

test('useWebRTC hook - Superseded OFFER ICE drain does not consume replacement generation candidates on closed peer', async () => {
  MockHookRTCPeerConnection.instances = [];
  let candidateAResolve;
  let offer2SdpResolve;
  const processedCandidates = [];

  MockHookRTCPeerConnection.addIceCandidateHandler = (cand, pc) => {
    processedCandidates.push({ candidate: cand.candidate, pcSignalingState: pc.signalingState });
    if (cand.candidate === 'candidate-A') {
      return new Promise((resolve) => {
        candidateAResolve = resolve;
      });
    }
    return Promise.resolve();
  };

  MockHookRTCPeerConnection.setRemoteDescriptionHandler = (desc) => {
    if (desc.sdp === 'offer-2') {
      return new Promise((resolve) => {
        offer2SdpResolve = resolve;
      });
    }
    return Promise.resolve();
  };

  const patRoom = { canJoin: true, userRole: 'PATIENT', userName: 'Patient Alice' };
  const patHarness = createHookHarness();
  patHarness.render({ appointmentId: 17, roomDetails: patRoom });

  await new Promise((r) => setTimeout(r, 50));
  const patWs = patHarness.getLatestSocket();
  patWs.simulateServerMessage('CONNECTED\nversion:1.2\n\n\0');
  await new Promise((r) => setTimeout(r, 20));

  // Queue candidate-A before Offer 1 arrives
  patWs.simulateServerMessage(
    'MESSAGE\nsubscription:sub-1\ndestination:/topic/video/room/17\n\n{"type":"ICE_CANDIDATE","senderRole":"DOCTOR","generation":1,"sessionId":"s_100_d","payload":{"candidate":"candidate-A"}}\0'
  );
  await new Promise((r) => setTimeout(r, 10));

  // Deliver Offer 1 (generation 1)
  patWs.simulateServerMessage(
    'MESSAGE\nsubscription:sub-1\ndestination:/topic/video/room/17\n\n{"type":"OFFER","senderRole":"DOCTOR","generation":1,"sessionId":"s_100_d","payload":{"type":"offer","sdp":"offer-1"}}\0'
  );
  await new Promise((r) => setTimeout(r, 20));

  // Peer 1 has begun draining candidate-A and is paused on candidateAResolve
  assert.equal(processedCandidates.length, 1);
  assert.equal(processedCandidates[0].candidate, 'candidate-A');

  // Now Offer 2 (generation 2) arrives and replaces Peer 1 with Peer 2, but Peer 2 pauses in setRemoteDescription
  patWs.simulateServerMessage(
    'MESSAGE\nsubscription:sub-1\ndestination:/topic/video/room/17\n\n{"type":"OFFER","senderRole":"DOCTOR","generation":2,"sessionId":"s_100_d","payload":{"type":"offer","sdp":"offer-2"}}\0'
  );
  await new Promise((r) => setTimeout(r, 20));

  // Candidate-B arrives while Peer 2 remoteDescription is pending -> candidate-B is queued into pendingCandidates!
  patWs.simulateServerMessage(
    'MESSAGE\nsubscription:sub-1\ndestination:/topic/video/room/17\n\n{"type":"ICE_CANDIDATE","senderRole":"DOCTOR","generation":2,"sessionId":"s_100_d","payload":{"candidate":"candidate-B"}}\0'
  );
  await new Promise((r) => setTimeout(r, 10));

  // Now unblock candidate-A on the superseded Peer 1
  candidateAResolve();
  await new Promise((r) => setTimeout(r, 30));

  // Assert Peer 1 immediately broke out of its drain loop and did NOT consume queued candidate-B on the closed peer!
  const closedPeerCandidates = processedCandidates.filter((c) => c.pcSignalingState === 'closed');
  assert.equal(closedPeerCandidates.length, 0, 'Closed peer must never process candidates from subsequent generations');

  // Now resolve Offer 2's remoteDescription -> Peer 2 drains candidate-B cleanly
  offer2SdpResolve();
  await new Promise((r) => setTimeout(r, 30));

  const peer2Candidates = processedCandidates.filter((c) => c.candidate === 'candidate-B');
  assert.equal(peer2Candidates.length, 1, 'Peer 2 must drain candidate-B');

  MockHookRTCPeerConnection.addIceCandidateHandler = null;
  MockHookRTCPeerConnection.setRemoteDescriptionHandler = null;
  patHarness.unmount();
});

test('useWebRTC hook - Superseded ANSWER ICE drain does not consume replacement generation candidates on closed peer', async () => {
  MockHookRTCPeerConnection.instances = [];
  let candidateAResolve;
  const processedCandidates = [];

  MockHookRTCPeerConnection.addIceCandidateHandler = (cand, pc) => {
    processedCandidates.push({ candidate: cand.candidate, pcSignalingState: pc.signalingState });
    if (cand.candidate === 'candidate-A') {
      return new Promise((resolve) => {
        candidateAResolve = resolve;
      });
    }
    return Promise.resolve();
  };

  const docRoom = { canJoin: true, userRole: 'DOCTOR', userName: 'Dr. House' };
  const docHarness = createHookHarness();
  docHarness.render({ appointmentId: 17, roomDetails: docRoom });

  await new Promise((r) => setTimeout(r, 50));
  const docWs = docHarness.getLatestSocket();
  docWs.simulateServerMessage('CONNECTED\nversion:1.2\n\n\0');
  await new Promise((r) => setTimeout(r, 20));

  // Patient sends READY -> Doctor creates Offer 1
  docWs.simulateServerMessage(
    'MESSAGE\nsubscription:sub-1\ndestination:/topic/video/room/17\n\n{"type":"READY","senderRole":"PATIENT","sessionId":"s_100_p"}\0'
  );
  await new Promise((r) => setTimeout(r, 20));

  // Queue candidate-A before Answer 1 arrives
  docWs.simulateServerMessage(
    'MESSAGE\nsubscription:sub-1\ndestination:/topic/video/room/17\n\n{"type":"ICE_CANDIDATE","senderRole":"PATIENT","generation":1,"sessionId":"s_100_p","payload":{"candidate":"candidate-A"}}\0'
  );
  await new Promise((r) => setTimeout(r, 10));

  // Deliver Answer 1 (generation 1) -> Doctor Peer 1 begins draining candidate-A and pauses
  docWs.simulateServerMessage(
    'MESSAGE\nsubscription:sub-1\ndestination:/topic/video/room/17\n\n{"type":"ANSWER","senderRole":"PATIENT","generation":1,"sessionId":"s_100_p","payload":{"type":"answer","sdp":"answer-1"}}\0'
  );
  await new Promise((r) => setTimeout(r, 20));

  assert.equal(processedCandidates.length, 1);
  assert.equal(processedCandidates[0].candidate, 'candidate-A');

  // Patient rejoins with JOIN -> Doctor resets connection and starts Offer 2
  docWs.simulateServerMessage(
    'MESSAGE\nsubscription:sub-1\ndestination:/topic/video/room/17\n\n{"type":"JOIN","senderRole":"PATIENT","sessionId":"s_200_p"}\0'
  );
  await new Promise((r) => setTimeout(r, 20));

  // Candidate-B (generation 2) arrives before Answer 2 -> queued
  docWs.simulateServerMessage(
    'MESSAGE\nsubscription:sub-1\ndestination:/topic/video/room/17\n\n{"type":"ICE_CANDIDATE","senderRole":"PATIENT","generation":2,"sessionId":"s_200_p","payload":{"candidate":"candidate-B"}}\0'
  );
  await new Promise((r) => setTimeout(r, 10));

  // Now resolve candidate-A on superseded Peer 1
  candidateAResolve();
  await new Promise((r) => setTimeout(r, 30));

  // Assert Peer 1 broke out of ANSWER drain loop and did NOT process candidate-B on closed peer!
  const closedPeerCandidates = processedCandidates.filter((c) => c.pcSignalingState === 'closed');
  assert.equal(closedPeerCandidates.length, 0, 'Closed peer must never process candidates from subsequent generations in ANSWER drain');

  // Deliver Answer 2 (generation 2) -> Doctor Peer 2 drains pendingCandidates including Candidate B
  docWs.simulateServerMessage(
    'MESSAGE\nsubscription:sub-1\ndestination:/topic/video/room/17\n\n{"type":"ANSWER","senderRole":"PATIENT","generation":2,"sessionId":"s_200_p","payload":{"type":"answer","sdp":"answer-2"}}\0'
  );
  await new Promise((r) => setTimeout(r, 30));

  // Assert Candidate B was applied exactly once, and on the active Peer 2 (not on closed Peer 1)
  const candidateBEntries = processedCandidates.filter((c) => c.candidate === 'candidate-B');
  assert.equal(candidateBEntries.length, 1, 'Candidate B must be applied exactly once by replacement peer');
  assert.equal(candidateBEntries[0].pcSignalingState, 'stable', 'Candidate B must be processed on active Peer 2 in stable state');

  MockHookRTCPeerConnection.addIceCandidateHandler = null;
  docHarness.unmount();
});

test('useWebRTC hook - Obsolete direct ICE candidate rejection does not corrupt active replacement connection state', async () => {
  MockHookRTCPeerConnection.instances = [];
  let slowCandReject;

  MockHookRTCPeerConnection.addIceCandidateHandler = (cand) => {
    if (cand.candidate === 'slow-failing-cand') {
      return new Promise((_, reject) => {
        slowCandReject = reject;
      });
    }
    return Promise.resolve();
  };

  const patRoom = { canJoin: true, userRole: 'PATIENT', userName: 'Patient Alice' };
  const patHarness = createHookHarness();
  patHarness.render({ appointmentId: 17, roomDetails: patRoom });

  await new Promise((r) => setTimeout(r, 50));
  const patWs = patHarness.getLatestSocket();
  patWs.simulateServerMessage('CONNECTED\nversion:1.2\n\n\0');
  await new Promise((r) => setTimeout(r, 20));

  // Offer 1 arrives and is answered -> Peer 1 has remoteDescription set
  patWs.simulateServerMessage(
    'MESSAGE\nsubscription:sub-1\ndestination:/topic/video/room/17\n\n{"type":"OFFER","senderRole":"DOCTOR","generation":1,"sessionId":"s_100_d","payload":{"type":"offer","sdp":"offer-1"}}\0'
  );
  await new Promise((r) => setTimeout(r, 20));

  // Direct ICE candidate arrives on Peer 1 and pauses in addIceCandidate
  patWs.simulateServerMessage(
    'MESSAGE\nsubscription:sub-1\ndestination:/topic/video/room/17\n\n{"type":"ICE_CANDIDATE","senderRole":"DOCTOR","generation":1,"sessionId":"s_100_d","payload":{"candidate":"slow-failing-cand"}}\0'
  );
  await new Promise((r) => setTimeout(r, 20));

  // Doctor re-enters with Session 2 -> Peer 1 replaced with Peer 2
  patWs.simulateServerMessage(
    'MESSAGE\nsubscription:sub-1\ndestination:/topic/video/room/17\n\n{"type":"OFFER","senderRole":"DOCTOR","generation":1,"sessionId":"s_200_d","payload":{"type":"offer","sdp":"offer-2"}}\0'
  );
  await new Promise((r) => setTimeout(r, 20));

  const peer2 = MockHookRTCPeerConnection.instances[MockHookRTCPeerConnection.instances.length - 1];
  peer2.connectionState = 'connected';
  if (peer2.onconnectionstatechange) peer2.onconnectionstatechange();
  await new Promise((r) => setTimeout(r, 10));

  // Now the obsolete candidate on closed Peer 1 rejects late
  slowCandReject(new Error('Stale ICE candidate transport failure on closed peer'));
  await new Promise((r) => setTimeout(r, 30));

  // Active call must remain connected and NOT transition to error state
  assert.notEqual(patHarness.getConnectionState(), 'error', 'Replacement connection must not be corrupted by obsolete ICE rejection');

  MockHookRTCPeerConnection.addIceCandidateHandler = null;
  patHarness.unmount();
});

test('useWebRTC hook - Rejected ANSWER with stale generation does not corrupt remoteSessionId', async () => {
  MockHookRTCPeerConnection.instances = [];
  const processedCandidates = [];

  MockHookRTCPeerConnection.addIceCandidateHandler = (cand) => {
    processedCandidates.push(cand.candidate);
    return Promise.resolve();
  };

  const docRoom = { canJoin: true, userRole: 'DOCTOR', userName: 'Dr. House' };
  const docHarness = createHookHarness();
  docHarness.render({ appointmentId: 17, roomDetails: docRoom });

  await new Promise((r) => setTimeout(r, 50));
  const docWs = docHarness.getLatestSocket();
  docWs.simulateServerMessage('CONNECTED\nversion:1.2\n\n\0');
  await new Promise((r) => setTimeout(r, 20));

  // Patient 1 sends READY -> Doctor creates Offer 1
  docWs.simulateServerMessage(
    'MESSAGE\nsubscription:sub-1\ndestination:/topic/video/room/17\n\n{"type":"READY","senderRole":"PATIENT","sessionId":"s_100_p"}\0'
  );
  await new Promise((r) => setTimeout(r, 20));

  // Patient sends Answer 1 (generation 1) -> accepted
  docWs.simulateServerMessage(
    'MESSAGE\nsubscription:sub-1\ndestination:/topic/video/room/17\n\n{"type":"ANSWER","senderRole":"PATIENT","generation":1,"sessionId":"s_100_p","payload":{"type":"answer","sdp":"ans-1"}}\0'
  );
  await new Promise((r) => setTimeout(r, 20));

  // Patient rejoins with Session 2 -> Doctor creates Offer 2 (generation 2, in have-local-offer)
  docWs.simulateServerMessage(
    'MESSAGE\nsubscription:sub-1\ndestination:/topic/video/room/17\n\n{"type":"JOIN","senderRole":"PATIENT","sessionId":"s_200_p"}\0'
  );
  await new Promise((r) => setTimeout(r, 20));

  // Delayed invalid Answer with stale generation 1 arrives from a third tab/bogus session
  docWs.simulateServerMessage(
    'MESSAGE\nsubscription:sub-1\ndestination:/topic/video/room/17\n\n{"type":"ANSWER","senderRole":"PATIENT","generation":1,"sessionId":"s_999_bogus","payload":{"type":"answer","sdp":"stale-ans"}}\0'
  );
  await new Promise((r) => setTimeout(r, 20));

  // Valid Answer 2 arrives from Session 2
  docWs.simulateServerMessage(
    'MESSAGE\nsubscription:sub-1\ndestination:/topic/video/room/17\n\n{"type":"ANSWER","senderRole":"PATIENT","generation":2,"sessionId":"s_200_p","payload":{"type":"answer","sdp":"ans-2"}}\0'
  );
  await new Promise((r) => setTimeout(r, 20));

  // Valid Candidate arrives from Session 2 -> must NOT be rejected as a session mismatch!
  docWs.simulateServerMessage(
    'MESSAGE\nsubscription:sub-1\ndestination:/topic/video/room/17\n\n{"type":"ICE_CANDIDATE","senderRole":"PATIENT","generation":2,"sessionId":"s_200_p","payload":{"candidate":"valid-s2-cand"}}\0'
  );
  await new Promise((r) => setTimeout(r, 20));

  assert.equal(processedCandidates.length, 1, 'Candidate from valid Session 2 must be accepted');
  assert.equal(processedCandidates[0], 'valid-s2-cand');

  MockHookRTCPeerConnection.addIceCandidateHandler = null;
  docHarness.unmount();
});

test('useWebRTC hook - Rejected ANSWER with zero generation does not corrupt remoteSessionId', async () => {
  MockHookRTCPeerConnection.instances = [];
  const processedCandidates = [];

  MockHookRTCPeerConnection.addIceCandidateHandler = (cand) => {
    processedCandidates.push(cand.candidate);
    return Promise.resolve();
  };

  const docRoom = { canJoin: true, userRole: 'DOCTOR', userName: 'Dr. House' };
  const docHarness = createHookHarness();
  docHarness.render({ appointmentId: 17, roomDetails: docRoom });

  await new Promise((r) => setTimeout(r, 50));
  const docWs = docHarness.getLatestSocket();
  docWs.simulateServerMessage('CONNECTED\nversion:1.2\n\n\0');
  await new Promise((r) => setTimeout(r, 20));

  // Patient 1 sends READY -> Doctor creates Offer 1
  docWs.simulateServerMessage(
    'MESSAGE\nsubscription:sub-1\ndestination:/topic/video/room/17\n\n{"type":"READY","senderRole":"PATIENT","sessionId":"s_100_p"}\0'
  );
  await new Promise((r) => setTimeout(r, 20));

  // Patient sends Answer 1 (generation 1) -> accepted
  docWs.simulateServerMessage(
    'MESSAGE\nsubscription:sub-1\ndestination:/topic/video/room/17\n\n{"type":"ANSWER","senderRole":"PATIENT","generation":1,"sessionId":"s_100_p","payload":{"type":"answer","sdp":"ans-1"}}\0'
  );
  await new Promise((r) => setTimeout(r, 20));

  // Patient rejoins with Session 2 -> Doctor creates Offer 2 (generation 2, in have-local-offer)
  docWs.simulateServerMessage(
    'MESSAGE\nsubscription:sub-1\ndestination:/topic/video/room/17\n\n{"type":"JOIN","senderRole":"PATIENT","sessionId":"s_200_p"}\0'
  );
  await new Promise((r) => setTimeout(r, 20));

  // Delayed invalid Answer with zero generation arrives from bogus session
  docWs.simulateServerMessage(
    'MESSAGE\nsubscription:sub-1\ndestination:/topic/video/room/17\n\n{"type":"ANSWER","senderRole":"PATIENT","generation":0,"sessionId":"s_999_bogus","payload":{"type":"answer","sdp":"zero-ans"}}\0'
  );
  await new Promise((r) => setTimeout(r, 20));

  // Valid Answer 2 arrives from Session 2
  docWs.simulateServerMessage(
    'MESSAGE\nsubscription:sub-1\ndestination:/topic/video/room/17\n\n{"type":"ANSWER","senderRole":"PATIENT","generation":2,"sessionId":"s_200_p","payload":{"type":"answer","sdp":"ans-2"}}\0'
  );
  await new Promise((r) => setTimeout(r, 20));

  // Valid Candidate arrives from Session 2 -> must NOT be rejected as a session mismatch!
  docWs.simulateServerMessage(
    'MESSAGE\nsubscription:sub-1\ndestination:/topic/video/room/17\n\n{"type":"ICE_CANDIDATE","senderRole":"PATIENT","generation":2,"sessionId":"s_200_p","payload":{"candidate":"valid-s2-cand"}}\0'
  );
  await new Promise((r) => setTimeout(r, 20));

  assert.equal(processedCandidates.length, 1, 'Candidate from valid Session 2 must be accepted');
  assert.equal(processedCandidates[0], 'valid-s2-cand');

  MockHookRTCPeerConnection.addIceCandidateHandler = null;
  docHarness.unmount();
});

test('useWebRTC hook - Rejected ANSWER with omitted generation does not corrupt remoteSessionId', async () => {
  MockHookRTCPeerConnection.instances = [];
  const processedCandidates = [];

  MockHookRTCPeerConnection.addIceCandidateHandler = (cand) => {
    processedCandidates.push(cand.candidate);
    return Promise.resolve();
  };

  const docRoom = { canJoin: true, userRole: 'DOCTOR', userName: 'Dr. House' };
  const docHarness = createHookHarness();
  docHarness.render({ appointmentId: 17, roomDetails: docRoom });

  await new Promise((r) => setTimeout(r, 50));
  const docWs = docHarness.getLatestSocket();
  docWs.simulateServerMessage('CONNECTED\nversion:1.2\n\n\0');
  await new Promise((r) => setTimeout(r, 20));

  // Patient 1 sends READY -> Doctor creates Offer 1
  docWs.simulateServerMessage(
    'MESSAGE\nsubscription:sub-1\ndestination:/topic/video/room/17\n\n{"type":"READY","senderRole":"PATIENT","sessionId":"s_100_p"}\0'
  );
  await new Promise((r) => setTimeout(r, 20));

  // Patient sends Answer 1 (generation 1) -> accepted
  docWs.simulateServerMessage(
    'MESSAGE\nsubscription:sub-1\ndestination:/topic/video/room/17\n\n{"type":"ANSWER","senderRole":"PATIENT","generation":1,"sessionId":"s_100_p","payload":{"type":"answer","sdp":"ans-1"}}\0'
  );
  await new Promise((r) => setTimeout(r, 20));

  // Patient rejoins with Session 2 -> Doctor creates Offer 2 (generation 2, in have-local-offer)
  docWs.simulateServerMessage(
    'MESSAGE\nsubscription:sub-1\ndestination:/topic/video/room/17\n\n{"type":"JOIN","senderRole":"PATIENT","sessionId":"s_200_p"}\0'
  );
  await new Promise((r) => setTimeout(r, 20));

  // Delayed invalid Answer with OMITTED generation arrives from bogus session
  docWs.simulateServerMessage(
    'MESSAGE\nsubscription:sub-1\ndestination:/topic/video/room/17\n\n{"type":"ANSWER","senderRole":"PATIENT","sessionId":"s_999_bogus","payload":{"type":"answer","sdp":"omitted-ans"}}\0'
  );
  await new Promise((r) => setTimeout(r, 20));

  // Valid Answer 2 arrives from Session 2
  docWs.simulateServerMessage(
    'MESSAGE\nsubscription:sub-1\ndestination:/topic/video/room/17\n\n{"type":"ANSWER","senderRole":"PATIENT","generation":2,"sessionId":"s_200_p","payload":{"type":"answer","sdp":"ans-2"}}\0'
  );
  await new Promise((r) => setTimeout(r, 20));

  // Valid Candidate arrives from Session 2 -> must NOT be rejected as a session mismatch!
  docWs.simulateServerMessage(
    'MESSAGE\nsubscription:sub-1\ndestination:/topic/video/room/17\n\n{"type":"ICE_CANDIDATE","senderRole":"PATIENT","generation":2,"sessionId":"s_200_p","payload":{"candidate":"valid-s2-cand"}}\0'
  );
  await new Promise((r) => setTimeout(r, 20));

  assert.equal(processedCandidates.length, 1, 'Candidate from valid Session 2 must be accepted');
  assert.equal(processedCandidates[0], 'valid-s2-cand');

  MockHookRTCPeerConnection.addIceCandidateHandler = null;
  docHarness.unmount();
});

test('useWebRTC hook - Stale delayed JOIN, LEAVE, and OFFER frames do not reset active session', async () => {
  MockHookRTCPeerConnection.instances = [];
  const patRoom = { canJoin: true, userRole: 'PATIENT', userName: 'Patient Alice' };
  const patHarness = createHookHarness();
  patHarness.render({ appointmentId: 17, roomDetails: patRoom });

  await new Promise((r) => setTimeout(r, 50));
  const patWs = patHarness.getLatestSocket();
  patWs.simulateServerMessage('CONNECTED\nversion:1.2\n\n\0');
  await new Promise((r) => setTimeout(r, 20));

  // Doctor joins with Session 2 (timestamp 2000)
  patWs.simulateServerMessage(
    'MESSAGE\nsubscription:sub-1\ndestination:/topic/video/room/17\n\n{"type":"JOIN","senderRole":"DOCTOR","sessionId":"s_2000_doc2"}\0'
  );
  await new Promise((r) => setTimeout(r, 20));

  // Doctor sends Offer for Session 2 (generation 1)
  patWs.simulateServerMessage(
    'MESSAGE\nsubscription:sub-1\ndestination:/topic/video/room/17\n\n{"type":"OFFER","senderRole":"DOCTOR","generation":1,"sessionId":"s_2000_doc2","payload":{"type":"offer","sdp":"offer-s2"}}\0'
  );
  await new Promise((r) => setTimeout(r, 20));

  const answersS2 = patWs.sentFrames.filter((f) => f.includes('"type":"ANSWER"'));
  assert.equal(answersS2.length, 1, 'Patient answered Session 2 offer');
  const activePc = MockHookRTCPeerConnection.instances[MockHookRTCPeerConnection.instances.length - 1];
  assert.notEqual(activePc.signalingState, 'closed', 'Session 2 peer must remain active');

  // Now a delayed JOIN from Session 1 (timestamp 1000) arrives
  patWs.simulateServerMessage(
    'MESSAGE\nsubscription:sub-1\ndestination:/topic/video/room/17\n\n{"type":"JOIN","senderRole":"DOCTOR","sessionId":"s_1000_doc1"}\0'
  );
  await new Promise((r) => setTimeout(r, 20));
  assert.notEqual(activePc.signalingState, 'closed', 'Delayed S1 JOIN must not close active S2 peer');

  // Now a delayed LEAVE from Session 1 arrives
  patWs.simulateServerMessage(
    'MESSAGE\nsubscription:sub-1\ndestination:/topic/video/room/17\n\n{"type":"LEAVE","senderRole":"DOCTOR","sessionId":"s_1000_doc1"}\0'
  );
  await new Promise((r) => setTimeout(r, 20));
  assert.notEqual(activePc.signalingState, 'closed', 'Delayed S1 LEAVE must not close active S2 peer');

  // Now a delayed OFFER from Session 1 arrives
  patWs.simulateServerMessage(
    'MESSAGE\nsubscription:sub-1\ndestination:/topic/video/room/17\n\n{"type":"OFFER","senderRole":"DOCTOR","generation":1,"sessionId":"s_1000_doc1","payload":{"type":"offer","sdp":"stale-offer-s1"}}\0'
  );
  await new Promise((r) => setTimeout(r, 20));

  const answersAfter = patWs.sentFrames.filter((f) => f.includes('"type":"ANSWER"'));
  assert.equal(answersAfter.length, 1, 'Patient must NOT answer delayed Session 1 offer');
  assert.notEqual(activePc.signalingState, 'closed', 'Session 2 peer must remain open and connected');

  patHarness.unmount();
});

test('useWebRTC hook - Delayed ICE candidate from older session with matching generation is discarded', async () => {
  MockHookRTCPeerConnection.instances = [];
  const processedCandidates = [];

  MockHookRTCPeerConnection.addIceCandidateHandler = (cand, pc) => {
    processedCandidates.push({ candidate: cand.candidate, pcSignalingState: pc.signalingState });
    return Promise.resolve();
  };

  const patRoom = { canJoin: true, userRole: 'PATIENT', userName: 'Patient Alice' };
  const patHarness = createHookHarness();
  patHarness.render({ appointmentId: 17, roomDetails: patRoom });

  await new Promise((r) => setTimeout(r, 50));
  const patWs = patHarness.getLatestSocket();
  patWs.simulateServerMessage('CONNECTED\nversion:1.2\n\n\0');
  await new Promise((r) => setTimeout(r, 20));

  // Doctor joins Session 2 (timestamp 2000) and sends Offer (generation 1)
  patWs.simulateServerMessage(
    'MESSAGE\nsubscription:sub-1\ndestination:/topic/video/room/17\n\n{"type":"JOIN","senderRole":"DOCTOR","sessionId":"s_2000_doc2"}\0'
  );
  await new Promise((r) => setTimeout(r, 20));
  patWs.simulateServerMessage(
    'MESSAGE\nsubscription:sub-1\ndestination:/topic/video/room/17\n\n{"type":"OFFER","senderRole":"DOCTOR","generation":1,"sessionId":"s_2000_doc2","payload":{"type":"offer","sdp":"offer-s2"}}\0'
  );
  await new Promise((r) => setTimeout(r, 20));

  // Delayed ICE candidate from Session 1 (timestamp 1000) arrives with generation 1 (matching generation!)
  patWs.simulateServerMessage(
    'MESSAGE\nsubscription:sub-1\ndestination:/topic/video/room/17\n\n{"type":"ICE_CANDIDATE","senderRole":"DOCTOR","generation":1,"sessionId":"s_1000_doc1","payload":{"candidate":"stale-cand-s1"}}\0'
  );
  await new Promise((r) => setTimeout(r, 20));

  // Legitimate Session 2 candidate arrives
  patWs.simulateServerMessage(
    'MESSAGE\nsubscription:sub-1\ndestination:/topic/video/room/17\n\n{"type":"ICE_CANDIDATE","senderRole":"DOCTOR","generation":1,"sessionId":"s_2000_doc2","payload":{"candidate":"valid-cand-s2"}}\0'
  );
  await new Promise((r) => setTimeout(r, 20));

  assert.equal(processedCandidates.length, 1, 'Only legitimate S2 candidate processed');
  assert.equal(processedCandidates[0].candidate, 'valid-cand-s2');

  MockHookRTCPeerConnection.addIceCandidateHandler = null;
  patHarness.unmount();
});


