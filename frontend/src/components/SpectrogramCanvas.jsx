import { useEffect, useRef, useState } from 'react';
import PropTypes from 'prop-types';
import { subscribePcm } from '@/audio/pcmBus.js';
import {
  createSpectrogramAnalyzer,
  magnitudeToRgb,
  SPECTROGRAM_SAMPLE_RATE,
} from '@/audio/spectrogram.js';
import { CHANNEL_PROFILES } from '@/contracts';
import { palette } from '@/theme.js';

const FREQ_4K = 4000;
const FREQ_8K = 8000;

/**
 * Scrolling spectrogram waterfall — FFT in a Web Worker; painted on canvas.
 * Uses the same PCM the capture pipeline produces (pcmBus), never over the wire.
 * When PCM is empty (SIP / WAV replay), paints a lightweight energy strip from
 * telemetry family scores — honesty, not a fake spectrogram.
 *
 * @param {Object} props
 * @param {string} [props.channelProfile]
 * @param {import('@/contracts').TelemetryFrame | null | undefined} [props.frame]
 * @param {string} [props.className]
 */
export function SpectrogramCanvas({
  channelProfile = CHANNEL_PROFILES.PSTN_NARROWBAND,
  frame = null,
  className = '',
}) {
  const profile = channelProfile;
  const narrowband = profile === CHANNEL_PROFILES.PSTN_NARROWBAND;

  const canvasRef = useRef(/** @type {HTMLCanvasElement | null} */ (null));
  const overlayRef = useRef(/** @type {HTMLCanvasElement | null} */ (null));
  const energyRef = useRef(/** @type {HTMLCanvasElement | null} */ (null));
  const columnQueue = useRef(/** @type {Float32Array[]} */ ([]));
  const metaRef = useRef({ binHz: SPECTROGRAM_SAMPLE_RATE / 512, nyquistHz: 8000 });
  const [fps, setFps] = useState(0);
  const [hasPcm, setHasPcm] = useState(false);
  const analyzerRef = useRef(/** @type {ReturnType<typeof createSpectrogramAnalyzer> | null} */ (null));

  // Analyzer + PCM subscription
  useEffect(() => {
    const analyzer = createSpectrogramAnalyzer((column, meta) => {
      metaRef.current = meta;
      columnQueue.current.push(column);
      // Bound queue — drop oldest if UI lags
      if (columnQueue.current.length > 120) {
        columnQueue.current.splice(0, columnQueue.current.length - 60);
      }
    });
    analyzerRef.current = analyzer;

    const unsub = subscribePcm((samples, sampleRate) => {
      setHasPcm(true);
      analyzer.push(samples, sampleRate);
    });

    const fpsTimer = setInterval(() => {
      setFps(analyzer.fps());
    }, 1000);

    return () => {
      unsub();
      clearInterval(fpsTimer);
      analyzer.dispose();
      analyzerRef.current = null;
    };
  }, []);

  // Scroll paint loop (~30fps target; columns may arrive faster)
  useEffect(() => {
    let raf = 0;
    let lastPaint = 0;
    const minInterval = 1000 / 30;

    const paint = (now) => {
      raf = requestAnimationFrame(paint);
      if (now - lastPaint < minInterval) return;
      lastPaint = now;

      const canvas = canvasRef.current;
      if (!canvas) return;
      const ctx = canvas.getContext('2d', { alpha: false });
      if (!ctx) return;

      const dpr = window.devicePixelRatio || 1;
      const cssW = canvas.clientWidth || 320;
      const cssH = canvas.clientHeight || 160;
      const w = Math.max(1, Math.floor(cssW * dpr));
      const h = Math.max(1, Math.floor(cssH * dpr));
      if (canvas.width !== w || canvas.height !== h) {
        canvas.width = w;
        canvas.height = h;
        ctx.fillStyle = palette.bg;
        ctx.fillRect(0, 0, w, h);
      }

      const queue = columnQueue.current;
      if (queue.length === 0) return;

      // Draw as many columns as we can without blocking (>20fps budget).
      const budget = Math.min(queue.length, 8);
      for (let c = 0; c < budget; c += 1) {
        const column = queue.shift();
        if (!column) break;
        // Scroll left by 1 device pixel
        ctx.drawImage(canvas, 1, 0, w - 1, h, 0, 0, w - 1, h);
        paintColumn(ctx, w - 1, h, column, metaRef.current.nyquistHz);
      }

      paintOverlay(overlayRef.current, cssW, cssH, dpr, narrowband, metaRef.current.nyquistHz);
    };

    raf = requestAnimationFrame(paint);
    return () => cancelAnimationFrame(raf);
  }, [narrowband]);

  // Repaint overlay when profile flips
  useEffect(() => {
    const canvas = overlayRef.current;
    if (!canvas) return;
    const cssW = canvas.clientWidth || 320;
    const cssH = canvas.clientHeight || 160;
    const dpr = window.devicePixelRatio || 1;
    paintOverlay(canvas, cssW, cssH, dpr, narrowband, metaRef.current.nyquistHz);
  }, [narrowband]);

  // Energy strip from telemetry when browser PCM is absent (SIP / fixture replay)
  useEffect(() => {
    const canvas = energyRef.current;
    if (!canvas || hasPcm) return;
    const dpr = window.devicePixelRatio || 1;
    const cssW = canvas.clientWidth || 320;
    const cssH = canvas.clientHeight || 24;
    const w = Math.max(1, Math.floor(cssW * dpr));
    const h = Math.max(1, Math.floor(cssH * dpr));
    if (canvas.width !== w || canvas.height !== h) {
      canvas.width = w;
      canvas.height = h;
    }
    const ctx = canvas.getContext('2d');
    if (!ctx) return;
    ctx.fillStyle = palette.bg;
    ctx.fillRect(0, 0, w, h);

    const voice = frame?.families?.voice?.score ?? 0;
    const prosody = frame?.families?.prosody?.score ?? 0;
    const channel = frame?.families?.channel?.score ?? 0;
    const energy = Math.min(1, Math.max(0, voice * 0.45 + prosody * 0.35 + channel * 0.2));
    const barW = Math.max(2, Math.floor(w * energy));
    const grad = ctx.createLinearGradient(0, 0, barW, 0);
    grad.addColorStop(0, 'rgba(56, 189, 248, 0.25)');
    grad.addColorStop(1, 'rgba(56, 189, 248, 0.85)');
    ctx.fillStyle = grad;
    ctx.fillRect(0, Math.floor(h * 0.2), barW, Math.floor(h * 0.6));
  }, [frame, hasPcm]);

  const statusLabel = hasPcm
    ? fps > 0
      ? `${fps} fps`
      : 'mic live'
    : frame
      ? 'energy from telemetry (no browser PCM)'
      : 'awaiting mic';

  return (
    <div className={`relative h-full min-h-[8rem] w-full overflow-hidden ${className}`}>
      <canvas
        ref={canvasRef}
        className="absolute inset-0 h-full w-full"
        aria-label="Live spectrogram"
      />
      <canvas
        ref={overlayRef}
        className="pointer-events-none absolute inset-0 h-full w-full"
        aria-hidden
      />
      {!hasPcm ? (
        <canvas
          ref={energyRef}
          className="pointer-events-none absolute bottom-6 left-0 right-0 h-6 w-full"
          aria-hidden
        />
      ) : null}
      <div className="pointer-events-none absolute bottom-1 right-2 font-mono text-[9px] text-sv-muted">
        {statusLabel}
      </div>
    </div>
  );
}

