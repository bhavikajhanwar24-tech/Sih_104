#!/usr/bin/env node
import { SentinelVoiceClient } from '../src/index.js';

const baseUrl = process.env.SV_BASE_URL || 'http://127.0.0.1:8081';
const apiKey = process.env.SV_API_KEY || 'sv_live_REPLACE_ME';

const client = new SentinelVoiceClient({ baseUrl, apiKey });
const result = await client.createTransactionRequest({
  actionType: 'WIRE_TRANSFER',
  amountInr: 250000,
  beneficiaryRef: 'BEN-001',
  requesterEmployeeRef: 'E1001',
  channel: 'CORE_BANKING',
});
console.log(JSON.stringify(result, null, 2));
