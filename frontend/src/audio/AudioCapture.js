/**
 * Browser mic → AudioWorklet → resample 16 kHz → Int16LE 500 ms frames → ml-engine WS.
 *
 * Wire format (Context §7.1 / ml-engine IngestHello):
 *   first text frame: {sessionId, sampleRate, encoding, channel, channels?, source?}
 *   then binary frames: 8000 samples × 2 bytes = 16000 bytes each
 *
 * Audio NEVER goes to the Java backend — only to ws …/ingest/{sessionId}.
 */

import { AUDIO_ENCODINGS, CHANNEL_PROFILES } from '@/contracts';
import { resample } from './resample.js';
// Force Vite to emit a separate worklet asset (new URL() was not emitted to dist/).
import captureWorkletUrl from './capture-worklet.js?url';

/** @typedef {'pcm_s16le'|'pcm_f32le'|'mulaw'|'alaw'|'slin16'} AudioEncoding */

/**
 * @typedef {Object} CaptureOptions
 * @property {string} sessionId
 * @property {number} [targetRate=16000]
 * @property {(frame: Int16Array) => void} [onFrame]
 * @property {(level: number) => void} [onLevel]
 * @property {(err: Error) => void} [onError]
 * @property {(info: {framesSent: number}) => void} [onFrameSent]
 * @property {(warning: string) => void} [onWarning]
 * @property {(flags: BrowserProcessingFlags) => void} [onBrowserProcessing]
 * @property {string} [wsUrl]  Override; default proxies via /ws/ingest/{sessionId}
 */

/**
 * @typedef {Object} BrowserProcessingFlags
 * @property {boolean} noiseSuppression
 * @property {boolean} echoCancellation
 * @property {boolean} autoGainControl
 * @property {boolean} anyEnabled
 */

const TARGET_RATE = 16000;
const FRAME_SAMPLES = 8000; // 500 ms @ 16 kHz
const FRAME_BYTES = FRAME_SAMPLES * 2;
const RECONNECT_BASE_MS = 500;
const RECONNECT_MAX_MS = 8000;

/**
 * CRITICAL — getUserMedia constraints.
 *
 * Browser noiseSuppression / echoCancellation / autoGainControl are neural
 * audio enhancers. Leaving them ON strips the micro-artifacts SentinelVoice
 * detects AND fabricates new ones, invalidating every acoustic feature.
 * Always request them false, then verify via track.getSettings().
 */
export const CAPTURE_CONSTRAINTS = Object.freeze({
  audio: {
    channelCount: 1,
    echoCancellation: false,
    noiseSuppression: false,
    autoGainControl: false,
    sampleRate: 48000,
  },
  video: false,
});

/**
 * @param {MediaTrackSettings} settings
 * @returns {BrowserProcessingFlags}
 */
export function detectBrowserProcessing(settings) {
  const noiseSuppression = settings.noiseSuppression === true;
  const echoCancellation = settings.echoCancellation === true;
  const autoGainControl = settings.autoGainControl === true;
  return {
    noiseSuppression,
    echoCancellation,
    autoGainControl,
    anyEnabled: noiseSuppression || echoCancellation || autoGainControl,
  };
}

/**
 * Float32 [-1,1] → Int16 LE samples.
 * @param {Float32Array} floatSamples
 * @returns {Int16Array}
 */
export function floatToInt16(floatSamples) {
  const out = new Int16Array(floatSamples.length);
  for (let i = 0; i < floatSamples.length; i += 1) {
    const s = Math.max(-1, Math.min(1, floatSamples[i]));
    out[i] = s < 0 ? Math.round(s * 0x8000) : Math.round(s * 0x7fff);
  }
  return out;
}

/**
 * RMS of a Float32 buffer (for the live input meter).
 * @param {Float32Array|Int16Array} samples
 * @returns {number}
 */
