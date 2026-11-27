# 素材来源 / Asset sources

项目自有源代码采用 [AGPL-3.0-only](LICENSE)。运行资源按各自来源和许可管理，构建时从仓库外提供。角色模型和贴图**不随本仓库分发**（作者免费配布但禁止重新分发），请按下方作者页面自行获取；完整版 APK 另含角色资源和声码提示。

Project-owned source is licensed under [AGPL-3.0-only](LICENSE). Runtime assets have separate sources and terms and are supplied outside the source tree. Character models and textures are **not distributed with this repository** (the authors distribute them for free but prohibit redistribution); obtain them from the authors' pages below. The full APK ships character resources and voice prompts separately.

| 组件 / Component | 来源 / Source |
| --- | --- |
| MOSS-TTS-Nano INT8 | [REALBITS INT8 生成器](https://huggingface.co/REALBITS/MOSS-TTS-Nano-100M-ONNX-int8/tree/23fd19793be20a4f3f6aacd4e3a2130c8e3d2519) |
| MOSS 音频编解码器 / Audio codec | [OpenMOSS Audio Tokenizer](https://huggingface.co/OpenMOSS-Team/MOSS-Audio-Tokenizer-Nano-ONNX/tree/ceff0d0749bfb3fa2d61149794ec6feef0d1e1ae) |
| 可选 Audio8 / Optional Audio8 | [Audio8 0.1B ONNX INT8](https://huggingface.co/Edge0/audio8-TTS-0.1B-ONNX-INT8/tree/317c12d4e0da83847b594fcf8bd74bf2c76615ec) |
| 鲸鱼娘（DeepSeek-chan / 大肥鱼）/ Whale (DeepSeek-chan) | 原画及拆分：神宫凉子；Live2D 建模：霓虹猫_NeonNeko；免费配布模型，请到作者页面（B 站「神宫凉子」）获取。作者规范禁止重新分发模型文件、禁止二次销售。不随本仓库分发。 / Original art: 神宫凉子; Live2D rig: 霓虹猫_NeonNeko. Free-distributed model; obtain it from the author's page. Redistribution and resale of the model files are prohibited. Not distributed with this repository. |
| 艾莲（Ellen） | 版权 miHoYo；画师 神宫凉子、建模 杨小唸；唯一发布渠道 B 站「神宫凉子」。禁止转手、二改。不随本仓库分发。 / Copyright miHoYo; art by 神宫凉子; model by 杨小唸. Obtain it from the author's page. Redistribution and modification are prohibited. Not distributed with this repository. |
| 背景 / Backgrounds | 完整版保留带 CC0 来源记录的背景图。 / The APK retains backgrounds with recorded CC0 sources. |
| 动作 / Motions | `call-motions/` 使用 Cubism 标准参数（如 `ParamAngleX/Y/Z`、`ParamBodyAngleX/Y/Z`），可配合任意含标准参数的模型；`trained-rigs/whale`、`trained-rigs/ellen` 是针对大肥鱼、艾莲模型训练的动作，需先从作者处取得对应模型才能使用。 / `call-motions/` uses Cubism standard parameters and works with any model that exposes them; `trained-rigs/whale` and `trained-rigs/ellen` are rig-specific motions and require the corresponding model. |

[构建与资源配置](docs/BUILDING.md) · [Build and assets](docs/BUILDING.en.md) · [Third-party notices](THIRD_PARTY_LICENSES.md)
