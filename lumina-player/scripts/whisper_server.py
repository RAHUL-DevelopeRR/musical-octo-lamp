"""
whisper_server.py — Persistent Faster-Whisper sidecar for LuminaPlayer.

Loads the model ONCE at startup, then processes chunk WAV files on demand
via a simple stdin/stdout JSON-lines protocol.

Protocol:
  Request  (one JSON line on stdin):
    {
      "wav_path": "/tmp/lumina-chunk-0/chunk.wav",
      "model":    "small",          // tiny | base | small | medium | large-v3
      "language": "en",            // ISO code or null for auto-detect
      "beam_size": 3,
      "best_of":   1,
      "quality":   "BALANCED",     // INSTANT | FAST | BALANCED | BEST
      "translate": false
    }

  Response (one JSON line on stdout):
    {
      "segments": [
        {"start": 1.24, "end": 3.80, "text": "Hello world.", "confidence": -0.21}
      ],
      "language": "en",
      "error": null          // non-null string on failure
    }

  Special request:
    {"cmd": "ping"}  -> {"pong": true}
    {"cmd": "exit"}  -> server shuts down
"""

import sys
import os
import json
import logging
import traceback
from typing import Optional

# ── Logging to stderr so it does not pollute the stdout JSON protocol ──────────
logging.basicConfig(
    stream=sys.stderr,
    level=logging.INFO,
    format="%(asctime)s [whisper_server] %(levelname)s %(message)s",
)
log = logging.getLogger("whisper_server")


# ── Quality preset → faster-whisper parameters ────────────────────────────────
QUALITY_PRESETS = {
    "INSTANT":  {"beam_size": 1, "best_of": 1, "temperature": [0.0]},
    "FAST":     {"beam_size": 1, "best_of": 1, "temperature": [0.0, 0.2]},
    "BALANCED": {"beam_size": 3, "best_of": 1, "temperature": [0.0, 0.2, 0.4]},
    "BEST":     {"beam_size": 5, "best_of": 1, "temperature": [0.0, 0.2, 0.4, 0.6, 0.8]},
}


class WhisperServer:
    """
    Persistent server that keeps one WhisperModel loaded for the lifetime of
    the process. Handles model hot-swap if a request asks for a different size.
    """

    def __init__(self):
        self._model = None
        self._loaded_size: Optional[str] = None
        self._compute_type = "int8"   # INT8 quantization — fast on CPU, accurate enough
        self._cpu_threads = max(2, (os.cpu_count() or 4) - 1)  # leave 1 core for UI
        log.info("Server initialised. CPU threads for inference: %d", self._cpu_threads)

    # ── Model management ──────────────────────────────────────────────────────

    def _ensure_model(self, size: str):
        """Load (or reload) the model only when the requested size changes."""
        if self._model is not None and self._loaded_size == size:
            return  # already loaded — no reload cost

        try:
            from faster_whisper import WhisperModel
        except ImportError:
            raise RuntimeError(
                "faster-whisper is not installed. Run: pip install faster-whisper"
            )

        log.info("Loading Faster-Whisper model '%s' (compute=%s, threads=%d) …",
                 size, self._compute_type, self._cpu_threads)
        self._model = WhisperModel(
            size,
            device="cpu",
            compute_type=self._compute_type,
            cpu_threads=self._cpu_threads,
            num_workers=1,
        )
        self._loaded_size = size
        log.info("Model '%s' loaded and ready.", size)

    # ── Transcription ─────────────────────────────────────────────────────────

    def transcribe(self, req: dict) -> dict:
        wav_path  = req["wav_path"]
        model_size = req.get("model", "small")
        language  = req.get("language") or None   # empty string → None (auto)
        quality   = req.get("quality", "BALANCED").upper()
        translate = req.get("translate", False)

        if not os.path.exists(wav_path):
            return {"segments": [], "language": None,
                    "error": f"WAV file not found: {wav_path}"}

        presets = QUALITY_PRESETS.get(quality, QUALITY_PRESETS["BALANCED"])
        beam_size   = req.get("beam_size") or presets["beam_size"]
        best_of     = req.get("best_of")  or presets["best_of"]
        temperature = presets["temperature"]

        try:
            self._ensure_model(model_size)
        except RuntimeError as e:
            return {"segments": [], "language": None, "error": str(e)}

        task = "translate" if translate else "transcribe"

        try:
            segments_iter, info = self._model.transcribe(
                wav_path,
                language=language,
                task=task,
                beam_size=beam_size,
                best_of=best_of,
                temperature=temperature,
                # ── THE KEY ACCURACY FIX ──────────────────────────────────────
                # Without this, Whisper conditions each chunk on the previous
                # chunk's decoder state → hallucinations at chunk boundaries.
                condition_on_previous_text=False,
                # ─────────────────────────────────────────────────────────────
                vad_filter=False,           # LuminaPlayer's SimpleVAD handles this
                word_timestamps=True,       # word-level boundaries for precise cues
                no_speech_threshold=0.6,
                log_prob_threshold=-1.0,
                compression_ratio_threshold=2.4,
            )

            segments_out = []
            for seg in segments_iter:
                text = seg.text.strip()
                if not text:
                    continue

                # Use word-level timestamps when available (more precise)
                if seg.words:
                    start = seg.words[0].start
                    end   = seg.words[-1].end
                else:
                    start = seg.start
                    end   = seg.end

                segments_out.append({
                    "start":      start,
                    "end":        end,
                    "text":       text,
                    "confidence": seg.avg_logprob,
                })

            return {
                "segments": segments_out,
                "language": info.language,
                "error":    None,
            }

        except Exception as e:  # noqa: BLE001
            log.error("Transcription error: %s", traceback.format_exc())
            return {"segments": [], "language": None, "error": str(e)}

    # ── Main loop ─────────────────────────────────────────────────────────────

    def run(self):
        """Read JSON-line requests from stdin, write JSON-line responses to stdout."""
        log.info("whisper_server ready — listening on stdin.")

        # Use binary-mode stdin/stdout and decode manually for cross-platform
        # UTF-8 safety (Windows console may default to cp1252 otherwise).
        stdin  = open(sys.stdin.fileno(),  "rb", closefd=False)
        stdout = open(sys.stdout.fileno(), "wb", closefd=False)

        for raw_line in stdin:
            line = raw_line.decode("utf-8", errors="replace").strip()
            if not line:
                continue

            try:
                req = json.loads(line)
            except json.JSONDecodeError as e:
                resp = {"error": f"JSON parse error: {e}"}
                self._write(stdout, resp)
                continue

            # Control commands
            cmd = req.get("cmd")
            if cmd == "ping":
                self._write(stdout, {"pong": True})
                continue
            if cmd == "exit":
                log.info("Received exit command — shutting down.")
                self._write(stdout, {"bye": True})
                break

            # Transcription request
            resp = self.transcribe(req)
            self._write(stdout, resp)

        log.info("whisper_server exiting.")

    @staticmethod
    def _write(stdout, obj: dict):
        line = json.dumps(obj, ensure_ascii=False) + "\n"
        stdout.write(line.encode("utf-8"))
        stdout.flush()


if __name__ == "__main__":
    WhisperServer().run()