export function rmsLevel(samples) {
  if (!samples || samples.length === 0) return 0;
  let sum = 0;
  if (samples instanceof Int16Array) {
    for (let i = 0; i < samples.length; i += 1) {
      const v = samples[i] / 32768;
      sum += v * v;
    }
  } else {
    for (let i = 0; i < samples.length; i += 1) {
      const v = samples[i];
      sum += v * v;
    }
  }
  return Math.sqrt(sum / samples.length);
}

/**
 * @param {string} sessionId
 * @returns {string}
 */
function defaultWsUrl(sessionId) {
  const proto = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
  return `${proto}//${window.location.host}/ws/ingest/${encodeURIComponent(sessionId)}`;
}

/**
 * Manages AudioContext + mic + WebSocket lifecycle.
 */
export class AudioCapture {
  /**
   * @param {CaptureOptions} options
   */
  constructor(options) {
    if (!options || !options.sessionId) {
      throw new Error('CaptureOptions.sessionId is required');
    }
    this.sessionId = options.sessionId;
    this.targetRate = options.targetRate ?? TARGET_RATE;
    this.onFrame = options.onFrame;
    this.onLevel = options.onLevel;
    this.onError = options.onError;
    this.onFrameSent = options.onFrameSent;
    this.onWarning = options.onWarning;
    this.onBrowserProcessing = options.onBrowserProcessing;
    this.wsUrl = options.wsUrl ?? defaultWsUrl(options.sessionId);

    /** @type {AudioContext | null} */
    this._ctx = null;
    /** @type {MediaStream | null} */
    this._stream = null;
    /** @type {AudioWorkletNode | null} */
    this._worklet = null;
    /** @type {MediaStreamAudioSourceNode | null} */
    this._source = null;
    /** @type {GainNode | null} */
    this._mute = null;
    /** @type {WebSocket | null} */
    this._ws = null;
    /** @type {Float32Array} */
    this._pending = new Float32Array(0);
    this._deviceRate = 48000;
    this._framesSent = 0;
    this._capturing = false;
    this._wantOpen = false;
    this._reconnectAttempt = 0;
    /** @type {ReturnType<typeof setTimeout> | null} */
    this._reconnectTimer = null;
    this._helloSent = false;

    this._onDeviceChange = this._onDeviceChange.bind(this);
    this._onVisibility = this._onVisibility.bind(this);
    this._onContextState = this._onContextState.bind(this);
  }

  /** @returns {number} */
  get framesSent() {
    return this._framesSent;
  }

  /** @returns {boolean} */
  get isCapturing() {
    return this._capturing;
  }

