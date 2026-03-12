"""
whisper_server.py — Precision Sidecar for LuminaPlayer
=======================================================
Full merge of forensic_subtitle.py best-practices into the persistent sidecar.

Pipeline per chunk:
  Stage 1  Audio I/O         — soundfile read into float32 numpy array
  Stage 2  Silero VAD        — splits into speech / non-speech regions
  Stage 3  Faster-Whisper    — INT8, per-region, word-timestamps,
                               condition_on_previous_text=True within region
  Stage 4  End-time fix      — waveform RMS trailing-window correction
  Stage 5  Drift correction  — linear-regression over VAD vs ASR boundaries
  Stage 6  Alignment refine  — ±50ms phoneme onset/offset snap (2ms precision)
  Stage 7  YAMNet SED        — event captions on non-speech regions (optional)
  Stage 8  Validation        — overlap fix, early-termination fix, confidence gate
  Cache    LRU 256 entries   — instant re-runs

Protocol (JSON-lines on stdin/stdout)
──────────────────────────────────────
Request:
  {
    "wav_path":        "/tmp/lumina/chunk.wav",
    "model":           "small",         // tiny|base|small|medium|large-v3
    "language":        "en",            // ISO code or null/empty for auto
    "quality":         "BALANCED",      // INSTANT|FAST|BALANCED|BEST
    "translate":       false,
    "use_vad":         true,            // Silero VAD pre-filter (default true)
    "sound_events":    false,           // YAMNet SED captions (default false)
    "max_cpu_percent": 75,              // CPU ceiling 0-100 (default 100)
    "cache_key":       "sha256hex"      // skip if cache hit
  }

Response:
  {
    "segments": [
      {"start":1.24,"end":3.80,"text":"Hello world.","type":"speech","confidence":-0.21},
      {"start":3.90,"end":4.50,"text":"(Applause)","type":"sound_event","confidence":0.87}
    ],
    "language":       "en",
    "from_cache":     false,
    "alignment_info": {"overall_confidence":94.2,"drift_ms":1.2,"refined":12,"overlaps_fixed":0},
    "error":          null
  }

Control commands:
  {"cmd":"ping"}        → {"pong":true,"vad_available":bool,"sed_available":bool}
  {"cmd":"exit"}        → {"bye":true}
  {"cmd":"cache_clear"} → {"cleared":true}
"""

import sys
import os
import json
import time
import logging
import threading
import traceback
from pathlib import Path
from typing import Optional, List, Dict, Tuple

# ── Logging to stderr only (never pollutes stdout JSON protocol) ─────────────
logging.basicConfig(
    stream=sys.stderr,
    level=logging.INFO,
    format="%(asctime)s [lumina-sidecar] %(levelname)s %(message)s",
    datefmt="%H:%M:%S",
)
log = logging.getLogger("lumina_sidecar")

SAMPLE_RATE = 16_000

# ─────────────────────────────────────────────────────────────────────────────
# Quality presets
# ─────────────────────────────────────────────────────────────────────────────
QUALITY_PRESETS = {
    "INSTANT":  {"beam_size": 1, "best_of": 1, "temperature": [0.0]},
    "FAST":     {"beam_size": 1, "best_of": 1, "temperature": [0.0, 0.2]},
    "BALANCED": {"beam_size": 3, "best_of": 1, "temperature": [0.0, 0.2, 0.4]},
    "BEST":     {"beam_size": 5, "best_of": 1, "temperature": [0.0, 0.2, 0.4, 0.6, 0.8]},
}

# Sound event label → human-readable caption
SED_LABEL_MAP = {
    "Applause": "(Applause)", "Cheering": "(Cheering)", "Laughter": "(Laughter)",
    "Music": "(Music playing)", "Singing": "(Singing)", "Dog": "(Dog barking)",
    "Cat": "(Cat meowing)", "Gunshot, gunfire": "(Gunshot)", "Explosion": "(Explosion)",
    "Glass": "(Glass breaking)", "Door": "(Door)", "Knock": "(Knocking)",
    "Telephone": "(Phone ringing)", "Alarm": "(Alarm)", "Siren": "(Siren)",
    "Car": "(Car)", "Engine": "(Engine)", "Rain": "(Rain)", "Thunder": "(Thunder)",
    "Wind": "(Wind)", "Fire": "(Fire crackling)", "Water": "(Water)",
    "Crowd": "(Crowd noise)", "Baby cry, infant cry": "(Baby crying)",
    "Crying, sobbing": "(Crying)", "Screaming": "(Screaming)",
    "Clapping": "(Clapping)", "Whistling": "(Whistling)",
    "Footsteps": "(Footsteps)", "Snoring": "(Snoring)", "Coughing": "(Coughing)",
}
SED_EXCLUDED = {"Speech", "Narration, monologue", "Conversation", "Silence",
                "Inside, small room", "Inside, large room or hall", "White noise", "Static"}

