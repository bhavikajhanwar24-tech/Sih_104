/**
 * Spectrogram FFT worker — keeps Cooley–Tukey off the UI thread.
 */

let fftSize = 512;
let hop = 256;
let sampleRate = 16000;
/** @type {Float32Array} */
let pending = new Float32Array(0);
/** @type {Float32Array | null} */
let windowFn = null;
/** @type {Float32Array | null} */
let real = null;
/** @type {Float32Array | null} */
let imag = null;

/**
 * @param {MessageEvent} ev
 */
self.onmessage = (ev) => {
  const msg = ev.data;
  if (!msg || typeof msg !== 'object') return;

  if (msg.type === 'config') {
    fftSize = msg.fftSize || 512;
    hop = msg.hop || 256;
    sampleRate = msg.sampleRate || 16000;
    windowFn = hann(fftSize);
    real = new Float32Array(fftSize);
    imag = new Float32Array(fftSize);
    pending = new Float32Array(0);
    return;
  }

  if (msg.type === 'pcm') {
    const samples =
      msg.samples instanceof Float32Array
        ? msg.samples
        : new Float32Array(msg.samples);
    pending = concat(pending, samples);
    const half = fftSize / 2;
    const binHz = sampleRate / fftSize;
    const nyquistHz = sampleRate / 2;

    while (pending.length >= fftSize) {
      if (!windowFn || !real || !imag) break;
      for (let i = 0; i < fftSize; i += 1) {
        real[i] = pending[i] * windowFn[i];
        imag[i] = 0;
      }
      fftInPlace(real, imag);

      const magnitudes = new Float32Array(half);
      let peak = 1e-12;
      for (let i = 0; i < half; i += 1) {
        const mag = Math.hypot(real[i], imag[i]);
        magnitudes[i] = mag;
        if (mag > peak) peak = mag;
      }
      // Log-ish normalize for speech dynamics.
      for (let i = 0; i < half; i += 1) {
        const db = 20 * Math.log10(magnitudes[i] / peak + 1e-12);
        magnitudes[i] = Math.max(0, Math.min(1, (db + 80) / 80));
      }

      self.postMessage(
        { type: 'column', magnitudes, binHz, nyquistHz },
        [magnitudes.buffer],
      );
      pending = pending.subarray(hop);
      // Ensure we own a compact buffer (subarray shares).
      if (pending.byteOffset > 0 || pending.length < pending.buffer.byteLength / 4) {
        pending = pending.slice();
      }
    }
  }
};

/**
 * @param {number} n
 * @returns {Float32Array}
 */
function hann(n) {
  const w = new Float32Array(n);
  for (let i = 0; i < n; i += 1) {
    w[i] = 0.5 * (1 - Math.cos((2 * Math.PI * i) / (n - 1)));
  }
  return w;
}

/**
 * @param {Float32Array} a
 * @param {Float32Array} b
 * @returns {Float32Array}
 */
function concat(a, b) {
  if (a.length === 0) return b.slice();
  const out = new Float32Array(a.length + b.length);
  out.set(a, 0);
  out.set(b, a.length);
  return out;
}

/**
 * Radix-2 Cooley–Tukey FFT (in-place).
 * @param {Float32Array} re
 * @param {Float32Array} im
 */
function fftInPlace(re, im) {
  const n = re.length;
  // Bit reversal
  for (let i = 1, j = 0; i < n; i += 1) {
    let bit = n >> 1;
    for (; j & bit; bit >>= 1) j ^= bit;
    j ^= bit;
    if (i < j) {
      const tr = re[i];
      re[i] = re[j];
      re[j] = tr;
      const ti = im[i];
      im[i] = im[j];
      im[j] = ti;
    }
  }
  for (let len = 2; len <= n; len <<= 1) {
    const ang = (-2 * Math.PI) / len;
    const wlenRe = Math.cos(ang);
    const wlenIm = Math.sin(ang);
    for (let i = 0; i < n; i += len) {
      let wRe = 1;
      let wIm = 0;
      for (let j = 0; j < len / 2; j += 1) {
        const uRe = re[i + j];
        const uIm = im[i + j];
        const vRe = re[i + j + len / 2] * wRe - im[i + j + len / 2] * wIm;
        const vIm = re[i + j + len / 2] * wIm + im[i + j + len / 2] * wRe;
        re[i + j] = uRe + vRe;
        im[i + j] = uIm + vIm;
        re[i + j + len / 2] = uRe - vRe;
        im[i + j + len / 2] = uIm - vIm;
        const nextWRe = wRe * wlenRe - wIm * wlenIm;
        wIm = wRe * wlenIm + wIm * wlenRe;
        wRe = nextWRe;
      }
    }
  }
}