  /**
   * Start mic capture and open the ml-engine ingest WebSocket.
   * @returns {Promise<void>}
   */
  async start() {
    if (this._capturing) return;
    this._wantOpen = true;
    this._framesSent = 0;
    this._pending = new Float32Array(0);

    try {
      this._stream = await navigator.mediaDevices.getUserMedia(CAPTURE_CONSTRAINTS);
    } catch (err) {
      const e =
        err instanceof Error
          ? err
          : new Error('Microphone permission denied or unavailable');
      if (e.name === 'NotAllowedError' || e.name === 'PermissionDeniedError') {
        this._emitError(new Error('Microphone permission denied'));
      } else {
        this._emitError(e);
      }
      this._wantOpen = false;
      throw e;
    }

    const track = this._stream.getAudioTracks()[0];
    if (!track) {
      this._emitError(new Error('No audio track from getUserMedia'));
      this._cleanupMedia();
      this._wantOpen = false;
      throw new Error('No audio track from getUserMedia');
    }

    const settings = track.getSettings();
    const flags = detectBrowserProcessing(settings);
    if (this.onBrowserProcessing) this.onBrowserProcessing(flags);
    if (flags.noiseSuppression) {
      this._warn(
        'Browser noiseSuppression is ON — acoustic features will be corrupted. ' +
          'Disable OS/browser audio enhancements if possible.',
      );
    } else if (flags.anyEnabled) {
      this._warn(
        'Browser audio processing (EC/AGC) still enabled — request constraints were ignored.',
      );
    }

    this._deviceRate = settings.sampleRate || 48000;

    const AudioCtx =
      window.AudioContext ||
      /** @type {typeof AudioContext | undefined} */ (
        /** @type {unknown} */ (window).webkitAudioContext
      );
    if (!AudioCtx) {
      throw new Error('AudioContext is not supported in this browser');
    }
    this._ctx = new AudioCtx({ sampleRate: this._deviceRate });
    this._deviceRate = this._ctx.sampleRate;

    await this._ctx.audioWorklet.addModule(captureWorkletUrl);

    this._source = this._ctx.createMediaStreamSource(this._stream);
    this._worklet = new AudioWorkletNode(this._ctx, 'capture-processor', {
      numberOfInputs: 1,
      numberOfOutputs: 1,
      channelCount: 1,
      outputChannelCount: [1],
    });
    // Mute → destination so the node stays in the rendering graph without playback.
    this._mute = this._ctx.createGain();
    this._mute.gain.value = 0;
    this._worklet.port.onmessage = (ev) => {
      this._onWorkletSamples(/** @type {Float32Array} */ (ev.data));
    };
    this._source.connect(this._worklet);
    this._worklet.connect(this._mute);
    this._mute.connect(this._ctx.destination);

    this._ctx.addEventListener('statechange', this._onContextState);
    document.addEventListener('visibilitychange', this._onVisibility);
    if (navigator.mediaDevices?.addEventListener) {
      navigator.mediaDevices.addEventListener('devicechange', this._onDeviceChange);
    }

    if (this._ctx.state === 'suspended') {
      await this._ctx.resume();
    }

    this._connectWs();
    this._capturing = true;
  }

  /** Stop capture, close WS, release mic. */
  stop() {
    this._wantOpen = false;
    this._capturing = false;
    if (this._reconnectTimer != null) {
      clearTimeout(this._reconnectTimer);
      this._reconnectTimer = null;
    }
    this._closeWs();
    this._cleanupMedia();
    document.removeEventListener('visibilitychange', this._onVisibility);
    if (navigator.mediaDevices?.removeEventListener) {
      navigator.mediaDevices.removeEventListener('devicechange', this._onDeviceChange);
    }
  }

  /** @param {Float32Array} deviceSamples */
  _onWorkletSamples(deviceSamples) {
    if (!this._capturing) return;

    const level = rmsLevel(deviceSamples);
    if (this.onLevel) this.onLevel(level);

    const at16k = resample(deviceSamples, this._deviceRate, this.targetRate);
    this._pending = concatFloat32(this._pending, at16k);

    while (this._pending.length >= FRAME_SAMPLES) {
      const frameFloat = this._pending.subarray(0, FRAME_SAMPLES);
      this._pending = this._pending.slice(FRAME_SAMPLES);
      const int16 = floatToInt16(frameFloat);
      if (this.onFrame) this.onFrame(int16);
      this._sendFrame(int16);
    }
  }

  /** @param {Int16Array} int16 */
  _sendFrame(int16) {
    if (!this._ws || this._ws.readyState !== WebSocket.OPEN || !this._helloSent) {
      return;
    }
    // Int16Array buffer may be a view — send exact PCM bytes.
    const bytes = new Uint8Array(int16.buffer, int16.byteOffset, int16.byteLength);
    if (bytes.byteLength !== FRAME_BYTES) {
      this._warn(`Unexpected frame size ${bytes.byteLength}, expected ${FRAME_BYTES}`);
    }
    this._ws.send(bytes);
    this._framesSent += 1;
    if (this.onFrameSent) this.onFrameSent({ framesSent: this._framesSent });
  }