# ─────────────────────────────────────────────────────────────────────────────
# LRU result cache  {cache_key -> response_dict}
# ─────────────────────────────────────────────────────────────────────────────
_CACHE: dict = {}
_CACHE_LOCK = threading.Lock()
MAX_CACHE_ENTRIES = 256


def _cache_get(key: str) -> Optional[dict]:
    with _CACHE_LOCK:
        entry = _CACHE.get(key)
        if entry:
            log.info("Cache HIT for key %.12s…", key)
        return entry


def _cache_put(key: str, value: dict):
    with _CACHE_LOCK:
        if len(_CACHE) >= MAX_CACHE_ENTRIES:
            oldest = next(iter(_CACHE))
            del _CACHE[oldest]
        _CACHE[key] = value


# ─────────────────────────────────────────────────────────────────────────────
# CPU throttle
# ─────────────────────────────────────────────────────────────────────────────
def _cpu_throttle(max_percent: float):
    if max_percent >= 100 or max_percent <= 0:
        return
    try:
        import psutil
        usage = psutil.cpu_percent(interval=0.1)
        if usage > max_percent:
            sleep_s = min(0.5, (usage - max_percent) / 100.0)
            time.sleep(sleep_s)
    except ImportError:
        pass


# ─────────────────────────────────────────────────────────────────────────────
# Stage 2 — Silero VAD
# ─────────────────────────────────────────────────────────────────────────────
class VadFilter:
    """Silero VAD: returns speech and non-speech regions with audio data."""

    _model = None
    _utils = None
    _lock = threading.Lock()
    _available: Optional[bool] = None

    @classmethod
    def is_available(cls) -> bool:
        if cls._available is None:
            try:
                import torch; import soundfile  # noqa: F401,E401
                cls._available = True
            except ImportError:
                cls._available = False
                log.warning("torch/soundfile not installed — Silero VAD disabled. "
                            "Install: pip install torch torchaudio soundfile")
        return cls._available

    @classmethod
    def _load(cls):
        with cls._lock:
            if cls._model is None:
                import torch
                log.info("Loading Silero VAD model…")
                cls._model, cls._utils = torch.hub.load(
                    "snakers4/silero-vad", "silero_vad",
                    force_reload=False, onnx=False, verbose=False,
                )
                log.info("Silero VAD loaded.")

    @classmethod
    def get_regions(cls, audio: "np.ndarray") -> Tuple[List[dict], List[dict]]:
        """
        Returns (speech_regions, nonspeech_regions).
        Each region: {"start": float, "end": float, "audio": np.ndarray}
        All times are absolute seconds.
        Falls back to one full-audio speech region on any error.
        """
        if not cls.is_available():
            return [], []
        try:
            cls._load()
            import torch
            import numpy as np
            get_speech_ts, _, _, *_ = cls._utils
            wav_tensor = torch.from_numpy(audio)
            total_dur = len(audio) / SAMPLE_RATE

            speech_ts = get_speech_ts(
                wav_tensor, cls._model,
                threshold=0.45,
                min_speech_duration_ms=200,
                min_silence_duration_ms=250,
                return_seconds=True,
                sampling_rate=SAMPLE_RATE,
            )

            def _slice(s, e):
                return audio[int(s * SAMPLE_RATE): int(e * SAMPLE_RATE)]

            ENERGY_THR = 0.001
            speech, nonspeech = [], []
            prev = 0.0

            for ts in speech_ts:
                s, e = ts["start"], ts["end"]
                if s > prev + 0.05:
                    gap = _slice(prev, s)
                    if len(gap) > 0 and float(np.abs(gap).mean()) > ENERGY_THR:
                        nonspeech.append({"start": prev, "end": s, "audio": gap})
                speech.append({"start": s, "end": e, "audio": _slice(s, e)})
                prev = e

            if total_dur - prev > 0.05:
                tail = _slice(prev, total_dur)
                if len(tail) > 0 and float(np.abs(tail).mean()) > ENERGY_THR:
                    nonspeech.append({"start": prev, "end": total_dur, "audio": tail})

            log.info("VAD: %d speech, %d non-speech regions", len(speech), len(nonspeech))
            return speech, nonspeech
        except Exception:  # noqa: BLE001
            log.warning("Silero VAD failed — full audio passed to ASR.\n%s", traceback.format_exc())
            return [], []


