/**
 * In-process PCM fan-out — spectrogram (and future meters) tap the same
 * capture stream without a second getUserMedia.
 *
 * Audio stays in-browser; never written to disk or sent to Java.
 */

/** @typedef {(samples: Float32Array, sampleRate: number) => void} PcmListener */

/** @type {Set<PcmListener>} */
const listeners = new Set();

/**
 * @param {PcmListener} fn
 * @returns {() => void} unsubscribe
 */
export function subscribePcm(fn) {
  listeners.add(fn);
  return () => {
    listeners.delete(fn);
  };
}

/**
 * @param {Float32Array} samples  mono float32 [-1,1]
 * @param {number} sampleRate
 */
export function publishPcm(samples, sampleRate) {
  if (!samples || samples.length === 0 || listeners.size === 0) return;
  for (const fn of listeners) {
    try {
      fn(samples, sampleRate);
    } catch {
      /* listener faults must not break capture */
    }
  }
}
