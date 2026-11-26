package com.prts.client.audio

import java.io.File

/**
 * 手机侧诊断落盘。这台机器的 logcat 被 ROM 关掉了，`Log.i` 只能当场看，
 * 出问题后没有任何东西可查，所以关键生命周期直接写进应用私有目录：
 *
 * - `files/playback.log`：播放 start/prepared/complete/fail/cancel、打断请求来源、
 *   自动收音的判定结果（这句是不是在 barge 模式下开始的）。
 * - `files/barge.log`：AutoListener 的增益/门限窗口明细（沿用原来的路径）。
 * - `files/speech.log`：合成请求的每个出口与起/成/败（`enq` / `skip *` / `queued` /
 *   `req start|ok|fail` / `gen_fail` / `cancel`），用来分辨「手机没发」和「发了被拒」。
 *
 * 只写时间戳、事件名、数值和判定结果；不写台词、回复 ID 或 PCM。
 * **例外（2026-11-23 主人要求）**：`speech.log` 的 `queued … first=<前 20 字>` 会写一小段台词，
 * 因为「这一条到底送没送合成」必须能和中继那行文本对上；其余文件与字段一律不写台词。
 *
 * 2026-11-24 改：每个文件超过 [write] 的 `limitBytes`（默认 256 KB）时**不再整份清零**，
 * 改成残留式轮转 —— `xxx.log` 改名成 `xxx.log.1`（覆盖旧 `.1`），再新建空的继续写。
 * 这样 `files/` 下每个日志最多两代、总量 ≤ 2×limitBytes（默认 512 KB），
 * 上一代内容始终留得住（11-23 17:17 那轮真机验收数据就是被整份清零抹掉的）。
 * 跨版本装机不会清（`install -r` 保留 app 数据，`.1` 也跟着保留）。
 */
object DiagLog {
    @Volatile private var dir: File? = null

    fun install(filesDir: File) {
        dir = filesDir
    }

    /** 追加一行；写前若会超过 [limitBytes] 先把旧内容轮转成 `name.1`。永不抛异常。 */
    fun write(name: String, line: String, limitBytes: Long = 256L * 1024L) {
        val base = dir ?: return
        try {
            val file = File(base, name)
            val text = "${System.currentTimeMillis()} $line\n"
            val add = text.toByteArray(Charsets.UTF_8).size.toLong()
            if (file.length() + add > limitBytes) rotate(file)
            file.appendText(text)
        } catch (_: Exception) {
        }
    }

    /**
     * `name` → `name.1`（覆盖旧 `.1`，只留两代）。
     * 重命名失败（权限/被占用）就退回「删掉当前文件」，宁可丢这一代也不能无限增长。
     */
    private fun rotate(file: File) {
        val prev = File(file.parentFile, file.name + ".1")
        try {
            if (prev.exists() && !prev.delete()) throw IllegalStateException("delete .1 failed")
            if (!file.renameTo(prev)) throw IllegalStateException("rename failed")
        } catch (_: Exception) {
            try {
                file.delete()
            } catch (_: Exception) {
            }
        }
    }
}