# ─────────────────────────────────────────────────────────────────────────────
# Stage 3 — Faster-Whisper ASR (per speech region)
# ─────────────────────────────────────────────────────────────────────────────
def _run_asr(model, speech_regions: List[dict], full_audio: "np.ndarray",
             language: Optional[str], task: str,
             beam_size: int, best_of: int, temperature) -> List[dict]:
    """
    Transcribe each speech region independently.
    condition_on_previous_text=True within a region (good for context),
    False between regions (prevents cross-region hallucination bleed).
    Falls back to full-audio transcription if no regions.
    """
    import numpy as np
    segments = []
    detected_lang = language

    # Fall back to full audio if no VAD regions
    regions_to_process = speech_regions if speech_regions else [
        {"start": 0.0, "end": len(full_audio) / SAMPLE_RATE, "audio": full_audio}
    ]

    for ri, region in enumerate(regions_to_process):
        audio_chunk = region["audio"].astype(np.float32)
        if len(audio_chunk) < 1600:   # < 0.1s — skip
            continue

        segs_iter, info = model.transcribe(
            audio_chunk,
            language=detected_lang,
            task=task,
            beam_size=beam_size,
            best_of=best_of,
            temperature=temperature,
            condition_on_previous_text=True,    # good within one speech region
            vad_filter=False,                   # we already did VAD above
            word_timestamps=True,
            no_speech_threshold=0.6,
            log_prob_threshold=-1.0,
            compression_ratio_threshold=2.4,
        )

        if detected_lang is None:
            detected_lang = info.language
            log.info("Auto-detected language: %s (prob=%.2f)", detected_lang, info.language_probability)

        for seg in segs_iter:
            text = seg.text.strip()
            if not text:
                continue
            # Shift timestamps to absolute (region.start is the offset)
            offset = region["start"]
            start = (seg.words[0].start if seg.words else seg.start) + offset
            end   = (seg.words[-1].end  if seg.words else seg.end)   + offset
            words = []
            if seg.words:
                for w in seg.words:
                    words.append({"word": w.word.strip(),
                                  "start": round(w.start + offset, 3),
                                  "end":   round(w.end   + offset, 3),
                                  "prob":  round(w.probability, 4)})
            segments.append({
                "start":      round(start, 3),
                "end":        round(end, 3),
                "text":       text,
                "type":       "speech",
                "confidence": round(seg.avg_logprob, 4),
                "_words":     words,
            })

        log.info("ASR region %d/%d: %d new segments", ri + 1, len(regions_to_process), len(segments))

    return segments, detected_lang


# ─────────────────────────────────────────────────────────────────────────────
# Stage 4 — End-time waveform correction  (forensic_subtitle.py Stage 4)
# ─────────────────────────────────────────────────────────────────────────────
def _correct_end_times(segments: List[dict], audio: "np.ndarray",
                       trailing_window_ms: int = 350, rms_frame_ms: int = 10) -> List[dict]:
    """Extend subtitle end times to the true waveform silence boundary."""
    try:
        import numpy as np
        frame_samples  = int(rms_frame_ms / 1000 * SAMPLE_RATE)
        window_samples = int(trailing_window_ms / 1000 * SAMPLE_RATE)
        corrections = 0
        for seg in segments:
            end_idx    = int(seg["end"] * SAMPLE_RATE)
            trail_end  = min(end_idx + window_samples, len(audio))
            if trail_end <= end_idx:
                continue
            trail = audio[end_idx:trail_end]
            # Adaptive threshold: 10% of segment's own RMS
            seg_audio = audio[int(seg["start"] * SAMPLE_RATE): end_idx]
            if len(seg_audio) < frame_samples:
                continue
            seg_rms   = float(np.sqrt(np.mean(seg_audio ** 2)))
            threshold = seg_rms * 0.10
            # Find silence onset in trailing window
            silence_offset = None
            for i in range(0, len(trail) - frame_samples, frame_samples):
                if float(np.sqrt(np.mean(trail[i:i + frame_samples] ** 2))) < threshold:
                    silence_offset = i
                    break
            if silence_offset is None:
                new_end = seg["end"] + trailing_window_ms / 1000
                seg["end"] = round(min(new_end, len(audio) / SAMPLE_RATE), 3)
                corrections += 1
            elif silence_offset > 0:
                seg["end"] = round(seg["end"] + silence_offset / SAMPLE_RATE, 3)
                corrections += 1
        log.info("Stage 4 end-time corrections: %d/%d", corrections, len(segments))
    except Exception:
        log.warning("End-time correction failed — skipped.\n%s", traceback.format_exc())
    return segments


