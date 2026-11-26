# 安全问题报告

发布仓库创建后，维护者应启用 GitHub Private Vulnerability Reporting，并在发布前验证 Security 页确实出现 **Report a vulnerability** 入口。

如该入口可用，请从那里私下提交复现过程、受影响版本和必要的脱敏证据。不要在公开Issue里发布可利用细节、API Key、配对秘密、Cookie或完整私人日志。

如果尚未提供私密入口，不要发送秘密或可利用细节。可以用不含细节的普通Issue请求维护者建立私下联系渠道。当前没有公布安全邮箱，不应把本模板当作已经存在的报告渠道。

维护者收到报告后核对影响、协调修复和披露；目前未承诺固定响应时限，也未公布长期支持版本表。

官方配置说明：
https://docs.github.com/en/code-security/how-tos/report-and-fix-vulnerabilities/configure-vulnerability-reporting/configure-for-a-repository

## 唤醒来电的信任边界（2026-11-26 起）

- `WakeRequestReceiver` 仍然导出（伴侣应用要靠广播拉起全屏来电），但声明了
  `android:permission="com.prts.client.permission.WAKE_REQUEST"`，`protectionLevel="signature"`：
  只有与 L2D App 用同一把钥匙签名的应用能投递，其他应用会被系统直接拒绝。
- `WakeCallActivity` 不再导出，只能由本进程（`PresenceService`、`CallKeepAliveService`、
  来电通知的 `PendingIntent`）显式启动。
- 旧版本把常量 `WAKE_TOKEN` 当口令；源码公开后该常量等于公开，不能当作安全边界，已删除。
  自己编译的用户若要启用伴侣唤醒，需要用同一 keystore 构建伴侣应用，并在其 Manifest 里声明
  `<uses-permission android:name="com.prts.client.permission.WAKE_REQUEST" />`。
