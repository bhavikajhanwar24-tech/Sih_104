"""SentinelVoice Integrations SDK (Python) — F17."""

from __future__ import annotations

import hashlib
import hmac
import json
import time
import urllib.error
import urllib.request
from typing import Any, Mapping, Optional


class SentinelVoiceClient:
    def __init__(self, base_url: str, api_key: str, timeout: float = 30.0) -> None:
        self.base_url = base_url.rstrip("/")
        self.api_key = api_key
        self.timeout = timeout

    def _request(self, method: str, path: str, body: Optional[Mapping[str, Any]] = None) -> Any:
        url = f"{self.base_url}{path}"
        data = None if body is None else json.dumps(body).encode("utf-8")
        req = urllib.request.Request(
            url,
            data=data,
            method=method,
            headers={
                "Authorization": f"Bearer {self.api_key}",
                "Content-Type": "application/json",
                "Accept": "application/json",
            },
        )
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as resp:
                raw = resp.read().decode("utf-8")
                return json.loads(raw) if raw else None
        except urllib.error.HTTPError as exc:
            detail = exc.read().decode("utf-8", errors="replace")
            raise RuntimeError(f"HTTP {exc.code}: {detail}") from exc

    def create_transaction_request(
        self,
        *,
        action_type: str,
        amount_inr: float | None = None,
        beneficiary_ref: str | None = None,
        requester_employee_ref: str | None = None,
        channel: str | None = None,
        session_id: str | None = None,
        call_reference: str | None = None,
    ) -> dict[str, Any]:
        payload: dict[str, Any] = {"actionType": action_type}
        if amount_inr is not None:
            payload["amountInr"] = amount_inr
        if beneficiary_ref:
            payload["beneficiaryRef"] = beneficiary_ref
        if requester_employee_ref:
            payload["requesterEmployeeRef"] = requester_employee_ref
        if channel:
            payload["channel"] = channel
        if session_id:
            payload["sessionId"] = session_id
        if call_reference:
            payload["callReference"] = call_reference
        return self._request("POST", "/api/v2/integrations/transactions/request", payload)

    def get_session_risk(self, session_id: str) -> dict[str, Any]:
        return self._request("GET", f"/api/v2/integrations/sessions/{session_id}/risk")

    def list_active_sessions(self) -> dict[str, Any]:
        return self._request("GET", "/api/v2/integrations/sessions?active=true")

    def ingest_cross_channel(
        self,
        *,
        type: str,
        identity_ref: str,
        occurred_at: str | None = None,
        attributes: Mapping[str, Any] | None = None,
    ) -> dict[str, Any]:
        body: dict[str, Any] = {"type": type, "identityRef": identity_ref}
        if occurred_at:
            body["occurredAt"] = occurred_at
        if attributes:
            body["attributes"] = dict(attributes)
        return self._request("POST", "/api/v2/integrations/events/cross-channel", body)


def verify_webhook_signature(
    secret: str,
    timestamp_header: str,
    signature_header: str,
    body: str | bytes,
    *,
    max_age_sec: int = 300,
) -> bool:
    """Verify ``X-SentinelVoice-Signature: v1=<hex>`` with replay window."""
    try:
        ts = int(timestamp_header)
    except (TypeError, ValueError):
        return False
    if abs(int(time.time()) - ts) > max_age_sec:
        return False
    raw = body if isinstance(body, bytes) else body.encode("utf-8")
    signed = f"{ts}.".encode("utf-8") + raw
    digest = hmac.new(secret.encode("utf-8"), signed, hashlib.sha256).hexdigest()
    expected = f"v1={digest}"
    return hmac.compare_digest(expected, (signature_header or "").strip())
