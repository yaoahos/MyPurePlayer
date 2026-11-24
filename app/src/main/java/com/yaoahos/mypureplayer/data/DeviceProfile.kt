package com.yaoahos.mypureplayer.data

import android.os.Build

/**
 * 云端备份「一设备一份」的命名与身份。
 *
 * 文件名形如 `MyPurePlayer-config-Xiaomi_14-3f9a.mpp`：
 * 前半是可读的设备名（默认取设备型号，用户可改），后半是 4 位短码。
 *
 * **为什么还要短码**：同一型号的两台设备（家里两台同款手机、公司配的同款平板）
 * 型号串完全一致，只靠设备名会互相覆盖——那正是「不同设备不能盖掉彼此」要避免的。
 * 短码首次生成后固定不变，改名也不变，因此能稳定区分「同一台」与「另一台」。
 */
object DeviceProfile {

    private const val PREFIX = "MyPurePlayer-config-"
    private const val ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyz"

    /** 4 位短码：首次使用时生成并落盘，之后不再变。 */
    fun newId(): String = (0 until 4)
        .map { ALPHABET.random() }
        .joinToString("")

    /** 显示名：用户改过就用改过的，否则用只读的设备型号。 */
    fun displayName(name: String): String =
        name.trim().ifBlank { hardwareName() }

    /**
     * 本机默认名：优先读**只读的设备型号**（`ro.product.marketname` 等系统属性）。
     * 部分 ROM 的 [Build.MODEL] 填的是认证型号（24018RPACC 这类），直接展示没人认得；
     * 属性都取不到时再回落「厂商 + 型号」。
     */
    fun hardwareName(): String {
        val fromProperty = MODEL_PROPS.firstNotNullOfOrNull { key ->
            systemProperty(key)?.trim()?.takeIf { it.isNotEmpty() }
        }
        if (fromProperty != null) return fromProperty
        val maker = Build.MANUFACTURER.trim()
        val model = Build.MODEL.trim()
        val joined = if (maker.isNotEmpty() && !model.lowercase().startsWith(maker.lowercase())) {
            "$maker $model"
        } else model
        return joined.trim().ifBlank { "Android" }
    }

    private val MODEL_PROPS = listOf(
        "ro.product.marketname",
        "ro.product.odm.marketname",
        "ro.product.vendor.marketname",
        "ro.product.system.marketname",
    )

    /**
     * 读系统属性：先反射 `android.os.SystemProperties.get`，被隐藏 API 限制拦下时
     * 退回 `getprop` 命令。两者都失败返回 null。
     */
    private fun systemProperty(key: String): String? {
        runCatching {
            val get = Class.forName("android.os.SystemProperties")
                .getMethod("get", String::class.java)
            (get.invoke(null, key) as? String)?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return runCatching {
            val process = Runtime.getRuntime().exec(arrayOf("getprop", key))
            process.inputStream.bufferedReader().use { it.readText() }.trim().takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    /** 文件名里只留字母、数字、中文、下划线与连字符，避免 WebDAV 端拼路径出岔。 */
    fun slug(text: String): String = text.trim()
        .replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_")
        .replace(Regex("_+"), "_")
        .trim('_')
        .ifBlank { "device" }

    fun fileName(name: String, id: String): String =
        PREFIX + slug(displayName(name)) + "-" + id + ConfigBackup.EXTENSION

    /** 从文件名反解出（设备名, 短码）；不是本应用的设备备份返回 null。 */
    fun parse(fileName: String): Pair<String, String>? {
        if (!fileName.startsWith(PREFIX) || !fileName.endsWith(ConfigBackup.EXTENSION)) return null
        val body = fileName.removePrefix(PREFIX).removeSuffix(ConfigBackup.EXTENSION)
        val dash = body.lastIndexOf('-')
        if (dash <= 0 || dash == body.length - 1) return null
        val id = body.substring(dash + 1)
        if (id.length != 4) return null
        return body.substring(0, dash).replace('_', ' ').trim() to id
    }

    /** 「Xiaomi 14 · 3f9a」 */
    fun label(name: String, id: String): String =
        if (id.isBlank()) displayName(name) else "${displayName(name)} · $id"
}