# ─────────────────────────────────────────────────────────────────────────────
# Stage 5 — Drift correction  (forensic_subtitle.py Stage 5)
# ─────────────────────────────────────────────────────────────────────────────
def _correct_drift(segments: List[dict], speech_regions: List[dict]) -> Tuple[List[dict], float]:
    """
    Detect and correct progressive timestamp drift between VAD and ASR boundaries.
    Returns (corrected_segments, mean_drift_ms).
    """
    mean_drift_ms = 0.0
    if not speech_regions or len(segments) < 2:
        return segments, mean_drift_ms
    try:
        import numpy as np
        drifts = []
        for seg in segments:
            best_vad = min(speech_regions, key=lambda r: abs(r["start"] - seg["start"]))
            drifts.append((seg["start"], seg["start"] - best_vad["start"]))

        times      = np.array([d[0] for d in drifts])
        drift_vals = np.array([d[1] for d in drifts])
        mean_drift = float(np.mean(drift_vals))
        std_drift  = float(np.std(drift_vals))
        mean_drift_ms = mean_drift * 1000

        coeffs = np.polyfit(times, drift_vals, 1) if len(times) >= 3 else [0.0, mean_drift]
        slope_ms_per_min = coeffs[0] * 1000 * 60

        log.info("Stage 5 drift: mean=%.1fms std=%.1fms slope=%.2fms/min",
                 mean_drift * 1000, std_drift * 1000, slope_ms_per_min)

        if abs(slope_ms_per_min) > 5.0:
            log.info("Stage 5: progressive drift — applying proportional correction")
            for seg in segments:
                correction = coeffs[0] * seg["start"] + coeffs[1]
                seg["start"] = round(max(0.0, seg["start"] - correction), 3)
                seg["end"]   = round(max(0.0, seg["end"]   - correction), 3)
                for w in seg.get("_words", []):
                    w["start"] = round(max(0.0, w["start"] - correction), 3)
                    w["end"]   = round(max(0.0, w["end"]   - correction), 3)
        elif abs(mean_drift) > 0.02 and std_drift < 0.02:
            log.info("Stage 5: uniform offset %.1fms — applying correction", mean_drift * 1000)
            for seg in segments:
                seg["start"] = round(max(0.0, seg["start"] - mean_drift), 3)
                seg["end"]   = round(max(0.0, seg["end"]   - mean_drift), 3)
                for w in seg.get("_words", []):
                    w["start"] = round(max(0.0, w["start"] - mean_drift), 3)
                    w["end"]   = round(max(0.0, w["end"]   - mean_drift), 3)
        else:
            log.info("Stage 5: no significant drift detected")
    except Exception:
        log.warning("Drift correction failed — skipped.\n%s", traceback.format_exc())
    return segments, mean_drift_ms


