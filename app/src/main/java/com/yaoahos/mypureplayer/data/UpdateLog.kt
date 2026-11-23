package com.yaoahos.mypureplayer.data

import android.content.Context

/** 一条版本更新记录：日期 + 版本号 + 逐条说明。 */
data class ReleaseNote(
    val date: String,
    val version: String,
    val notes: List<String>,
)

/**
 * 应用内更新日志。数据来自 assets/updateLog.md，格式如下：
 *
 * ```
 * **2026/11/16 · 1.1.0**
 * - 修复 SMB 网络视频同名字幕加载
 * ```
 */
object UpdateLog {
    private const val ASSET_NAME = "updateLog.md"

    private val HEADER = Regex("""^(\d{4}[-/]\d{1,2}[-/]\d{1,2})(?:\s*[·\-—]\s*|\s+)?(v?\d+(?:\.\d+)*)?$""")
    private val BULLET = Regex("""^[-*+•]\s+""")

    fun load(context: Context): List<ReleaseNote> = runCatching {
        context.assets.open(ASSET_NAME).bufferedReader().use { parse(it.readText()) }
    }.getOrDefault(emptyList())

    internal fun parse(text: String): List<ReleaseNote> {
        val result = mutableListOf<ReleaseNote>()
        var date = ""
        var version = ""
        val notes = mutableListOf<String>()

        fun flush() {
            if (date.isNotBlank() && notes.isNotEmpty()) {
                result += ReleaseNote(date, version.removePrefix("v"), notes.toList())
            }
            notes.clear()
        }

        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            when {
                line.startsWith("**") && line.endsWith("**") && line.length in 4..40 -> {
                    flush()
                    val body = line.trim('*').trim()
                    val match = HEADER.matchEntire(body)
                    date = match?.groupValues?.getOrNull(1) ?: body
                    version = match?.groupValues?.getOrNull(2).orEmpty()
                }

                BULLET.containsMatchIn(line) -> {
                    val clean = BULLET.replace(line, "")
                        .replace("**", "")
                        .replace("`", "")
                        .trim()
                    if (clean.isNotBlank()) notes += clean
                }
            }
        }
        flush()
        return result
    }
}
