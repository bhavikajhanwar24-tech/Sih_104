#!/usr/bin/env python3
"""Runnable example: pre-transaction gate check."""
import os
import sys

# Allow running without install
sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from sentinelvoice import SentinelVoiceClient


def main() -> None:
    base = os.environ.get("SV_BASE_URL", "http://127.0.0.1:8081")
    key = os.environ.get("SV_API_KEY", "sv_live_REPLACE_ME")
    client = SentinelVoiceClient(base_url=base, api_key=key)
    result = client.create_transaction_request(
        action_type="WIRE_TRANSFER",
        amount_inr=250_000,
        beneficiary_ref="BEN-001",
        requester_employee_ref="E1001",
        channel="CORE_BANKING",
    )
    print(json_dumps(result))


def json_dumps(obj):
    import json

    return json.dumps(obj, indent=2)


if __name__ == "__main__":
    main()
