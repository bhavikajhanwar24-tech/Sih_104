/**
 * AudioWorkletProcessor — accumulates render-quantum (typically 128) samples
 * and posts Float32Array chunks to the main thread.
 *
 * Do NOT use ScriptProcessorNode — it is deprecated and runs on the main thread.
 *
 * Loaded via audioContext.audioWorklet.addModule(...).
 */

const QUANTUM = 128;
/** Post ~20 ms chunks at 48 kHz (≈7–8 quantums) to keep WS path responsive. */
const CHUNK_SAMPLES = QUANTUM * 8;

class CaptureProcessor extends AudioWorkletProcessor {
  constructor() {
    super();
    /** @type {Float32Array} */
    this._buf = new Float32Array(CHUNK_SAMPLES);
    this._filled = 0;
  }

  /**
   * @param {Float32Array[][]} inputs
   * @returns {boolean}
   */
  process(inputs) {
    const input = inputs[0];
    const channel = input && input[0];
    if (!channel || channel.length === 0) {
      return true;
    }

    let offset = 0;
    while (offset < channel.length) {
      const space = CHUNK_SAMPLES - this._filled;
      const take = Math.min(space, channel.length - offset);
      this._buf.set(channel.subarray(offset, offset + take), this._filled);
      this._filled += take;
      offset += take;

      if (this._filled >= CHUNK_SAMPLES) {
        // Transferable copy — avoid sharing the accumulation buffer.
        const chunk = this._buf.slice(0, CHUNK_SAMPLES);
        this.port.postMessage(chunk, [chunk.buffer]);
        this._buf = new Float32Array(CHUNK_SAMPLES);
        this._filled = 0;
      }
    }
    return true;
  }
}

registerProcessor('capture-processor', CaptureProcessor);
