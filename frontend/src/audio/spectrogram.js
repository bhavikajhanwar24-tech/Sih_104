/**
 * Browser-side spectrogram — FFT runs in a Web Worker; main thread only paints.
 *
 * Sample rate expected: 16 kHz (capture pipeline). Nyquist = 8 kHz.
 */

export const SPECTROGRAM_FFT_SIZE = 512;
export const SPECTROGRAM_HOP = 256;
export const SPECTROGRAM_SAMPLE_RATE = 16000;

/**
 * Create and manage the FFT worker. Returns a controller.
 *
 * @param {(column: Float32Array, meta: { binHz: number, nyquistHz: number }) => void} onColumn
 * @returns {{ push: (samples: Float32Array, sampleRate: number) => void, dispose: () => void, fps: () => number }}
 */
export function createSpectrogramAnalyzer(onColumn) {
  const worker = new Worker(
    new URL('./spectrogram.worker.js', import.meta.url),
    { type: 'module' },
  );

  let framesDrawn = 0;
  let fpsWindowStart = performance.now();
  let lastFps = 0;

  worker.onmessage = (ev) => {
    const data = ev.data;
    if (!data || data.type !== 'column') return;
    const mags = data.magnitudes;
    const column =
      mags instanceof Float32Array ? mags : new Float32Array(mags);
    framesDrawn += 1;
    const now = performance.now();
    if (now - fpsWindowStart >= 1000) {
      lastFps = framesDrawn;
      framesDrawn = 0;
      fpsWindowStart = now;
    }
    onColumn(column, {
      binHz: data.binHz,
      nyquistHz: data.nyquistHz,
    });
  };

  worker.postMessage({
    type: 'config',
    fftSize: SPECTROGRAM_FFT_SIZE,
    hop: SPECTROGRAM_HOP,
    sampleRate: SPECTROGRAM_SAMPLE_RATE,
  });

  return {
    /**
     * @param {Float32Array} samples
     * @param {number} sampleRate
     */
    push(samples, sampleRate) {
      // Copy — transferable ownership would steal the capture buffer.
      const copy = samples.slice();
      worker.postMessage(
        { type: 'pcm', samples: copy, sampleRate },
        [copy.buffer],
      );
    },
    dispose() {
      worker.terminate();
    },
    fps() {
      return lastFps;
    },
  };
}

/**
 * Map magnitude [0..] to a viridis-ish RGB for canvas ImageData.
 * @param {number} t 0..1
 * @returns {[number, number, number]}
 */
export function magnitudeToRgb(t) {
  const x = Math.max(0, Math.min(1, t));
  // Dark → teal → lime → yellow (readable on dark console, not purple glow).
  if (x < 0.25) {
    const u = x / 0.25;
    return [8 + 20 * u, 12 + 60 * u, 24 + 80 * u];
  }
  if (x < 0.5) {
    const u = (x - 0.25) / 0.25;
    return [28 + 20 * u, 72 + 100 * u, 104 + 40 * u];
  }
  if (x < 0.75) {
    const u = (x - 0.5) / 0.25;
    return [48 + 140 * u, 172 + 50 * u, 144 - 80 * u];
  }
  const u = (x - 0.75) / 0.25;
  return [188 + 60 * u, 222 + 20 * u, 64 + 40 * u];
}
