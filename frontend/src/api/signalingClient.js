/**
 * Lightweight, zero-dependency STOMP-over-WebSocket signaling client for WebRTC.
 */

import { resolveApiBaseUrl } from './axios.js';

export function resolveSignalingUrl(explicitUrl, apiBaseUrl = resolveApiBaseUrl()) {
  if (typeof explicitUrl === 'string' && explicitUrl.trim()) {
    return explicitUrl.trim();
  }
  if (typeof import.meta !== 'undefined' && import.meta.env?.VITE_WS_URL) {
    return import.meta.env.VITE_WS_URL.trim();
  }
  if (typeof apiBaseUrl === 'string' && /^https?:\/\//i.test(apiBaseUrl.trim())) {
    try {
      const url = new URL(apiBaseUrl.trim());
      const wsProtocol = url.protocol === 'https:' ? 'wss:' : 'ws:';
      return `${wsProtocol}//${url.host}/ws/signaling`;
    } catch {
      // fallback to location
    }
  }
  if (typeof window !== 'undefined' && window.location) {
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const isDev = window.location.port === '5173' || window.location.port === '3000';
    const host = isDev ? `${window.location.hostname}:8080` : window.location.host;
    return `${protocol}//${host}/ws/signaling`;
  }
  return 'ws://localhost:8080/ws/signaling';
}

export class SignalingClient {
  constructor({ url, onConnected, onError, onDisconnected }) {
    this.url = resolveSignalingUrl(url);
    this.onConnected = onConnected;
    this.onError = onError;
    this.onDisconnected = onDisconnected;
    this.ws = null;
    this.connected = false;
    this.subscriptions = new Map(); // id -> { destination, callback }
    this.subCounter = 0;
    this.reconnectAttempts = 0;
    this.maxReconnectAttempts = 5;
    this.reconnectTimer = null;
    this.manualClose = false;
  }

  getDefaultUrl() {
    return resolveSignalingUrl();
  }

  connect() {
    this.manualClose = false;
    try {
      this.ws = new WebSocket(this.url);
      this.ws.onopen = () => this.handleOpen();
      this.ws.onmessage = (event) => this.handleMessage(event.data);
      this.ws.onerror = (err) => {
        if (this.onError) this.onError(err);
      };
      this.ws.onclose = () => this.handleClose();
    } catch (err) {
      if (this.onError) this.onError(err);
    }
  }

  handleOpen() {
    // Send STOMP CONNECT frame
    const connectFrame = 'CONNECT\naccept-version:1.2,1.1,1.0\nheart-beat:10000,10000\n\n\0';
    this.ws.send(connectFrame);
  }

  handleMessage(data) {
    if (typeof data !== 'string') return;
    const frames = data.split('\0');
    for (const rawFrame of frames) {
      const trimmed = rawFrame.trim();
      if (!trimmed) continue;

      const lines = trimmed.split('\n');
      const command = lines[0].trim();
      const headers = {};
      let bodyStartIndex = 1;

      for (let i = 1; i < lines.length; i++) {
        const line = lines[i];
        if (line === '' || line === '\r') {
          bodyStartIndex = i + 1;
          break;
        }
        const colonIdx = line.indexOf(':');
        if (colonIdx > 0) {
          const key = line.substring(0, colonIdx).trim();
          const val = line.substring(colonIdx + 1).trim();
          headers[key] = val;
        }
      }

      const body = lines.slice(bodyStartIndex).join('\n');

      if (command === 'CONNECTED') {
        this.connected = true;
        this.reconnectAttempts = 0;
        // Resubscribe to existing subscriptions BEFORE notifying onConnected
        // so listeners are ready before presence announcement
        this.subscriptions.forEach((sub, id) => {
          this.sendSubscribeFrame(id, sub.destination);
        });
        if (this.onConnected) this.onConnected(headers);
      } else if (command === 'MESSAGE') {
        const subId = headers.subscription;
        const sub = this.subscriptions.get(subId);
        if (sub && sub.callback) {
          let parsed = body;
          try {
            parsed = body ? JSON.parse(body) : null;
          } catch {
            // retain raw string
          }
          try {
            const result = sub.callback(parsed, headers);
            if (result && typeof result.then === 'function') {
              result.catch((err) => {
                if (this.onError) this.onError(err);
              });
            }
          } catch (err) {
            if (this.onError) this.onError(err);
          }
        }
      } else if (command === 'ERROR') {
        if (this.onError) this.onError(new Error(headers.message || body));
      }
    }
  }

  sendSubscribeFrame(subId, destination) {
    if (!this.ws || this.ws.readyState !== WebSocket.OPEN) return;
    const frame = `SUBSCRIBE\nid:${subId}\ndestination:${destination}\n\n\0`;
    this.ws.send(frame);
  }

  subscribe(destination, callback) {
    const subId = `sub-${++this.subCounter}`;
    this.subscriptions.set(subId, { destination, callback });
    if (this.connected && this.ws && this.ws.readyState === WebSocket.OPEN) {
      this.sendSubscribeFrame(subId, destination);
    }
    return () => {
      this.unsubscribe(subId);
    };
  }

  unsubscribe(subId) {
    if (this.subscriptions.has(subId)) {
      this.subscriptions.delete(subId);
      if (this.connected && this.ws && this.ws.readyState === WebSocket.OPEN) {
        this.ws.send(`UNSUBSCRIBE\nid:${subId}\n\n\0`);
      }
    }
  }

  send(destination, payload) {
    if (!this.connected || !this.ws || this.ws.readyState !== WebSocket.OPEN) {
      return false;
    }
    const body = typeof payload === 'string' ? payload : JSON.stringify(payload);
    const frame = `SEND\ndestination:${destination}\ncontent-type:application/json\n\n${body}\0`;
    this.ws.send(frame);
    return true;
  }

  handleClose() {
    this.connected = false;
    if (this.onDisconnected) this.onDisconnected();
    if (!this.manualClose && this.reconnectAttempts < this.maxReconnectAttempts) {
      this.reconnectAttempts++;
      const delay = Math.min(1000 * Math.pow(2, this.reconnectAttempts), 10000);
      this.reconnectTimer = setTimeout(() => this.connect(), delay);
    }
  }

  disconnect() {
    this.manualClose = true;
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
    if (this.ws) {
      if (this.connected && this.ws.readyState === WebSocket.OPEN) {
        try {
          this.ws.send('DISCONNECT\n\n\0');
        } catch (err) {
          void err;
        }
      }
      this.ws.close();
      this.ws = null;
    }
    this.connected = false;
    this.subscriptions.clear();
  }
}
