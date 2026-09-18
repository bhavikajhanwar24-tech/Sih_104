import { useCallback, useEffect, useRef, useState } from 'react';
import {
  AudioCapture,
  detectBrowserProcessing,
} from '@/audio/AudioCapture.js';

/**
 * @typedef {Object} BrowserProcessingFlags
 * @property {boolean} noiseSuppression
 * @property {boolean} echoCancellation
 * @property {boolean} autoGainControl
 * @property {boolean} anyEnabled
 */

/**
 * Return shape of {@link useAudioCapture} — documented for editor autocomplete.
 *
 * @typedef {Object} AudioCaptureApi
 * @property {() => Promise<void>} start
 * @property {() => void} stop
 * @property {boolean} isCapturing
 * @property {number} level              RMS 0..1 for the live meter
 * @property {string | null} error
 * @property {number} framesSent
 * @property {string | null} warning
 * @property {BrowserProcessingFlags | null} browserProcessing
 */

/**
 * React binding for {@link AudioCapture}.
 *
 * @param {Object} [options]
 * @param {string} [options.sessionId]
 * @param {string} [options.wsUrl]
 * @returns {AudioCaptureApi}
 */
export function useAudioCapture(options = {}) {
  const sessionId = options.sessionId ?? 'browser-dev';
  const wsUrl = options.wsUrl;

  const [isCapturing, setIsCapturing] = useState(false);
  const [level, setLevel] = useState(0);
  const [error, setError] = useState(/** @type {string | null} */ (null));
  const [framesSent, setFramesSent] = useState(0);
  const [warning, setWarning] = useState(/** @type {string | null} */ (null));
  const [browserProcessing, setBrowserProcessing] = useState(
    /** @type {BrowserProcessingFlags | null} */ (null),
  );

  /** @type {React.MutableRefObject<AudioCapture | null>} */
  const captureRef = useRef(null);

  const stop = useCallback(() => {
    if (captureRef.current) {
      captureRef.current.stop();
      captureRef.current = null;
    }
    setIsCapturing(false);
    setLevel(0);
  }, []);

  const start = useCallback(async () => {
    setError(null);
    setWarning(null);
    setFramesSent(0);
    stop();

    const capture = new AudioCapture({
      sessionId,
      wsUrl,
      onLevel: (rms) => setLevel(rms),
      onFrameSent: ({ framesSent: n }) => setFramesSent(n),
      onError: (err) => setError(err.message || String(err)),
      onWarning: (msg) => setWarning(msg),
      onBrowserProcessing: (flags) => setBrowserProcessing(flags),
    });
    captureRef.current = capture;

    try {
      await capture.start();
      setIsCapturing(true);
    } catch (err) {
      const message =
        err instanceof Error ? err.message : 'Failed to start audio capture';
      setError(message);
      setIsCapturing(false);
      captureRef.current = null;
    }
  }, [sessionId, wsUrl, stop]);

  useEffect(() => {
    return () => {
      if (captureRef.current) {
        captureRef.current.stop();
        captureRef.current = null;
      }
    };
  }, []);

  return {
    start,
    stop,
    isCapturing,
    level,
    error,
    framesSent,
    warning,
    browserProcessing,
  };
}

export { detectBrowserProcessing };
