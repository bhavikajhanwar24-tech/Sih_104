/**
 * Windowed-sinc resampling (low-pass + interpolate/decimate).
 *
 * Naive "take every Nth sample" aliasing would fold high-frequency energy into
 * the 0–8 kHz band and corrupt every acoustic feature downstream. This module
 * always applies an anti-alias FIR before rate conversion.
 */

const DEFAULT_HALF_WIDTH = 32;

/**
 * Hamming-windowed sinc low-pass kernel.
 * @param {number} cutoffNorm  Normalised cutoff (fraction of input Nyquist), (0, 1].
 * @param {number} [halfWidth=32]
 * @returns {Float64Array}
 */
export function designLowpassKernel(cutoffNorm, halfWidth = DEFAULT_HALF_WIDTH) {
  const M = halfWidth * 2;
  const kernel = new Float64Array(M + 1);
  const fc = Math.min(Math.max(cutoffNorm, 1e-6), 1);
  let sum = 0;
  for (let i = 0; i <= M; i += 1) {
    const n = i - halfWidth;
    // Ideal low-pass impulse response: 2*fc * sinc(2*fc*n), fc as fraction of Fs.
    const sinc = n === 0 ? 2 * fc : Math.sin(2 * Math.PI * fc * n) / (Math.PI * n);
    const window = 0.54 - 0.46 * Math.cos((2 * Math.PI * i) / M);
    const tap = sinc * window;
    kernel[i] = tap;
    sum += tap;
  }
  for (let i = 0; i <= M; i += 1) {
    kernel[i] /= sum;
  }
  return kernel;
}

/**
 * Convolve `input` with FIR `kernel` (same length output, zero-padded edges).
 * @param {Float32Array|Float64Array|number[]} input
 * @param {Float64Array|Float32Array} kernel
 * @returns {Float64Array}
 */
function convolve(input, kernel) {
  const n = input.length;
  const m = kernel.length;
  const half = (m - 1) >> 1;
  const out = new Float64Array(n);
  for (let i = 0; i < n; i += 1) {
    let acc = 0;
    for (let k = 0; k < m; k += 1) {
      const j = i + k - half;
      if (j >= 0 && j < n) {
        acc += input[j] * kernel[k];
      }
    }
    out[i] = acc;
  }
  return out;
}

/**
 * Resample mono float PCM from `fromRate` to `toRate` via windowed-sinc FIR.
 *
 * @param {Float32Array|Float64Array|number[]} input
 * @param {number} fromRate
 * @param {number} toRate
 * @returns {Float32Array}
 */
export function resample(input, fromRate, toRate) {
  if (!input || input.length === 0) {
    return new Float32Array(0);
  }
  if (fromRate === toRate) {
    return input instanceof Float32Array ? input.slice() : Float32Array.from(input);
  }
  if (fromRate <= 0 || toRate <= 0) {
    throw new Error(`invalid sample rates: ${fromRate} → ${toRate}`);
  }

  const ratio = toRate / fromRate;
  const outLen = Math.max(1, Math.round(input.length * ratio));
  // Cutoff at the lower Nyquist (anti-alias when downsampling).
  const nyquistIn = fromRate / 2;
  const nyquistOut = toRate / 2;
  const cutoffHz = Math.min(nyquistIn, nyquistOut) * 0.9;
  const cutoffNorm = cutoffHz / fromRate; // as fraction of input sample rate
  const kernel = designLowpassKernel(cutoffNorm, DEFAULT_HALF_WIDTH);
  const filtered = convolve(input, kernel);

  const out = new Float32Array(outLen);
  for (let i = 0; i < outLen; i += 1) {
    const srcPos = i / ratio;
    const i0 = Math.floor(srcPos);
    const i1 = Math.min(i0 + 1, filtered.length - 1);
    const frac = srcPos - i0;
    out[i] = filtered[i0] * (1 - frac) + filtered[i1] * frac;
  }
  return out;
}

/**
 * Peak frequency (Hz) via DFT magnitude on a real signal.
 * Used by unit tests to verify resampling preserves tone frequency.
 *
 * @param {Float32Array|Float64Array|number[]} samples
 * @param {number} sampleRate
 * @returns {number}
 */
export function peakFrequencyHz(samples, sampleRate) {
  const n = samples.length;
  if (n < 4) return 0;
  let bestMag = -1;
  let bestBin = 0;
  // Skip DC; search up to Nyquist.
  const maxBin = Math.floor(n / 2);
  for (let k = 1; k < maxBin; k += 1) {
    let re = 0;
    let im = 0;
    const omega = (2 * Math.PI * k) / n;
    for (let t = 0; t < n; t += 1) {
      const s = samples[t];
      re += s * Math.cos(omega * t);
      im -= s * Math.sin(omega * t);
    }
    const mag = re * re + im * im;
    if (mag > bestMag) {
      bestMag = mag;
      bestBin = k;
    }
  }
  return (bestBin * sampleRate) / n;
}
