/**
 * SentinelVoice Integrations SDK (JavaScript / TypeScript) — F17
 */

/**
 * @typedef {object} ClientOptions
 * @property {string} baseUrl
 * @property {string} apiKey
 * @property {number} [timeoutMs]
 */

export class SentinelVoiceClient {
  /**
   * @param {ClientOptions} opts
   */
  constructor(opts) {
    this.baseUrl = String(opts.baseUrl || '').replace(/\/$/, '');
    this.apiKey = opts.apiKey;
    this.timeoutMs = opts.timeoutMs ?? 30_000;
  }

  /**
   * @param {string} method
   * @param {string} path
   * @param {object} [body]
   */
  async #request(method, path, body) {
    const ctrl = new AbortController();
    const t = setTimeout(() => ctrl.abort(), this.timeoutMs);
    try {
      const res = await fetch(`${this.baseUrl}${path}`, {
        method,
        headers: {
          Authorization: `Bearer ${this.apiKey}`,
          'Content-Type': 'application/json',
          Accept: 'application/json',
        },
        body: body === undefined ? undefined : JSON.stringify(body),
        signal: ctrl.signal,
      });
      const text = await res.text();
      const json = text ? JSON.parse(text) : null;
      if (!res.ok) {
        const err = new Error(json?.message || `HTTP ${res.status}`);
        err.status = res.status;
        err.body = json;
        throw err;
      }
      return json;
    } finally {
      clearTimeout(t);
    }
  }

  /** Pre-transaction gate check → { decision, level, reasons, gateCheckMs } */
  createTransactionRequest(input) {
    return this.#request('POST', '/api/v2/integrations/transactions/request', {
      actionType: input.actionType,
      amountInr: input.amountInr,
      beneficiaryRef: input.beneficiaryRef,
      requesterEmployeeRef: input.requesterEmployeeRef,
      channel: input.channel,
      sessionId: input.sessionId,
      callReference: input.callReference,
    });
  }

  getSessionRisk(sessionId) {
    return this.#request('GET', `/api/v2/integrations/sessions/${encodeURIComponent(sessionId)}/risk`);
  }

  listActiveSessions() {
    return this.#request('GET', '/api/v2/integrations/sessions?active=true');
  }

  ingestCrossChannel(input) {
    return this.#request('POST', '/api/v2/integrations/events/cross-channel', {
      type: input.type,
      identityRef: input.identityRef,
      occurredAt: input.occurredAt,
      attributes: input.attributes,
    });
  }
}

/**
 * Verify X-SentinelVoice-Signature (HMAC-SHA256 over `${timestamp}.${body}`).
 * @param {{ secret: string, timestampHeader: string, signatureHeader: string, body: string|ArrayBuffer|Uint8Array, maxAgeSec?: number }} args
 */
export async function verifyWebhookSignature(args) {
  const { secret, timestampHeader, signatureHeader, body, maxAgeSec = 300 } = args;
  const ts = Number(timestampHeader);
  if (!Number.isFinite(ts)) return false;
  if (Math.abs(Math.floor(Date.now() / 1000) - ts) > maxAgeSec) return false;

  const enc = new TextEncoder();
  const key = await crypto.subtle.importKey(
    'raw',
    enc.encode(secret),
    { name: 'HMAC', hash: 'SHA-256' },
    false,
    ['sign'],
  );
  const raw =
    typeof body === 'string'
      ? enc.encode(`${ts}.${body}`)
      : (() => {
          const prefix = enc.encode(`${ts}.`);
          const bytes = body instanceof Uint8Array ? body : new Uint8Array(body);
          const out = new Uint8Array(prefix.length + bytes.length);
          out.set(prefix, 0);
          out.set(bytes, prefix.length);
          return out;
        })();
  const sig = await crypto.subtle.sign('HMAC', key, raw);
  const hex = [...new Uint8Array(sig)].map((b) => b.toString(16).padStart(2, '0')).join('');
  const expected = `v1=${hex}`;
  return expected === String(signatureHeader || '').trim();
}

export default SentinelVoiceClient;
