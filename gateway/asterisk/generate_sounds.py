"""Generate 8 kHz mono Asterisk prompt WAVs for SentinelVoice actuation."""
from __future__ import annotations

import math
import os
import struct
import wave

OUT = os.path.join(os.path.dirname(__file__), "sounds")
SR = 8000


def write_wav(path: str, samples: list[float]) -> None:
    with wave.open(path, "w") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        frames = b"".join(
            struct.pack("<h", max(-32767, min(32767, int(s)))) for s in samples
        )
        w.writeframes(frames)


def hold_tone(seconds: float = 4.0) -> list[float]:
    samples: list[float] = []
    n = int(SR * seconds)
    for i in range(n):
        t = i / SR
        f = 440.0 if (i // (SR // 2)) % 2 == 0 else 480.0
        env = 0.35 if (i % SR) < (SR * 0.4) else 0.0
        samples.append(env * 16000 * math.sin(2 * math.pi * f * t))
    return samples


def try_windows_tts(text: str) -> list[float] | None:
    """Best-effort SAPI TTS → 8 kHz mono. Returns None if unavailable."""
    try:
        import tempfile

        import win32com.client  # type: ignore
    except Exception:
        try:
            # Fallback: PowerShell System.Speech via subprocess
            import subprocess
            import tempfile

            raw = os.path.join(tempfile.gettempdir(), "sentinel-whisper-raw.wav")
            ps_exe = os.path.join(
                os.environ.get("SystemRoot", r"C:\Windows"),
                "System32",
                "WindowsPowerShell",
                "v1.0",
                "powershell.exe",
            )
            ps = (
                "Add-Type -AssemblyName System.Speech; "
                "$s = New-Object System.Speech.Synthesis.SpeechSynthesizer; "
                "$s.Rate = -1; $s.Volume = 100; "
                f"$s.SetOutputToWaveFile('{raw}'); "
                f"$s.Speak('{text.replace(chr(39), '')}'); "
                "$s.Dispose()"
            )
            subprocess.run(
                [ps_exe, "-NoProfile", "-Command", ps],
                check=True,
                capture_output=True,
                text=True,
            )
            return resample_wav_to_8k_mono(raw)
        except Exception as exc:
            print("tts_unavailable:", exc)
            return None
    return None


def resample_wav_to_8k_mono(path: str) -> list[float]:
    with wave.open(path, "rb") as r:
        nch, sw, fr, nframes = r.getnchannels(), r.getsampwidth(), r.getframerate(), r.getnframes()
        raw = r.readframes(nframes)
    if sw != 2:
        raise ValueError(f"expected 16-bit wav, got sampwidth={sw}")
    import array

    samples = array.array("h")
    samples.frombytes(raw)
    if nch == 2:
        samples = array.array("h", (samples[i] for i in range(0, len(samples), 2)))
    out: list[float] = []
    target = SR
    for i in range(int(len(samples) * target / fr)):
        src_i = i * fr / target
        i0 = int(src_i)
        i1 = min(i0 + 1, len(samples) - 1)
        f = src_i - i0
        out.append((1 - f) * samples[i0] + f * samples[i1])
    return out


def whisper_fallback() -> list[float]:
    """Audible caution pattern if TTS is unavailable."""
    samples: list[float] = []
    for i in range(int(SR * 3.5)):
        t = i / SR
        word = 1 if (0.15 <= t < 0.55) or (0.7 <= t < 1.3) or (1.5 <= t < 3.2) else 0
        f1 = 700 + 80 * math.sin(2 * math.pi * 3 * t)
        f2 = 1200 + 40 * math.sin(2 * math.pi * 5 * t)
        buzz = 0.45 * math.sin(2 * math.pi * f1 * t) + 0.25 * math.sin(2 * math.pi * f2 * t)
        samples.append(word * 14000 * buzz)
    return samples


def main() -> None:
    os.makedirs(OUT, exist_ok=True)
    write_wav(os.path.join(OUT, "sentinel-hold.wav"), hold_tone())
    text = "Caution: this callers identity could not be verified"
    whisper = try_windows_tts(text) or whisper_fallback()
    write_wav(os.path.join(OUT, "sentinel-whisper-warning.wav"), whisper)
    print("wrote", os.listdir(OUT), "whisper_samples", len(whisper))


if __name__ == "__main__":
    main()
