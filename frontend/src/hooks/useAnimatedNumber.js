import { useEffect, useRef, useState } from 'react';

/**
 * Animate a numeric value toward {@code target} over {@code durationMs} (default 300).
 *
 * @param {number} target
 * @param {number} [durationMs=300]
 * @returns {number}
 */
export function useAnimatedNumber(target, durationMs = 300) {
  const safeTarget = Number.isFinite(target) ? target : 0;
  const [value, setValue] = useState(safeTarget);
  const fromRef = useRef(safeTarget);
  const animRef = useRef(/** @type {number | null} */ (null));

  useEffect(() => {
    const from = fromRef.current;
    const to = safeTarget;
    const started = performance.now();
    if (animRef.current != null) cancelAnimationFrame(animRef.current);

    const tick = (now) => {
      const t = Math.min(1, (now - started) / durationMs);
      const ease = 1 - (1 - t) ** 3;
      const next = from + (to - from) * ease;
      setValue(next);
      fromRef.current = next;
      if (t < 1) {
        animRef.current = requestAnimationFrame(tick);
      } else {
        animRef.current = null;
      }
    };
    animRef.current = requestAnimationFrame(tick);
    return () => {
      if (animRef.current != null) cancelAnimationFrame(animRef.current);
    };
  }, [safeTarget, durationMs]);

  return value;
}
