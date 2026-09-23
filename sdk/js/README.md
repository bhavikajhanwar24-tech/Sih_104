# SentinelVoice JS/TS SDK (F17)

```bash
cd sdk/js
npm install
# or: npm link / copy src into your app
```

## Auth

```js
import { SentinelVoiceClient } from '@sentinelvoice/sdk';

const client = new SentinelVoiceClient({
  baseUrl: 'http://127.0.0.1:8081',
  apiKey: 'sv_live_…',
});
```

## Create transaction request

```js
const result = await client.createTransactionRequest({
  actionType: 'WIRE_TRANSFER',
  amountInr: 250000,
  beneficiaryRef: 'BEN-001',
  channel: 'CORE_BANKING',
});
console.log(result.decision, result.level);
```

## Webhook signature verify

```js
import { verifyWebhookSignature } from '@sentinelvoice/sdk';

const ok = await verifyWebhookSignature({
  secret: 'whsec_…',
  timestampHeader: req.headers['x-sentinelvoice-timestamp'],
  signatureHeader: req.headers['x-sentinelvoice-signature'],
  body: rawBodyString,
});
```

## Poll risk changes

```js
const risk = await client.getSessionRisk(sessionId);
// or subscribe via webhook event `risk.level_changed`
```

## Runnable example

See [`examples/transaction_request.mjs`](examples/transaction_request.mjs).