SpectrogramCanvas.propTypes = {
  channelProfile: PropTypes.string,
  frame: PropTypes.object,
  className: PropTypes.string,
};

/**
 * @param {CanvasRenderingContext2D} ctx
 * @param {number} x
 * @param {number} h
 * @param {Float32Array} column
 * @param {number} nyquistHz
 */
function paintColumn(ctx, x, h, column, nyquistHz) {
  const bins = column.length;
  const img = ctx.createImageData(1, h);
  const data = img.data;
  for (let y = 0; y < h; y += 1) {
    // y=0 at top = nyquist, y=h at bottom = 0 Hz
    const frac = 1 - y / (h - 1);
    const bin = Math.min(bins - 1, Math.floor(frac * (bins - 1)));
    const [r, g, b] = magnitudeToRgb(column[bin]);
    const i = y * 4;
    data[i] = r;
    data[i + 1] = g;
    data[i + 2] = b;
    data[i + 3] = 255;
  }
  void nyquistHz;
  ctx.putImageData(img, x, 0);
}

/**
 * @param {HTMLCanvasElement | null} canvas
 * @param {number} cssW
 * @param {number} cssH
 * @param {number} dpr
 * @param {boolean} narrowband
 * @param {number} nyquistHz
 */
function paintOverlay(canvas, cssW, cssH, dpr, narrowband, nyquistHz) {
  if (!canvas) return;
  const w = Math.max(1, Math.floor(cssW * dpr));
  const h = Math.max(1, Math.floor(cssH * dpr));
  if (canvas.width !== w || canvas.height !== h) {
    canvas.width = w;
    canvas.height = h;
  }
  const ctx = canvas.getContext('2d');
  if (!ctx) return;
  ctx.clearRect(0, 0, w, h);

  const maxHz = Math.max(nyquistHz, FREQ_8K);
  const yForHz = (hz) => h * (1 - hz / maxHz);

  // 4 kHz annotation (always)
  drawFreqLine(ctx, w, yForHz(FREQ_4K), '4 kHz', dpr);

  // 8 kHz for wideband
  if (!narrowband && maxHz >= FREQ_8K) {
    drawFreqLine(ctx, w, yForHz(FREQ_8K), '8 kHz', dpr);
  }

  if (narrowband) {
    const y4 = yForHz(FREQ_4K);
    ctx.fillStyle = 'rgba(15, 23, 42, 0.72)';
    ctx.fillRect(0, 0, w, Math.max(0, y4));
    ctx.fillStyle = 'rgba(148, 163, 184, 0.95)';
    ctx.font = `${Math.max(10, 11 * dpr)}px "IBM Plex Mono", monospace`;
    ctx.textAlign = 'center';
    ctx.fillText(
      'No signal above 4 kHz — PSTN narrowband',
      w / 2,
      Math.max(14 * dpr, y4 / 2),
    );
  }
}

/**
 * @param {CanvasRenderingContext2D} ctx
 * @param {number} w
 * @param {number} y
 * @param {string} label
 * @param {number} dpr
 */
function drawFreqLine(ctx, w, y, label, dpr) {
  ctx.strokeStyle = 'rgba(232, 238, 247, 0.45)';
  ctx.lineWidth = Math.max(1, dpr * 0.75);
  ctx.setLineDash([4 * dpr, 3 * dpr]);
  ctx.beginPath();
  ctx.moveTo(0, y);
  ctx.lineTo(w, y);
  ctx.stroke();
  ctx.setLineDash([]);
  ctx.fillStyle = 'rgba(232, 238, 247, 0.75)';
  ctx.font = `${Math.max(9, 9 * dpr)}px "IBM Plex Mono", monospace`;
  ctx.textAlign = 'left';
  ctx.fillText(label, 6 * dpr, y - 3 * dpr);
}
