# SentinelVoice Python SDK (F17)

Install (local editable):

```bash
cd sdk/python
pip install -e .
```

Or copy `sentinelvoice/` into your project.

## Auth

```python
from sentinelvoice import SentinelVoiceClient

client = SentinelVoiceClient(
    base_url="http://127.0.0.1:8081",
    api_key="sv_live_…",
)
```

Header used: `Authorization: Bearer sv_live_…` (also accepts `X-API-Key`).

## Create a transaction request (pre-transaction warning)

```python
result = client.create_transaction_request(
    action_type="WIRE_TRANSFER",
    amount_inr=250_000,
    beneficiary_ref="BEN-001",
    channel="CORE_BANKING",
    session_id=None,  # or live call session UUID
)
print(result["decision"], result["level"], result["reasons"])
# decision ∈ ALLOW | CHALLENGE | BLOCK
```

## Poll risk / subscribe via webhook

```python
# Polling
risk = client.get_session_risk("session-uuid")
sessions = client.list_active_sessions()

# Webhooks: configure an endpoint in /app/settings/integrations
# Verify HMAC:
from sentinelvoice import verify_webhook_signature

ok = verify_webhook_signature(
    secret="whsec_…",
    timestamp_header=request.headers["X-SentinelVoice-Timestamp"],
    signature_header=request.headers["X-SentinelVoice-Signature"],
    body=request.get_data(),
)
```

## Runnable example

See [`examples/transaction_request.py`](examples/transaction_request.py).
