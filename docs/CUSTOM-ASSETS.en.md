# Supplying your own character assets

This repository publishes source code only. Character models, textures and reference voices belong to their respective rights holders and **must not be redistributed** with this repository, so they are not included. This page explains how to supply and adapt your own.

## 1. Character model (Live2D)

1. Obtain the model from the author's original release page. Do not mirror or re-upload it:
   - **Whale (DeepSeek-chan)**: original art and separation by 神宫凉子; Live2D rig by 霓虹猫_NeonNeko; the author distributes it for free on Bilibili ("神宫凉子"). The author's terms prohibit redistribution and resale of the model files.
   - **Ellen**: copyright miHoYo; art by 神宫凉子; model by 杨小唸; the only release channel is the author's Bilibili page. Redistribution and modification are prohibited.
2. Put the model under `<assets>/live2d/<id>/`, for example `assets/live2d/whale/`. `<assets>` is the directory pointed to by the build variable `DSH_ANDROID_ASSETS_DIR`; when unset, it defaults to `apps/android/app/src/main/assets`.
3. The directory needs at least `model.model3.json`, the `*.moc3`, texture folders, `physics3.json`, `cdi3.json`, and the `profile.json` the app uses to discover the character:

   ```json
   {"id": "whale", "displayName": "鲸鱼娘", "version": 1, "modelJson": "model.model3.json"}
   ```

4. The app scans `assets/live2d/` for `profile.json` at startup. Without a model the app still builds and connects to a conversation service, but shows no character.

## 2. Touch voices

The repository contains no touch-voice mp3 files: the reference deployment used the Microsoft Edge Read Aloud voice `zh-CN-XiaoyiNeural`, which belongs to Microsoft, so the clips are not distributed here. Two options:

**Synthesize your own** (recommended; change both lines and voice):

```sh
set L2D_TTS_ENDPOINT=http://127.0.0.1:8448/audio/speech   # any OpenAI-compatible /audio/speech service
set L2D_TTS_TOKEN=<your key, optional>
set L2D_TTS_VOICE=<a voice your TTS accepts>
python apps/android/tools/gen_touch_voices.py --out apps/android/app/src/main/assets/touch-voices
```

The 34 lines live in `LINES` in `apps/android/tools/gen_touch_voices.py`; edit them for your own character. The script writes `<name>.mp3` plus `touch-voices.manifest.json` (the manifest is for humans; the app does not read it).

**Record your own**: file names must follow `AutoMotionDirector.touchVoiceClip`: `tap_<region>_1|2`, `double_<region>`, `hold_<region>`, `rapid_1|2`, with regions `hair/face/ears/chest/waist/skirt/legs/tail`. Missing files are skipped silently; that poke then plays motion/expression only.

**Voice licensing**: if you keep `Xiaoyi`, check the Microsoft (Edge / Azure) terms that apply to your use case; for commercial use prefer Azure TTS or a voice you have rights to. Your own recordings have no such issue.

## 3. Motions

- `apps/android/app/src/main/assets/call-motions/` uses Cubism standard parameters (`ParamAngleX/Y/Z`, `ParamBodyAngleX/Y/Z`, ...). Any model exposing the standard parameters can use them; they are not rig-specific.
- `apps/android/app/src/main/assets/trained-rigs/whale|ellen/` are motions fitted to those two rigs and **only apply to the matching model**. A different model needs retraining or a new `motion3.json`.
- To add motions for a new model: write a Cubism `motion3.json` and keep `trained-rigs/manifest.json` in sync (`bytes`, `parameter_ids`, duration, ...). `Live2DView.validateTrainedCallMotion` validates every entry; if the file and the manifest disagree, the whole trained-rig set is rejected and the app falls back to `call-motions/`.
- Motions do not depend on model files and can ship with the source; how they look depends on the target rig's parameter channels.

## 4. Do not commit

- Models, textures and touch voices have their own terms. **Do not** commit them to the public repository unless you have confirmed you may redistribute them.
- `.gitignore` already ignores `assets/touch-voices/`, `**/touch-voices/`, `*.moc3` and `**/assets/live2d/`. Prefer keeping models outside the repository and pointing `DSH_ANDROID_ASSETS_DIR` at them to avoid accidental commits.