# ─────────────────────────────────────────────────────────────────────────────
# Stage 6 — Forced alignment refinement  (forensic_subtitle.py Stage 6)
# ─────────────────────────────────────────────────────────────────────────────
def _refine_alignment(segments: List[dict], audio: "np.ndarray",
                      search_window_ms: int = 50, onset_ratio: float = 0.15) -> Tuple[List[dict], int]:
    """
    Snap subtitle boundaries to phoneme onset/offset via energy analysis.
    Searches ±50ms around each boundary. Target: <40ms error.
    Returns (segments, refined_count).
    """
    refined = 0
    try:
        import numpy as np
        frame_ms      = 2
        frame_samples = int(frame_ms / 1000 * SAMPLE_RATE)
        win_samples   = int(search_window_ms / 1000 * SAMPLE_RATE)
        total_samples = len(audio)

        for seg in segments:
            changed = False

            # ── Snap start to energy onset ──────────────────────────────────
            ci = int(seg["start"] * SAMPLE_RATE)
            ws = max(0, ci - win_samples); we = min(total_samples, ci + win_samples)
            region = audio[ws:we]
            if len(region) > frame_samples * 4:
                peak = float(np.max(np.abs(region)))
                thr  = peak * onset_ratio
                for i in range(0, len(region) - frame_samples, frame_samples):
                    if float(np.max(np.abs(region[i:i + frame_samples]))) >= thr:
                        new_start = (ws + i) / SAMPLE_RATE
                        if abs(new_start - seg["start"]) < search_window_ms / 1000:
                            seg["start"] = round(new_start, 3)
                            changed = True
                        break

            # ── Snap end to energy offset ────────────────────────────────────
            ci = int(seg["end"] * SAMPLE_RATE)
            ws = max(0, ci - win_samples); we = min(total_samples, ci + win_samples)
            region = audio[ws:we]
            if len(region) > frame_samples * 4:
                peak = float(np.max(np.abs(region)))
                thr  = peak * onset_ratio
                for i in range(len(region) - frame_samples, 0, -frame_samples):
                    if float(np.max(np.abs(region[i:i + frame_samples]))) >= thr:
                        new_end = (ws + i + frame_samples) / SAMPLE_RATE
                        if abs(new_end - seg["end"]) < search_window_ms / 1000:
                            seg["end"] = round(new_end, 3)
                            changed = True
                        break

            # ── Refine individual word boundaries ────────────────────────────
            for w in seg.get("_words", []):
                wc = int(w["start"] * SAMPLE_RATE)
                wws = max(0, wc - win_samples // 2)
                wwe = min(total_samples, wc + win_samples // 2)
                wr  = audio[wws:wwe]
                if len(wr) > frame_samples * 2:
                    lp = float(np.max(np.abs(wr)))
                    ot = lp * onset_ratio
                    for i in range(0, len(wr) - frame_samples, frame_samples):
                        if float(np.max(np.abs(wr[i:i + frame_samples]))) >= ot:
                            nw = (wws + i) / SAMPLE_RATE
                            if abs(nw - w["start"]) < search_window_ms / 1000:
                                w["start"] = round(nw, 3)
                            break

            if changed:
                refined += 1

        log.info("Stage 6 alignment refined: %d/%d segments", refined, len(segments))
    except Exception:
        log.warning("Alignment refinement failed — skipped.\n%s", traceback.format_exc())
    return segments, refined


# ─────────────────────────────────────────────────────────────────────────────
# Stage 7 — YAMNet SED  (non-speech regions)
# ─────────────────────────────────────────────────────────────────────────────
class SoundEventDetector:
    _model = None
    _class_names: list = []
    _lock = threading.Lock()
    _available: Optional[bool] = None

    @classmethod
    def is_available(cls) -> bool:
        if cls._available is None:
            try:
                import tensorflow as tf; import tensorflow_hub  # noqa: F401,E401
                cls._available = True
            except ImportError:
                cls._available = False
                log.warning("TensorFlow/TF-Hub not installed — YAMNet SED disabled. "
                            "Install: pip install tensorflow tensorflow-hub")
        return cls._available

    @classmethod
    def _load(cls):
        with cls._lock:
            if cls._model is None:
                import tensorflow_hub as hub, csv, urllib.request, io
                log.info("Loading YAMNet from TF-Hub…")
                cls._model = hub.load("https://tfhub.dev/google/yamnet/1")
                try:
                    url = ("https://raw.githubusercontent.com/tensorflow/models/"
                           "master/research/audioset/yamnet/yamnet_class_map.csv")
                    with urllib.request.urlopen(url, timeout=10) as r:
                        reader = csv.DictReader(io.TextIOWrapper(r, encoding="utf-8"))
                        cls._class_names = [row["display_name"] for row in reader]
                except Exception:
                    cls._class_names = []
                log.info("YAMNet loaded (%d classes).", len(cls._class_names))

    @classmethod
    def detect(cls, nonspeech_regions: List[dict],
               confidence_threshold: float = 0.35) -> List[dict]:
        if not cls.is_available():
            return []
        try:
            cls._load()
            import numpy as np, tensorflow as tf
            events = []
            for region in nonspeech_regions:
                waveform = region["audio"].astype(np.float32)
                if len(waveform) < int(0.96 * SAMPLE_RATE):
                    continue
                scores, _, _ = cls._model(waveform)
                mean_scores  = tf.reduce_mean(scores, axis=0).numpy()
                top_idx  = int(np.argmax(mean_scores))
                top_conf = float(mean_scores[top_idx])
                if top_conf < confidence_threshold:
                    continue
                label = cls._class_names[top_idx] if top_idx < len(cls._class_names) else str(top_idx)
                if label in SED_EXCLUDED:
                    continue
                caption = SED_LABEL_MAP.get(label, f"({label})")
                events.append({
                    "start":      round(region["start"], 3),
                    "end":        round(region["end"],   3),
                    "text":       caption,
                    "type":       "sound_event",
                    "confidence": round(top_conf, 3),
                })
            log.info("Stage 7 YAMNet events: %d", len(events))
            return events
        except Exception:  # noqa: BLE001
            log.warning("YAMNet SED failed — skipped.\n%s", traceback.format_exc())
            return []


# ─────────────────────────────────────────────────────────────────────────────
# Stage 8 — Validation  (forensic_subtitle.py Stage 8)
# ─────────────────────────────────────────────────────────────────────────────
def _validate_and_fix(segments: List[dict], audio: "np.ndarray",
                      confidence_threshold: float = 70.0) -> Tuple[List[dict], dict]:
    """
    Fix overlapping segments, early-termination errors.
    Returns (fixed_segments, alignment_info_dict).
    """
    info = {"overall_confidence": 0.0, "overlaps_fixed": 0,
            "early_terminations_fixed": 0, "passed": False}
    try:
        import numpy as np
        segments.sort(key=lambda s: s["start"])
        frame_samples = int(0.010 * SAMPLE_RATE)

        # Early-termination fix
        for seg in segments:
            if seg["type"] != "speech":
                continue
            end_idx = int(seg["end"] * SAMPLE_RATE)
            cs = max(0, end_idx - frame_samples)
            ce = min(len(audio), end_idx + frame_samples)
            if ce <= cs:
                continue
            check_audio = audio[cs:ce]
            rms = float(np.sqrt(np.mean(check_audio ** 2)))
            seg_audio = audio[int(seg["start"] * SAMPLE_RATE): end_idx]
            seg_rms = float(np.sqrt(np.mean(seg_audio ** 2))) if len(seg_audio) > 0 else 0.001
            silence_thr = seg_rms * 0.08
            if rms > silence_thr:
                for ext_i in range(end_idx, min(end_idx + int(0.5 * SAMPLE_RATE), len(audio)), frame_samples):
                    block = audio[ext_i: min(ext_i + frame_samples, len(audio))]
                    if float(np.sqrt(np.mean(block ** 2))) < silence_thr:
                        seg["end"] = round(ext_i / SAMPLE_RATE, 3)
                        info["early_terminations_fixed"] += 1
                        break

        # Overlap fix
        for i in range(1, len(segments)):
            if segments[i]["start"] < segments[i - 1]["end"]:
                mid = (segments[i - 1]["end"] + segments[i]["start"]) / 2
                segments[i - 1]["end"] = round(mid, 3)
                segments[i]["start"]   = round(mid, 3)
                info["overlaps_fixed"] += 1

        # Confidence score
        speech_segs = [s for s in segments if s["type"] == "speech"]
        confs = []
        for seg in speech_segs:
            if seg.get("_words"):
                avg_prob = float(np.mean([w["prob"] for w in seg["_words"]])) * 100
            else:
                # avg_logprob is negative; map to 0-100 range
                avg_prob = max(0, min(100, (1.0 + seg["confidence"]) * 100))
            confs.append(avg_prob)

        info["overall_confidence"] = round(float(np.mean(confs)), 2) if confs else 0.0
        info["passed"] = info["overall_confidence"] >= confidence_threshold
        log.info("Stage 8 validation: confidence=%.1f%% overlaps_fixed=%d early_term=%d passed=%s",
                 info["overall_confidence"], info["overlaps_fixed"],
                 info["early_terminations_fixed"], info["passed"])
    except Exception:
        log.warning("Validation failed — skipped.\n%s", traceback.format_exc())
    return segments, info


# ─────────────────────────────────────────────────────────────────────────────
# Main server
# ─────────────────────────────────────────────────────────────────────────────
class WhisperServer:
    """
    Persistent sidecar: one WhisperModel kept in RAM, 9-stage forensic pipeline per request.
    """

    def __init__(self):
        self._model        = None
        self._loaded_size: Optional[str] = None
        self._compute_type = "int8"
        self._cpu_threads  = max(2, (os.cpu_count() or 4) - 1)
        log.info("Lumina sidecar initialised. CPU threads=%d compute=%s",
                 self._cpu_threads, self._compute_type)
        _log_optional_deps()

    # ── Model management ──────────────────────────────────────────────────────
    def _ensure_model(self, size: str):
        if self._model is not None and self._loaded_size == size:
            return
        try:
            from faster_whisper import WhisperModel
        except ImportError:
            raise RuntimeError("faster-whisper not installed. Run: pip install faster-whisper")
        log.info("Loading model '%s' (compute=%s, threads=%d)…",
                 size, self._compute_type, self._cpu_threads)
        self._model = WhisperModel(
            size,
            device="cpu",
            compute_type=self._compute_type,
            cpu_threads=self._cpu_threads,
            num_workers=1,
        )
        self._loaded_size = size
        log.info("Model '%s' ready.", size)

    # ── Full 9-stage pipeline per request ─────────────────────────────────────
    def transcribe(self, req: dict) -> dict:
        wav_path    = req["wav_path"]
        model_size  = req.get("model", "small")
        language    = req.get("language") or None
        quality     = req.get("quality", "BALANCED").upper()
        translate   = req.get("translate", False)
        use_vad     = req.get("use_vad", True)
        want_sed    = req.get("sound_events", False)
        max_cpu     = float(req.get("max_cpu_percent", 100))
        cache_key   = req.get("cache_key") or None

        # ── Cache lookup ──────────────────────────────────────────────────────
        if cache_key:
            hit = _cache_get(cache_key)
            if hit:
                return {**hit, "from_cache": True}

        if not os.path.exists(wav_path):
            return {"segments": [], "language": None, "from_cache": False,
                    "alignment_info": {}, "error": f"WAV not found: {wav_path}"}

        presets     = QUALITY_PRESETS.get(quality, QUALITY_PRESETS["BALANCED"])
        beam_size   = req.get("beam_size") or presets["beam_size"]
        best_of     = req.get("best_of")  or presets["best_of"]
        temperature = presets["temperature"]

        try:
            self._ensure_model(model_size)
        except RuntimeError as e:
            return {"segments": [], "language": None, "from_cache": False,
                    "alignment_info": {}, "error": str(e)}

        _cpu_throttle(max_cpu)

        # ── Stage 1: Load audio into numpy ───────────────────────────────────
        try:
            import soundfile as sf
            import numpy as np
            audio, sr = sf.read(wav_path, dtype="float32")
            if audio.ndim > 1:
                audio = audio.mean(axis=1)
            # Resample if needed (should already be 16kHz from FFmpeg)
            if sr != SAMPLE_RATE:
                log.warning("Unexpected sample rate %d Hz (expected %d) — may affect accuracy", sr, SAMPLE_RATE)
        except Exception as e:
            return {"segments": [], "language": None, "from_cache": False,
                    "alignment_info": {}, "error": f"Audio load failed: {e}"}

        # ── Stage 2: Silero VAD ───────────────────────────────────────────────
        speech_regions: List[dict] = []
        nonspeech_regions: List[dict] = []
        if use_vad and VadFilter.is_available():
            speech_regions, nonspeech_regions = VadFilter.get_regions(audio)

        # ── Stage 3: Faster-Whisper ASR (per region) ─────────────────────────
        task = "translate" if translate else "transcribe"
        _cpu_throttle(max_cpu)
        try:
            segments, detected_lang = _run_asr(
                self._model, speech_regions, audio,
                language, task, beam_size, best_of, temperature,
            )
        except Exception as e:
            log.error("ASR error:\n%s", traceback.format_exc())
            return {"segments": [], "language": None, "from_cache": False,
                    "alignment_info": {}, "error": str(e)}

        # ── Stage 4: End-time correction ─────────────────────────────────────
        segments = _correct_end_times(segments, audio)

        # ── Stage 5: Drift correction ─────────────────────────────────────────
        segments, drift_ms = _correct_drift(segments, speech_regions)

        # ── Stage 6: Forced alignment refinement ─────────────────────────────
        segments, refined_count = _refine_alignment(segments, audio)

        # ── Stage 7: YAMNet SED ───────────────────────────────────────────────
        sound_events: List[dict] = []
        if want_sed and SoundEventDetector.is_available():
            _cpu_throttle(max_cpu)
            sound_events = SoundEventDetector.detect(nonspeech_regions)

        all_segments = sorted(segments + sound_events, key=lambda s: s["start"])

        # ── Stage 8: Validation + auto-retry ─────────────────────────────────
        all_segments, alignment_info = _validate_and_fix(all_segments, audio)

        # Auto-retry with higher beam if confidence is low and beam was < 5
        if not alignment_info.get("passed", True) and beam_size < 5:
            log.warning("Confidence %.1f%% below threshold — retrying with beam_size=5",
                        alignment_info["overall_confidence"])
            _cpu_throttle(max_cpu)
            try:
                segments_retry, _ = _run_asr(
                    self._model, speech_regions, audio,
                    language, task, 5, best_of, temperature,
                )
                segments_retry = _correct_end_times(segments_retry, audio)
                segments_retry, _ = _correct_drift(segments_retry, speech_regions)
                segments_retry, _ = _refine_alignment(segments_retry, audio)
                all_segments_retry = sorted(segments_retry + sound_events, key=lambda s: s["start"])
                all_segments_retry, alignment_info_retry = _validate_and_fix(all_segments_retry, audio)
                if alignment_info_retry["overall_confidence"] > alignment_info["overall_confidence"]:
                    all_segments  = all_segments_retry
                    alignment_info = alignment_info_retry
                    log.info("Retry improved confidence: %.1f%% → %.1f%%",
                             alignment_info["overall_confidence"],
                             alignment_info_retry["overall_confidence"])
            except Exception:
                log.warning("Retry failed — keeping original result.\n%s", traceback.format_exc())

        # Strip internal _words field before sending to Java
        for seg in all_segments:
            seg.pop("_words", None)

        alignment_info["drift_ms"] = round(drift_ms, 2)
        alignment_info["refined"]  = refined_count

        result = {
            "segments":       all_segments,
            "language":       detected_lang,
            "from_cache":     False,
            "alignment_info": alignment_info,
            "error":          None,
        }

        if cache_key:
            _cache_put(cache_key, result)

        return result

    # ── JSON-lines event loop ─────────────────────────────────────────────────
    def run(self):
        log.info("Lumina sidecar ready — listening on stdin.")
        stdin  = open(sys.stdin.fileno(),  "rb", closefd=False)
        stdout = open(sys.stdout.fileno(), "wb", closefd=False)

        for raw_line in stdin:
            line = raw_line.decode("utf-8", errors="replace").strip()
            if not line:
                continue
            try:
                req = json.loads(line)
            except json.JSONDecodeError as e:
                self._write(stdout, {"error": f"JSON parse error: {e}"})
                continue

            cmd = req.get("cmd")
            if cmd == "ping":
                self._write(stdout, {"pong": True,
                                     "vad_available": VadFilter.is_available(),
                                     "sed_available": SoundEventDetector.is_available()})
                continue
            if cmd == "exit":
                log.info("Exit command received.")
                self._write(stdout, {"bye": True})
                break
            if cmd == "cache_clear":
                with _CACHE_LOCK:
                    _CACHE.clear()
                self._write(stdout, {"cleared": True})
                continue

            resp = self.transcribe(req)
            self._write(stdout, resp)

        log.info("Lumina sidecar exiting.")

    @staticmethod
    def _write(stdout, obj: dict):
        line = json.dumps(obj, ensure_ascii=False) + "\n"
        stdout.write(line.encode("utf-8"))
        stdout.flush()


# ─────────────────────────────────────────────────────────────────────────────
# Helpers
# ─────────────────────────────────────────────────────────────────────────────
def _log_optional_deps():
    for pkg, friendly in [
        ("torch",          "Silero VAD"),
        ("soundfile",      "Audio I/O (required for pipeline)"),
        ("tensorflow",     "YAMNet SED"),
        ("tensorflow_hub", "YAMNet TF-Hub"),
        ("psutil",         "CPU throttle"),
    ]:
        try:
            __import__(pkg)
            log.info("  ✓ %s", friendly)
        except ImportError:
            log.info("  ✗ %s  (pip install %s)", friendly, pkg)


if __name__ == "__main__":
    WhisperServer().run()