  _connectWs() {
    this._closeWs();
    this._helloSent = false;
    const ws = new WebSocket(this.wsUrl);
    ws.binaryType = 'arraybuffer';
    this._ws = ws;

    ws.onopen = () => {
      this._reconnectAttempt = 0;
      // Context §7.1 — first message is JSON hello (no audio).
      const hello = {
        sessionId: this.sessionId,
        sampleRate: this.targetRate,
        encoding: AUDIO_ENCODINGS.PCM_S16LE,
        channels: 1,
        channel: CHANNEL_PROFILES.WEBRTC_WIDEBAND,
        source: 'browser',
      };
      ws.send(JSON.stringify(hello));
      this._helloSent = true;
    };

    ws.onclose = () => {
      this._helloSent = false;
      if (this._wantOpen) {
        this._scheduleReconnect();
      }
    };

    ws.onerror = () => {
      this._emitError(new Error('WebSocket error talking to ml-engine ingest'));
    };
  }

  _scheduleReconnect() {
    if (this._reconnectTimer != null) return;
    const delay = Math.min(
      RECONNECT_MAX_MS,
      RECONNECT_BASE_MS * 2 ** this._reconnectAttempt,
    );
    this._reconnectAttempt += 1;
    this._warn(`Ingest WebSocket disconnected — reconnecting in ${delay} ms`);
    this._reconnectTimer = setTimeout(() => {
      this._reconnectTimer = null;
      if (this._wantOpen) this._connectWs();
    }, delay);
  }

  _closeWs() {
    if (this._ws) {
      this._ws.onopen = null;
      this._ws.onclose = null;
      this._ws.onerror = null;
      try {
        if (
          this._ws.readyState === WebSocket.OPEN ||
          this._ws.readyState === WebSocket.CONNECTING
        ) {
          this._ws.close();
        }
      } catch {
        /* ignore */
      }
      this._ws = null;
    }
    this._helloSent = false;
  }

  _cleanupMedia() {
    if (this._mute) {
      try {
        this._mute.disconnect();
      } catch {
        /* ignore */
      }
      this._mute = null;
    }
    if (this._worklet) {
      try {
        this._worklet.port.onmessage = null;
        this._worklet.disconnect();
      } catch {
        /* ignore */
      }
      this._worklet = null;
    }
    if (this._source) {
      try {
        this._source.disconnect();
      } catch {
        /* ignore */
      }
      this._source = null;
    }
    if (this._ctx) {
      this._ctx.removeEventListener('statechange', this._onContextState);
      this._ctx.close().catch(() => {});
      this._ctx = null;
    }
    if (this._stream) {
      this._stream.getTracks().forEach((t) => t.stop());
      this._stream = null;
    }
  }

  _onDeviceChange() {
    if (!this._capturing || !this._stream) return;
    const live = this._stream.getAudioTracks().some((t) => t.readyState === 'live');
    if (!live) {
      this._warn('Audio input device changed or was removed mid-stream');
      this._emitError(new Error('Audio input device lost — stop and start again'));
    }
  }

  _onVisibility() {
    if (document.visibilityState === 'hidden' && this._ctx?.state === 'suspended') {
      this._warn('Tab backgrounded — AudioContext suspended; audio may stall');
    }
  }

  _onContextState() {
    if (this._ctx?.state === 'suspended' && this._capturing) {
      this._warn(
        'AudioContext suspended (tab backgrounded or autoplay policy) — click Start again or focus the tab',
      );
      // Try to resume when user returns.
      if (document.visibilityState === 'visible') {
        this._ctx.resume().catch(() => {});
      }
    }
  }

  /** @param {string} msg */
  _warn(msg) {
    if (this.onWarning) this.onWarning(msg);
  }

  /** @param {Error} err */
  _emitError(err) {
    if (this.onError) this.onError(err);
  }
}

/**
 * @param {Float32Array} a
 * @param {Float32Array} b
 * @returns {Float32Array}
 */
function concatFloat32(a, b) {
  if (a.length === 0) return b.slice();
  if (b.length === 0) return a;
  const out = new Float32Array(a.length + b.length);
  out.set(a, 0);
  out.set(b, a.length);
  return out;
}
