import { useState, useEffect, useRef, useCallback } from 'react';
import { SignalingClient } from '../api/signalingClient.js';

function parseSessionTime(sid) {
  return typeof sid === 'string' && sid.startsWith('s_') ? parseInt(sid.split('_')[1], 10) || 0 : 0;
}

export function useWebRTC({ appointmentId, roomDetails, onCallEnded }) {
  const [localStream, setLocalStream] = useState(null);
  const [remoteStream, setRemoteStream] = useState(null);
  const [connectionState, setConnectionState] = useState(
    roomDetails?.canJoin ? 'initializing' : 'locked',
  );
  const [isMuted, setIsMuted] = useState(false);
  const [isVideoOff, setIsVideoOff] = useState(false);
  const [peerMediaState, setPeerMediaState] = useState({ isAudioMuted: false, isVideoOff: false });
  const [error, setError] = useState(null);

  const pcRef = useRef(null);
  const localStreamRef = useRef(null);
  const signalingRef = useRef(null);
  const pendingCandidates = useRef([]);
  const isInitiatorRef = useRef(false);
  const isMakingOfferRef = useRef(false);
  const offerSentRef = useRef(false);
  const roomDetailsRef = useRef(roomDetails);
  const isMutedRef = useRef(false);
  const isVideoOffRef = useRef(false);
  const negotiationGenerationRef = useRef(0);
  const remoteGenerationRef = useRef(0);
  const sessionIdRef = useRef(null);
  const remoteSessionIdRef = useRef(null);

  useEffect(() => {
    roomDetailsRef.current = roomDetails;
  }, [roomDetails]);

  const cleanup = useCallback(() => {
    const details = roomDetailsRef.current;
    if (signalingRef.current) {
      try {
        signalingRef.current.send(`/app/video/signal/${appointmentId}`, {
          type: 'LEAVE',
          senderRole: details?.userRole,
          senderName: details?.userName,
          sessionId: sessionIdRef.current,
        });
      } catch (err) {
        void err;
      }
      signalingRef.current.disconnect();
      signalingRef.current = null;
    }

    if (pcRef.current) {
      pcRef.current.onicecandidate = null;
      pcRef.current.ontrack = null;
      pcRef.current.onconnectionstatechange = null;
      pcRef.current.close();
      pcRef.current = null;
    }

    if (localStreamRef.current) {
      localStreamRef.current.getTracks().forEach((track) => track.stop());
      localStreamRef.current = null;
      setLocalStream(null);
    }
    offerSentRef.current = false;
    isMakingOfferRef.current = false;
    negotiationGenerationRef.current = 0;
    remoteGenerationRef.current = 0;
    remoteSessionIdRef.current = null;
    pendingCandidates.current = [];
    setRemoteStream(null);
    setPeerMediaState({ isAudioMuted: false, isVideoOff: false });
    setError(null);
    setConnectionState('closed');
  }, [appointmentId]);

  const endCall = useCallback(() => {
    cleanup();
    if (onCallEnded) onCallEnded();
  }, [cleanup, onCallEnded]);

  const toggleMute = useCallback(() => {
    const stream = localStreamRef.current;
    if (stream) {
      const audioTracks = stream.getAudioTracks();
      if (audioTracks.length > 0) {
        const nextState = !audioTracks[0].enabled;
        audioTracks[0].enabled = nextState;
        isMutedRef.current = !nextState;
        setIsMuted(!nextState);
        if (signalingRef.current) {
          signalingRef.current.send(`/app/video/signal/${appointmentId}`, {
            type: 'MEDIA_STATE',
            senderRole: roomDetailsRef.current?.userRole,
            payload: { isAudioMuted: !nextState, isVideoOff: isVideoOffRef.current },
          });
        }
      }
    }
  }, [appointmentId]);

  const toggleVideo = useCallback(() => {
    const stream = localStreamRef.current;
    if (stream) {
      const videoTracks = stream.getVideoTracks();
      if (videoTracks.length > 0) {
        const nextState = !videoTracks[0].enabled;
        videoTracks[0].enabled = nextState;
        isVideoOffRef.current = !nextState;
        setIsVideoOff(!nextState);
        if (signalingRef.current) {
          signalingRef.current.send(`/app/video/signal/${appointmentId}`, {
            type: 'MEDIA_STATE',
            senderRole: roomDetailsRef.current?.userRole,
            payload: { isAudioMuted: isMutedRef.current, isVideoOff: !nextState },
          });
        }
      }
    }
  }, [appointmentId]);

  useEffect(() => {
    if (!roomDetails || !roomDetails.canJoin) {
      return;
    }

    // Set initiator based on loaded room details
    isInitiatorRef.current = roomDetails.userRole === 'DOCTOR';
    sessionIdRef.current = `s_${Date.now()}_${Math.random().toString(36).slice(2, 9)}`;

    let isMounted = true;

    async function initCall() {
      try {
        setConnectionState('requesting_media');

        // 1. Acquire camera and microphone
        let stream;
        try {
          stream = await navigator.mediaDevices.getUserMedia({
            audio: true,
            video: { width: { ideal: 1280 }, height: { ideal: 720 } },
          });
        } catch (mediaErr) {
          void mediaErr;
          if (!isMounted) return;
          setError('Camera or microphone permission was denied or device not found.');
          setConnectionState('error');
          return;
        }

        if (!isMounted) {
          stream.getTracks().forEach((t) => t.stop());
          return;
        }

        localStreamRef.current = stream;
        setLocalStream(stream);

        // 2. Peer Connection Lifecycle & Reinitialization Helper
        function setupPeerConnection() {
          if (pcRef.current) {
            pcRef.current.onicecandidate = null;
            pcRef.current.ontrack = null;
            pcRef.current.onconnectionstatechange = null;
            try {
              pcRef.current.close();
            } catch {
              // noop
            }
            pcRef.current = null;
          }

          pendingCandidates.current = [];
          offerSentRef.current = false;
          isMakingOfferRef.current = false;
          setRemoteStream(null);
          setPeerMediaState({ isAudioMuted: false, isVideoOff: false });
          setError(null);

          const iceServers = roomDetails.iceServers && roomDetails.iceServers.length > 0
            ? roomDetails.iceServers
            : [{ urls: 'stun:stun.l.google.com:19302' }];

          const pc = new RTCPeerConnection({ iceServers });
          pcRef.current = pc;

          // Add local media tracks if available
          const activeLocalStream = localStreamRef.current;
          if (activeLocalStream) {
            activeLocalStream.getTracks().forEach((track) => pc.addTrack(track, activeLocalStream));
          }

          // Listen for remote media track
          pc.ontrack = (event) => {
            if (event.streams && event.streams[0]) {
              setRemoteStream(event.streams[0]);
            }
          };

          // Emit local ICE candidates
          pc.onicecandidate = (event) => {
            if (event.candidate && signalingRef.current) {
              signalingRef.current.send(`/app/video/signal/${appointmentId}`, {
                type: 'ICE_CANDIDATE',
                senderRole: roomDetails.userRole,
                generation: isInitiatorRef.current ? negotiationGenerationRef.current : remoteGenerationRef.current,
                sessionId: sessionIdRef.current,
                payload: event.candidate,
              });
            }
          };

          // Track connection state
          pc.onconnectionstatechange = () => {
            if (!isMounted) return;
            const state = pc.connectionState;
            if (state === 'connected') {
              setConnectionState('connected');
              setError(null);
            } else if (state === 'disconnected') {
              setConnectionState('reconnecting');
            } else if (state === 'failed') {
              setConnectionState('failed');
              setError('Peer connection failed. Check your network or firewall.');
            } else if (state === 'closed') {
              setConnectionState('closed');
            }
          };

          return pc;
        }

        setupPeerConnection();

        function sendCurrentMediaState() {
          if (signalingRef.current) {
            signalingRef.current.send(`/app/video/signal/${appointmentId}`, {
              type: 'MEDIA_STATE',
              senderRole: roomDetails.userRole,
              payload: {
                isAudioMuted: isMutedRef.current,
                isVideoOff: isVideoOffRef.current,
              },
            });
          }
        }

        // 3. Helper to create and send WebRTC offer (initiated by Doctor)
        async function createAndSendOffer() {
          let currentPc = pcRef.current;
          if (!currentPc || currentPc.signalingState !== 'stable' || isMakingOfferRef.current || offerSentRef.current) return;
          negotiationGenerationRef.current += 1;
          const targetGen = negotiationGenerationRef.current;
          const targetPc = currentPc;
          try {
            isMakingOfferRef.current = true;
            setConnectionState('creating_offer');
            const offer = await targetPc.createOffer();
            if (!isMounted || pcRef.current !== targetPc || negotiationGenerationRef.current !== targetGen || targetPc.signalingState !== 'stable') return;
            await targetPc.setLocalDescription(offer);
            if (!isMounted || pcRef.current !== targetPc || negotiationGenerationRef.current !== targetGen) return;
            offerSentRef.current = true;
            signalingRef.current?.send(`/app/video/signal/${appointmentId}`, {
              type: 'OFFER',
              senderRole: roomDetails.userRole,
              generation: targetGen,
              sessionId: sessionIdRef.current,
              payload: offer,
            });
          } catch (err) {
            if (!isMounted || pcRef.current !== targetPc || negotiationGenerationRef.current !== targetGen) {
              return;
            }
            setError(err.message || 'Failed to create WebRTC offer');
            setConnectionState('error');
          } finally {
            if (pcRef.current === targetPc && negotiationGenerationRef.current === targetGen) {
              isMakingOfferRef.current = false;
            }
          }
        }

        // 4. Initialize Signaling Client
        setConnectionState('connecting_signaling');
        const signaling = new SignalingClient({
          onConnected: () => {
            if (!isMounted) return;
            setConnectionState('waiting_for_peer');
            // If signaling reconnected after prior session or interrupted negotiation, reset peer connection & offer guards
            if (offerSentRef.current || isMakingOfferRef.current || pcRef.current?.connectionState === 'connected' || pcRef.current?.connectionState === 'disconnected' || pcRef.current?.connectionState === 'failed' || pcRef.current?.signalingState !== 'stable') {
              setupPeerConnection();
            }
            // Announce presence in room
            signaling.send(`/app/video/signal/${appointmentId}`, {
              type: 'JOIN',
              senderRole: roomDetails.userRole,
              senderName: roomDetails.userName,
              sessionId: sessionIdRef.current,
            });
            sendCurrentMediaState();
          },
          onError: (err) => {
            if (!isMounted) return;
            setError(err.message || 'Signaling error');
          },
          onDisconnected: () => {
            if (!isMounted) return;
            if (pcRef.current?.connectionState !== 'connected') {
              setConnectionState('disconnected');
            }
          },
        });
        signalingRef.current = signaling;

        // 5. Subscribe to room signals with reciprocal negotiation
        signaling.subscribe(`/topic/video/room/${appointmentId}`, async (message) => {
          if (!isMounted || !message || message.senderRole === roomDetails.userRole) {
            return; // Ignore own messages
          }

          try {
            const currentPc = pcRef.current;
            if (!currentPc || currentPc.signalingState === 'closed') return;

            const msgType = (message.type || '').toUpperCase();

            if (msgType === 'JOIN') {
              const incomingSessionId = message.sessionId || null;
              const incomingSessionTime = parseSessionTime(incomingSessionId);
              const currentSessionTime = parseSessionTime(remoteSessionIdRef.current);

              // Discard stale JOIN from an older session
              if (remoteSessionIdRef.current && incomingSessionId) {
                if (incomingSessionId !== remoteSessionIdRef.current && incomingSessionTime < currentSessionTime) {
                  return;
                }
              }

              if (isInitiatorRef.current) {
                // Doctor receives JOIN from Patient
                if (incomingSessionId && incomingSessionId !== remoteSessionIdRef.current && incomingSessionTime >= currentSessionTime) {
                  remoteSessionIdRef.current = incomingSessionId;
                  pendingCandidates.current = [];
                }
                const pc = pcRef.current;
                if (!pc || pc.connectionState === 'connected' || pc.connectionState === 'disconnected' || pc.connectionState === 'failed' || pc.signalingState !== 'stable' || offerSentRef.current || isMakingOfferRef.current) {
                  setupPeerConnection();
                }
                await createAndSendOffer();
                sendCurrentMediaState();
              } else {
                // Patient receives JOIN from Doctor
                const isNewSession = Boolean(
                  incomingSessionId && remoteSessionIdRef.current && incomingSessionId !== remoteSessionIdRef.current && incomingSessionTime >= currentSessionTime
                );
                if (isNewSession) {
                  remoteSessionIdRef.current = incomingSessionId;
                  remoteGenerationRef.current = 0;
                  pendingCandidates.current = [];
                  setupPeerConnection();
                } else if (remoteSessionIdRef.current === null && incomingSessionId) {
                  remoteSessionIdRef.current = incomingSessionId;
                }
                signaling.send(`/app/video/signal/${appointmentId}`, {
                  type: 'READY',
                  senderRole: roomDetails.userRole,
                  senderName: roomDetails.userName,
                  sessionId: sessionIdRef.current,
                });
                sendCurrentMediaState();
              }
            } else if (msgType === 'READY') {
              const incomingSessionId = message.sessionId || null;
              const incomingSessionTime = parseSessionTime(incomingSessionId);
              const currentSessionTime = parseSessionTime(remoteSessionIdRef.current);

              if (remoteSessionIdRef.current && incomingSessionId) {
                if (incomingSessionId !== remoteSessionIdRef.current && incomingSessionTime < currentSessionTime) {
                  return; // Discard stale READY from an older session
                }
              }

              // Doctor receives READY from waiting Patient -> if connection was previously active, disconnected, or failed, reset
              if (isInitiatorRef.current) {
                if (incomingSessionId && incomingSessionId !== remoteSessionIdRef.current && incomingSessionTime >= currentSessionTime) {
                  remoteSessionIdRef.current = incomingSessionId;
                  pendingCandidates.current = [];
                }
                const pc = pcRef.current;
                if (!pc || pc.connectionState === 'connected' || pc.connectionState === 'disconnected' || pc.connectionState === 'failed') {
                  setupPeerConnection();
                }
                await createAndSendOffer();
                sendCurrentMediaState();
              }
            } else if (msgType === 'OFFER') {
              if (isInitiatorRef.current) return; // Doctor initiates; ignore incoming offers
              const incomingSessionId = message.sessionId || null;
              const incomingGen = typeof message.generation === 'number' ? message.generation : 0;

              // Parse session timestamps to distinguish re-entry from stale messages across sessions
              const currentSessionTime = parseSessionTime(remoteSessionIdRef.current);
              const incomingSessionTime = parseSessionTime(incomingSessionId);

              const isNewSession = Boolean(
                incomingSessionId && remoteSessionIdRef.current && incomingSessionId !== remoteSessionIdRef.current && incomingSessionTime >= currentSessionTime
              );
              const isStaleSession = Boolean(
                incomingSessionId && remoteSessionIdRef.current && incomingSessionId !== remoteSessionIdRef.current && incomingSessionTime < currentSessionTime
              );

              if (isStaleSession) {
                return; // Discard offer from an older, already-closed session
              }

              if (isNewSession) {
                // Doctor re-entered with a newer session: adopt new session and reset generation
                remoteSessionIdRef.current = incomingSessionId;
                remoteGenerationRef.current = 0;
                pendingCandidates.current = [];
                setupPeerConnection();
              } else if (remoteSessionIdRef.current === null && incomingSessionId) {
                remoteSessionIdRef.current = incomingSessionId;
              }

              // Reject older offers within the same session
              if (remoteGenerationRef.current > 0 && incomingGen <= remoteGenerationRef.current) {
                return;
              }

              // If incoming offer supersedes an active/prior offer or current connection is not stable,
              // cleanly recreate the peer connection.
              if ((remoteGenerationRef.current > 0 && incomingGen > remoteGenerationRef.current) || currentPc.signalingState !== 'stable' || currentPc.remoteDescription) {
                setupPeerConnection();
              }
              const activePc = pcRef.current;
              if (!activePc || activePc.signalingState !== 'stable') return;
              remoteGenerationRef.current = incomingGen;
              const targetGen = incomingGen;
              const targetPc = activePc;
              const targetSessionId = remoteSessionIdRef.current;

              try {
                setConnectionState('received_offer');
                await targetPc.setRemoteDescription(new RTCSessionDescription(message.payload));
                if (!isMounted || pcRef.current !== targetPc || remoteGenerationRef.current !== targetGen || remoteSessionIdRef.current !== targetSessionId) return;

                // Drain any queued candidates with ownership, session, and generation checks
                while (pendingCandidates.current.length > 0) {
                  if (!isMounted || pcRef.current !== targetPc || remoteGenerationRef.current !== targetGen || remoteSessionIdRef.current !== targetSessionId) break;
                  const item = pendingCandidates.current.shift();
                  if (!item) continue;
                  const itemSessionId = item.sessionId || null;
                  const itemGen = typeof item.generation === 'number' ? item.generation : 0;
                  if (itemSessionId && remoteSessionIdRef.current && itemSessionId !== remoteSessionIdRef.current) continue;
                  if (itemGen && itemGen !== targetGen) continue;

                  const candPayload = item.candidate || item;
                  await targetPc.addIceCandidate(new RTCIceCandidate(candPayload));
                  if (!isMounted || pcRef.current !== targetPc || remoteGenerationRef.current !== targetGen || remoteSessionIdRef.current !== targetSessionId) break;
                }
                if (!isMounted || pcRef.current !== targetPc || remoteGenerationRef.current !== targetGen || remoteSessionIdRef.current !== targetSessionId) return;

                const answer = await targetPc.createAnswer();
                if (!isMounted || pcRef.current !== targetPc || remoteGenerationRef.current !== targetGen || remoteSessionIdRef.current !== targetSessionId) return;

                await targetPc.setLocalDescription(answer);
                if (!isMounted || pcRef.current !== targetPc || remoteGenerationRef.current !== targetGen || remoteSessionIdRef.current !== targetSessionId) return;

                signalingRef.current?.send(`/app/video/signal/${appointmentId}`, {
                  type: 'ANSWER',
                  senderRole: roomDetails.userRole,
                  generation: targetGen,
                  sessionId: sessionIdRef.current,
                  payload: answer,
                });
                sendCurrentMediaState();
              } catch (offerErr) {
                if (!isMounted || pcRef.current !== targetPc || remoteGenerationRef.current !== targetGen || remoteSessionIdRef.current !== targetSessionId) {
                  // Obsolete failure from canceled/superseded negotiation
                  return;
                }
                setError(offerErr.message || 'Failed to process WebRTC offer');
                setConnectionState('error');
              }
            } else if (msgType === 'ANSWER') {
              if (!isInitiatorRef.current) return; // Doctor initiates offer and receives answer; patient ignores incoming answer
              const targetPc = currentPc;
              if (targetPc.signalingState !== 'have-local-offer') {
                return; // Not awaiting an answer
              }

              const incomingGen = typeof message.generation === 'number' ? message.generation : 0;
              if (incomingGen !== negotiationGenerationRef.current) {
                return; // Strict generation equality required: discard omitted, zero, or mismatched generations
              }

              const incomingSessionId = message.sessionId || null;
              const incomingSessionTime = parseSessionTime(incomingSessionId);
              const currentSessionTime = parseSessionTime(remoteSessionIdRef.current);

              if (remoteSessionIdRef.current && incomingSessionId) {
                if (incomingSessionId !== remoteSessionIdRef.current && incomingSessionTime < currentSessionTime) {
                  return; // Discard stale answer from an older session
                }
              }

              // Only adopt session ID once negotiation ownership and validity are verified
              if (incomingSessionId && incomingSessionId !== remoteSessionIdRef.current && incomingSessionTime >= currentSessionTime) {
                remoteSessionIdRef.current = incomingSessionId;
              }

              const targetGen = negotiationGenerationRef.current;
              const targetSessionId = remoteSessionIdRef.current;

              try {
                await targetPc.setRemoteDescription(new RTCSessionDescription(message.payload));
                if (!isMounted || pcRef.current !== targetPc || negotiationGenerationRef.current !== targetGen || remoteSessionIdRef.current !== targetSessionId) return;
                while (pendingCandidates.current.length > 0) {
                  if (!isMounted || pcRef.current !== targetPc || negotiationGenerationRef.current !== targetGen || remoteSessionIdRef.current !== targetSessionId) break;
                  const item = pendingCandidates.current.shift();
                  if (!item) continue;
                  const itemSessionId = item.sessionId || null;
                  const itemGen = typeof item.generation === 'number' ? item.generation : 0;
                  if (itemSessionId && remoteSessionIdRef.current && itemSessionId !== remoteSessionIdRef.current) continue;
                  if (itemGen && itemGen !== targetGen) continue;

                  const candPayload = item.candidate || item;
                  await targetPc.addIceCandidate(new RTCIceCandidate(candPayload));
                  if (!isMounted || pcRef.current !== targetPc || negotiationGenerationRef.current !== targetGen || remoteSessionIdRef.current !== targetSessionId) break;
                }
                if (!isMounted || pcRef.current !== targetPc || negotiationGenerationRef.current !== targetGen || remoteSessionIdRef.current !== targetSessionId) return;
                sendCurrentMediaState();
              } catch (ansErr) {
                if (!isMounted || pcRef.current !== targetPc || negotiationGenerationRef.current !== targetGen || remoteSessionIdRef.current !== targetSessionId) return;
                setError(ansErr.message || 'Failed to process WebRTC answer');
                setConnectionState('error');
              }
            } else if (msgType === 'ICE_CANDIDATE') {
              const candSessionId = message.sessionId || null;
              const candSessionTime = parseSessionTime(candSessionId);
              const currentSessionTime = parseSessionTime(remoteSessionIdRef.current);

              // Discard candidate from a different/stale session
              if (remoteSessionIdRef.current && candSessionId) {
                if (candSessionId !== remoteSessionIdRef.current) {
                  return; // Discard candidate from different session
                }
              } else if (candSessionId && candSessionTime < currentSessionTime) {
                return; // Discard candidate older than current session
              }

              const candGen = typeof message.generation === 'number' ? message.generation : 0;
              const expectedGen = isInitiatorRef.current ? negotiationGenerationRef.current : remoteGenerationRef.current;
              if (candGen && expectedGen && candGen !== expectedGen) {
                return; // Discard candidate from stale negotiation generation
              }

              const targetPc = currentPc;
              const targetGen = expectedGen;
              const targetSessionId = remoteSessionIdRef.current;

              if (targetPc.remoteDescription && targetPc.remoteDescription.type) {
                try {
                  await targetPc.addIceCandidate(new RTCIceCandidate(message.payload));
                } catch (candErr) {
                  const currentGen = isInitiatorRef.current ? negotiationGenerationRef.current : remoteGenerationRef.current;
                  if (!isMounted || pcRef.current !== targetPc || currentGen !== targetGen || remoteSessionIdRef.current !== targetSessionId) {
                    // Obsolete failure on a superseded or replaced peer connection
                    return;
                  }
                  void candErr;
                }
              } else {
                pendingCandidates.current.push({
                  candidate: message.payload,
                  sessionId: candSessionId,
                  generation: candGen,
                });
              }
            } else if (msgType === 'MEDIA_STATE') {
              if (message.payload) {
                setPeerMediaState({
                  isAudioMuted: !!message.payload.isAudioMuted,
                  isVideoOff: !!message.payload.isVideoOff,
                });
              }
            } else if (msgType === 'LEAVE') {
              const incomingSessionId = message.sessionId || null;
              const incomingSessionTime = parseSessionTime(incomingSessionId);
              const currentSessionTime = parseSessionTime(remoteSessionIdRef.current);

              if (remoteSessionIdRef.current && incomingSessionId) {
                if (incomingSessionId !== remoteSessionIdRef.current || incomingSessionTime < currentSessionTime) {
                  return; // Discard stale LEAVE from a different or older session
                }
              }
              // Peer left call -> clean reset of peer connection, remote media state, and negotiation session
              remoteSessionIdRef.current = null;
              remoteGenerationRef.current = 0;
              pendingCandidates.current = [];
              setupPeerConnection();
              setConnectionState('peer_left');
            }
          } catch (rtcErr) {
            if (!isMounted) return;
            setError(rtcErr.message || 'WebRTC signaling error');
            setConnectionState('error');
          }
        });

        signaling.connect();
      } catch (err) {
        if (!isMounted) return;
        setError(err.message || 'Failed to initialize video consultation');
        setConnectionState('error');
      }
    }

    initCall();

    return () => {
      isMounted = false;
      cleanup();
    };
  }, [appointmentId, roomDetails, cleanup]);

  const effectiveConnectionState = (roomDetails && !roomDetails.canJoin) ? 'locked' : connectionState;

  return {
    localStream,
    remoteStream,
    connectionState: effectiveConnectionState,
    isMuted,
    isVideoOff,
    peerMediaState,
    error,
    toggleMute,
    toggleVideo,
    endCall,
  };
}
