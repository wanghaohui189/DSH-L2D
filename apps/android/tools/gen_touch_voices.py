# -*- coding: utf-8 -*-
"""Render the per-region touch replies for the L2D call screen.

The app ships no touch-voice audio: this repository has no rights to redistribute
the voice used for the reference clips. Instead, render your own clips with a TTS
service you are allowed to use, or record them yourself.

The clips are pre-rendered, not synthesized at runtime: a poke must answer
instantly and must not consume model tokens or the call TTS queue.

Contract expected by the app (see `AutoMotionDirector.touchVoiceClip` and
`TouchVoicePlayer` in the Android sources):

    assets/touch-voices/<name>.mp3 + touch-voices.manifest.json

The names below are the ones `CharacterTouchPolicy` / `AutoMotionDirector` look
up (`tap_<region>_1|2`, `double_<region>`, `hold_<region>`, `rapid_1|2`).
Missing clips are skipped silently; the poke then has motion/expression only.

Usage:
    set L2D_TTS_ENDPOINT=http://127.0.0.1:8448/audio/speech   # any OpenAI-compatible /audio/speech
    set L2D_TTS_TOKEN=<optional bearer token>
    set L2D_TTS_VOICE=<voice name your endpoint accepts>       # optional
    python gen_touch_voices.py --out <assets/touch-voices> [--only tap_hair_1] [--probe]

The endpoint is never stored in this repo or in the generated manifest: pass it
via L2D_TTS_ENDPOINT (and L2D_TTS_TOKEN if your service requires a key).

Voice licensing: the reference deployment used the Microsoft Edge "read aloud"
voice `zh-CN-XiaoyiNeural` via a local relay. That voice is provided by
Microsoft and is not redistributed here; if you keep the same voice, check the
terms that apply to your use, or supply your own voice.
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import time
import urllib.request
from pathlib import Path

ENDPOINT = os.environ.get("L2D_TTS_ENDPOINT", "").strip()
TOKEN = os.environ.get("L2D_TTS_TOKEN", "").strip()
MODEL = os.environ.get("L2D_TTS_MODEL", "tts-1").strip() or "tts-1"
VOICE = os.environ.get("L2D_TTS_VOICE", "Xiaoyi").strip()

# region ids come from PresentationPolicy.touchRegion, kinds from AutoMotionDirector.classifyTap.
LINES: dict[str, str] = {
    # tap (single poke) : two variants each so a rhythm of pokes is not one repeated sentence
    "tap_hair_1": "唔…摸头就摸头嘛",
    "tap_hair_2": "主人的手好轻呀",
    "tap_face_1": "呀！戳我脸干嘛",
    "tap_face_2": "脸不可以随便戳的",
    "tap_ears_1": "唔…耳朵被碰到了",
    "tap_ears_2": "别碰耳朵，会抖的",
    "tap_chest_1": "啊…胸口也戳？",
    "tap_chest_2": "这里不让碰啦",
    "tap_waist_1": "咦，好痒，别挠腰",
    "tap_waist_2": "腰不能碰的呀",
    "tap_skirt_1": "裙子要被碰飞了",
    "tap_skirt_2": "别碰裙摆，真的",
    "tap_legs_1": "唔，腿被碰到了",
    "tap_legs_2": "腿有点痒欸",
    "tap_tail_1": "尾巴！别拽尾巴",
    "tap_tail_2": "啊，尾巴被碰到了",
    # double tap
    "double_hair": "又摸又摸，头都晕了",
    "double_face": "连戳两下，太过分了",
    "double_ears": "耳朵都要被你戳红了",
    "double_chest": "还戳，人家会害羞的",
    "double_waist": "再挠我就要笑出声了",
    "double_skirt": "再碰裙子我就不理你",
    "double_legs": "腿都不能好好放着了",
    "double_tail": "尾巴要被你摸炸了",
    # hold
    "hold_hair": "嗯…被摸头了，我举手",
    "hold_face": "脸被按住了，动不了",
    "hold_ears": "揪耳朵很疼的呀",
    "hold_chest": "被按住了，起不来",
    "hold_waist": "按住腰会更痒的",
    "hold_skirt": "裙子别按着，会坏的",
    "hold_legs": "放开啦，腿被按住了",
    "hold_tail": "别抓着尾巴，真会疼",
    # rapid (three pokes inside the window)
    "rapid_1": "别戳啦，我生气了",
    "rapid_2": "再戳我就走了",
}


def render(text: str, timeout: int = 40) -> bytes:
    if not ENDPOINT:
        raise RuntimeError("L2D_TTS_ENDPOINT is not set")
    body = json.dumps({
        "model": MODEL, "voice": VOICE, "input": text,
        "response_format": "mp3", "speed": 1.0,
    }).encode("utf-8")
    headers = {"Content-Type": "application/json", "Accept": "audio/mpeg"}
    if TOKEN:
        headers["Authorization"] = "Bearer " + TOKEN
    request = urllib.request.Request(
        ENDPOINT, data=body, headers=headers,
    )
    with urllib.request.urlopen(request, timeout=timeout) as response:
        data = response.read()
        ctype = response.headers.get("Content-Type", "")
    if not data or len(data) < 2048:
        raise RuntimeError(f"short body: {len(data)} bytes, ctype={ctype}")
    if not data.startswith(b"ID3") and data[:2] not in (b"\xff\xfb", b"\xff\xf3", b"\xff\xf2"):
        raise RuntimeError(f"not an mp3: ctype={ctype} head={data[:8].hex()}")
    return data


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", required=True)
    parser.add_argument("--only", action="append", default=None)
    parser.add_argument("--probe", action="store_true", help="render one line to stdout stats only")
    parser.add_argument("--force", action="store_true", help="re-render files that already exist")
    args = parser.parse_args()

    names = args.only or list(LINES)
    if args.probe:
        names = names[:1]
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)

    manifest_path = out / "touch-voices.manifest.json"
    manifest: dict[str, str] = {}
    if manifest_path.is_file():
        try:
            manifest = json.loads(manifest_path.read_text(encoding="utf-8")).get("lines", {})
        except (OSError, ValueError):
            manifest = {}

    failures: list[str] = []
    for index, name in enumerate(names, 1):
        text = LINES.get(name)
        if not text:
            failures.append(f"{name}: no line defined")
            continue
        target = out / f"{name}.mp3"
        if target.is_file() and target.stat().st_size > 2048 and not args.force:
            manifest[name] = text
            print(f"[{index}/{len(names)}] skip {name} (exists, {target.stat().st_size}B)")
            continue
        started = time.time()
        try:
            data = render(text)
        except Exception as exc:  # noqa: BLE001 - report and keep going
            failures.append(f"{name}: {type(exc).__name__} {exc}")
            print(f"[{index}/{len(names)}] FAIL {name} {exc}")
            continue
        target.write_bytes(data)
        manifest[name] = text
        print(f"[{index}/{len(names)}] ok {name} {len(data)}B "
              f"{int((time.time() - started) * 1000)}ms  {text}")
        time.sleep(0.15)

    manifest_path.write_text(
        json.dumps({"voice": VOICE, "model": MODEL, "lines": manifest},
                   ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"manifest: {manifest_path}")
    if failures:
        print("FAILURES:\n  " + "\n  ".join(failures))
        return 1
    print(f"RESULT: PASS {len(names)} clips")
    return 0


if __name__ == "__main__":
    sys.exit(main())
