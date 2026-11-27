# 自备角色素材 / Supplying your own character assets

本仓库只发布源码。角色模型、贴图和参考语音由各自权利人提供，**不能随源码再分发**，所以仓库里没有这些文件。这一页说明怎么自备、怎么改——不是给你原文件。

This repository publishes source code only. Character models, textures and reference voices belong to their respective rights holders and **must not be redistributed** with this repository, so they are not included. This page explains how to supply and adapt your own.

## 一、角色模型 / Character model

1. 从作者发布的原始页面获取，不要转存、不要二次上传：
   - **大肥鱼（DeepSeek-chan）**：原画及拆分 神宫凉子，Live2D 建模 霓虹猫_NeonNeko；作者在 B 站「神宫凉子」免费配布。作者规范禁止重新分发模型文件、禁止二次销售。
   - **艾莲（Ellen）**：版权 miHoYo；画师 神宫凉子、建模 杨小唸；唯一发布渠道 B 站「神宫凉子」。禁止转手、二改。
2. 放到 `<assets>/live2d/<id>/`，例如 `assets/live2d/whale/`。`<assets>` 是构建变量 `DSH_ANDROID_ASSETS_DIR` 指向的目录；不设时默认 `apps/android/app/src/main/assets`。
3. 目录里至少要有：`model.model3.json`、`*.moc3`、贴图目录、`physics3.json`、`cdi3.json`，以及应用用来识别角色的 `profile.json`：

   ```json
   {"id": "whale", "displayName": "鲸鱼娘", "version": 1, "modelJson": "model.model3.json"}
   ```

4. 应用启动时扫描 `assets/live2d/` 下的 `profile.json`。没有模型时，App 仍能编译、连接对话服务，但没有角色形象。

## 二、触摸语音 / Touch voices

仓库里没有触摸语音 mp3：参考部署用的是 Microsoft Edge Read Aloud 的 `zh-CN-XiaoyiNeural`，声音本身属于 Microsoft，不随源码分发。两条路：

**自己合成**（推荐，台词和音色都能换）：

```sh
set L2D_TTS_ENDPOINT=http://127.0.0.1:8448/audio/speech   # 任意兼容 OpenAI /audio/speech 的服务
set L2D_TTS_TOKEN=<你的 key，没有就不设>
set L2D_TTS_VOICE=<你的 TTS 支持的音色名>
python apps/android/tools/gen_touch_voices.py --out apps/android/app/src/main/assets/touch-voices
```

34 句台词在 `apps/android/tools/gen_touch_voices.py` 的 `LINES` 里，可以按自己的角色改；脚本会生成 `<name>.mp3` 和 `touch-voices.manifest.json`（manifest 只方便人读，App 不读）。

**自己录音**：文件名必须按 `AutoMotionDirector.touchVoiceClip` 的约定：`tap_<部位>_1|2`、`double_<部位>`、`hold_<部位>`、`rapid_1|2`，部位是 `hair/face/ears/chest/waist/skirt/legs/tail`，放同一目录即可。缺文件不会报错，那一次触摸就只有动作/表情、没有声音。

**音色许可**：如果你继续用 `Xiaoyi`，请按 Microsoft（Edge / Azure）对应条款确认你的使用场景；商业用途建议直接换成 Azure TTS 或你有权使用的音色。自己录的声音没有这个问题。

## 三、动作 / Motions

- `apps/android/app/src/main/assets/call-motions/`：使用 Cubism 标准参数（`ParamAngleX/Y/Z`、`ParamBodyAngleX/Y/Z` 等），任何暴露标准参数的模型都能用，与具体 rig 无关。
- `apps/android/app/src/main/assets/trained-rigs/whale|ellen/`：针对大肥鱼 / 艾莲这两个 rig 拟合的动作，**只对对应模型有效**；换模型需要重训或自己写 `motion3.json`。
- 给新模型加动作：可直接写 Cubism `motion3.json`，并同步 `trained-rigs/manifest.json` 里该条的 `bytes`、`parameter_ids`、时长等字段——`Live2DView.validateTrainedCallMotion` 会逐条校验，文件与 manifest 不一致时整套 trained rig 会被拒绝，退回 `call-motions/` 的通用动作。
- 动作不依赖模型文件，可以随源码分发；但它的效果取决于目标 rig 的参数通道。

## 四、不要提交到公开仓 / Do not commit

- 模型、贴图、触摸语音都有各自的授权，**不要**提交到公开仓库（除非你确认自己有权再分发）。
- `.gitignore` 已忽略 `assets/touch-voices/`、`**/touch-voices/`、`*.moc3`、`**/assets/live2d/`；模型建议放在仓库外，用 `DSH_ANDROID_ASSETS_DIR` 指过去，避免误提交。
- 构建前可以跑一次发布闸门（`tools/` 下的脚本是给维护者用的，不在本仓）：先确认树里没有本机路径、内网 IP、密钥和别人的素材。
