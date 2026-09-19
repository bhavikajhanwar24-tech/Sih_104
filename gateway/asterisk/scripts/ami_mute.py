#!/usr/bin/env python3
"""Mute softphone audio without tearing down Dial (lab hold).

Uses AMI Setvar MUTEAUDIO(all)=on|off inside the Asterisk container.
Host-published :5038 is avoided (Docker Desktop wedges Manager).
"""
from __future__ import annotations

import socket
import subprocess
import sys
import time


def read_msg(sock: socket.socket) -> str:
    data = b""
    while b"\r\n\r\n" not in data:
        chunk = sock.recv(4096)
        if not chunk:
            break
        data += chunk
    return data.split(b"\r\n\r\n", 1)[0].decode("utf-8", "replace")


def ami_session(user: str, secret: str) -> socket.socket:
    sock = socket.create_connection(("127.0.0.1", 5038), 5)
    sock.settimeout(8)
    banner = read_msg(sock)
    if "Asterisk Call Manager" not in banner:
        sock.close()
        raise RuntimeError(f"bad AMI banner: {banner!r}")
    sock.sendall(
        f"Action: Login\r\nUsername: {user}\r\nSecret: {secret}\r\nEvents: off\r\n\r\n".encode()
    )
    login = read_msg(sock)
    if "Success" not in login:
        sock.close()
        raise RuntimeError(f"AMI login failed: {login}")
    return sock


def set_mute(channel: str, state: str, user: str, secret: str) -> str:
    sock = ami_session(user, secret)
    try:
        sock.sendall(
            (
                f"Action: Setvar\r\nChannel: {channel}\r\n"
                f"Variable: MUTEAUDIO(all)\r\nValue: {state}\r\n\r\n"
            ).encode()
        )
        resp = read_msg(sock)
        try:
            sock.sendall(b"Action: Logoff\r\n\r\n")
        except OSError:
            pass
        return resp
    finally:
        try:
            sock.close()
        except OSError:
            pass


def main() -> int:
    if len(sys.argv) < 5:
        print("usage: ami_mute.py <channel> <on|off> <user> <secret>", file=sys.stderr)
        return 1
    channel, state, user, secret = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
    if state not in ("on", "off"):
        print("state must be on|off", file=sys.stderr)
        return 1

    last_err: Exception | None = None
    for attempt in range(3):
        try:
            resp = set_mute(channel, state, user, secret)
            print(resp)
            if "Success" in resp:
                return 0
            last_err = RuntimeError(resp)
        except Exception as exc:  # noqa: BLE001 — lab helper; retry after manager reload
            last_err = exc
            subprocess.run(
                ["asterisk", "-rx", "manager reload"],
                check=False,
                capture_output=True,
            )
            time.sleep(0.8)
    print(f"FAILED after retries: {last_err}", file=sys.stderr)
    return 3


if __name__ == "__main__":
    raise SystemExit(main())
