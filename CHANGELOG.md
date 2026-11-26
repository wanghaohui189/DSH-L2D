# Changelog / 变更记录

## 0.0.105-wake-guard

- 安全：唤醒来电不再依赖源码里的常量 token。`WakeRequestReceiver` 改为声明
  `com.prts.client.permission.WAKE_REQUEST`（`protectionLevel="signature"`），只有与 App 同签名的
  伴侣应用能投递广播；`WakeCallActivity` 不再导出，只能由本进程启动。
- Security: the wake-call hook no longer trusts a hard-coded token from the source tree. The receiver
  now requires a signature-level permission shared only with the companion app, and the wake activity
  is no longer exported.

## 0.0.2

- 首次启动可打开麦克风和摄像头授权、电池设置，以及 DSH Go 本机连接。
- 音色管理显示本机 STT 的下载进度和就绪状态，下载中断后可手动重试。
- 从 L2D 开启本机配对后，在 Go 的「全部功能 → DSH L2D桥接」点连接。初次配对时返回 L2D 确认。
- First-run setup links to microphone and camera permissions, battery settings, and local DSH Go pairing.
- Voice settings show on-device STT download progress and readiness, with a retry option after interruptions.
- Start local pairing in L2D, then connect under All Features → DSH L2D Bridge in Go. Return to L2D to confirm the first pairing.

[0.0.2 Release](https://github.com/Franka-official/DSH-L2D-Public/releases/tag/v0.0.2)

## 0.0.1

- 首次公开 Android 源码和三个独立插件。
- 正式签名完整版采用 MOSS-TTS-Nano INT8 CPU，内置鲸鱼娘 Bright 和艾莲音色。
- 音色管理支持 MOSS WAV 导入、外接语音 API 和可选 Audio8。
- First public source release for Android and the three standalone plugins.
- The signed full APK bundles MOSS-TTS-Nano INT8 CPU synthesis and the Whale Bright and Ellen voices.
- Voice settings support MOSS WAV import, an external speech API and optional Audio8.

[0.0.1 Release](https://github.com/Franka-official/DSH-L2D-Public/releases/tag/v0.0.1)
