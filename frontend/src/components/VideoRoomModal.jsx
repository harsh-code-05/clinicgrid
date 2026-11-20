import { useEffect, useRef, useState } from 'react';
import api from '../api/axios';
import { useWebRTC } from '../hooks/useWebRTC';
import './VideoRoomModal.css';

export function VideoRoomModal({ appointmentId, onClose }) {
  const [roomDetails, setRoomDetails] = useState(null);
  const [loading, setLoading] = useState(true);
  const [fetchError, setFetchError] = useState(null);

  const localVideoRef = useRef(null);
  const remoteVideoRef = useRef(null);

  useEffect(() => {
    let active = true;
    setRoomDetails(null);
    setFetchError(null);

    async function loadRoom() {
      try {
        setLoading(true);
        const res = await api.get(`/appointments/${appointmentId}/video-room`);
        if (active) {
          if (res.data?.appointmentId && Number(res.data.appointmentId) !== Number(appointmentId)) {
            return;
          }
          setRoomDetails(res.data);
          setFetchError(null);
        }
      } catch (err) {
        if (active) {
          const msg = err.response?.data?.message || err.message || 'Failed to load video consultation room.';
          setFetchError(msg);
        }
      } finally {
        if (active) setLoading(false);
      }
    }

    loadRoom();
    return () => {
      active = false;
      setRoomDetails(null);
      setFetchError(null);
    };
  }, [appointmentId]);

  const {
    localStream,
    remoteStream,
    connectionState,
    isMuted,
    isVideoOff,
    peerMediaState,
    error: rtcError,
    toggleMute,
    toggleVideo,
    endCall,
  } = useWebRTC({
    appointmentId,
    roomDetails,
    onCallEnded: onClose,
  });

  // Attach local stream to video element
  useEffect(() => {
    if (localVideoRef.current && localStream) {
      localVideoRef.current.srcObject = localStream;
    }
  }, [localStream]);

  // Attach remote stream to video element
  useEffect(() => {
    if (remoteVideoRef.current && remoteStream) {
      remoteVideoRef.current.srcObject = remoteStream;
    }
  }, [remoteStream]);

  const renderStatusBadge = () => {
    switch (connectionState) {
      case 'connected':
        return <span className="status-badge connected">● Connected</span>;
      case 'waiting_for_peer':
        return <span className="status-badge waiting">Waiting for {roomDetails?.peerName || 'participant'}...</span>;
      case 'creating_offer':
      case 'received_offer':
      case 'connecting_signaling':
        return <span className="status-badge connecting">Connecting...</span>;
      case 'reconnecting':
        return <span className="status-badge reconnecting">Reconnecting...</span>;
      case 'peer_left':
        return <span className="status-badge left">Peer Left Call</span>;
      case 'failed':
      case 'error':
        return <span className="status-badge error">Connection Failed</span>;
      default:
        return <span className="status-badge idle">Initializing...</span>;
    }
  };

  return (
    <div className="video-modal-backdrop" role="dialog" aria-modal="true">
      <div className="video-modal-content">
        {loading ? (
          <div className="video-loading-pane">
            <button
              type="button"
              className="video-modal-close"
              onClick={onClose}
              aria-label="Close"
              style={{ position: 'absolute', top: '16px', right: '16px', background: 'none', border: 'none', color: '#94a3b8', fontSize: '20px', cursor: 'pointer' }}
            >
              ✕
            </button>
            <div className="spinner" />
            <p>Accessing secure video consultation room #{appointmentId}...</p>
          </div>
        ) : fetchError ? (
          <div className="video-locked-pane">
            <div className="lock-icon">🔒</div>
            <h3>Room Access Denied</h3>
            <p className="error-text">{fetchError}</p>
            <button type="button" className="btn-secondary" onClick={onClose}>
              Close
            </button>
          </div>
        ) : roomDetails?.canJoin !== true ? (
          <div className="video-locked-pane">
            <div className="lock-icon">⚠️</div>
            <h3>Consultation Room Locked</h3>
            <p className="warning-text">
              {roomDetails?.statusMessage || 'Video room access is restricted.'}
            </p>
            {!roomDetails?.doctorVerified && (
              <p className="subtext">
                Doctor verification is required prior to commencing video consultations.
              </p>
            )}
            <button type="button" className="btn-secondary" onClick={onClose}>
              Close
            </button>
          </div>
        ) : (
          <div className="video-room-container">
            {/* Header */}
            <div className="video-header">
              <div className="header-info">
                <h2>Video Consultation #{appointmentId}</h2>
                <span className="participant-names">
                  Speaking with <strong>{roomDetails.peerName}</strong>
                </span>
              </div>
              <div className="header-status">
                {renderStatusBadge()}
                <button type="button" className="btn-close-header" onClick={endCall} aria-label="Close">
                  ✕
                </button>
              </div>
            </div>

            {/* Error banner if media fails */}
            {rtcError && (
              <div className="video-error-banner">
                <span>⚠️ {rtcError}</span>
              </div>
            )}

            {/* Main Video View Area */}
            <div className="video-viewport">
              {/* Remote Video (Main) */}
              <div className="remote-video-wrapper">
                <video
                  ref={remoteVideoRef}
                  autoPlay
                  playsInline
                  className={`remote-video ${(!remoteStream || peerMediaState.isVideoOff) ? 'hidden' : ''}`}
                />
                {(!remoteStream || peerMediaState.isVideoOff) && (
                  <div className="remote-placeholder">
                    <div className="avatar-circle">
                      {roomDetails.peerName ? roomDetails.peerName.charAt(0).toUpperCase() : 'P'}
                    </div>
                    <p className="placeholder-name">{roomDetails.peerName}</p>
                    <p className="placeholder-status">
                      {connectionState === 'connected' && peerMediaState.isVideoOff
                        ? 'Camera is switched off'
                        : connectionState === 'connected'
                          ? 'Streaming audio/video...'
                          : 'Waiting to join room...'}
                    </p>
                  </div>
                )}
              </div>

              {/* Local Video Preview (Picture in Picture) */}
              <div className="local-video-pip">
                <video
                  ref={localVideoRef}
                  autoPlay
                  playsInline
                  muted
                  className={`local-video ${isVideoOff ? 'hidden' : ''}`}
                />
                {isVideoOff && (
                  <div className="local-video-off">
                    <span>Camera Off</span>
                  </div>
                )}
                <span className="pip-label">You ({roomDetails.userRole})</span>
              </div>
            </div>

            {/* Control Bar */}
            <div className="video-controls-bar">
              <button
                type="button"
                className={`control-btn ${isMuted ? 'active-off' : ''}`}
                onClick={toggleMute}
                title={isMuted ? 'Unmute Microphone' : 'Mute Microphone'}
              >
                {isMuted ? '🔇 Unmute' : '🎙️ Mute'}
              </button>

              <button
                type="button"
                className={`control-btn ${isVideoOff ? 'active-off' : ''}`}
                onClick={toggleVideo}
                title={isVideoOff ? 'Turn Camera On' : 'Turn Camera Off'}
              >
                {isVideoOff ? '📷 Start Video' : '📹 Stop Video'}
              </button>

              <button
                type="button"
                className="control-btn btn-end-call"
                onClick={endCall}
                title="End Video Consultation"
              >
                📞 End Call
              </button>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
