package com.yaoahos.mypureplayer.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import com.yaoahos.mypureplayer.media.Thumbnails
import java.io.File

/**
 * 保险箱：视频以 AES 加密形式存放在应用私有目录，媒体库和文件管理器都看不到。
 * 进入需要 4-20 位 PIN 或（已启用时）指纹。
 *
 * 播放时把文件解密到 `cacheDir/vault_play` 的临时文件，离开播放页即删除。
 */
class VaultRepo(
    private val context: Context,
    /**
     * 内容被解密放回原目录后回调「哪一条 → 落到了哪个路径」。
     *
     * 用途是把 `vault:<id>` 的播放进度迁到原路径，并在历史里补回一条记录
     * （用户要求：移出保险箱后要重新记录历史）。回调在保险箱的锁之外执行，
     * 扫库与写 JSON 不会堵住其它保险箱操作。
     */
    private val onRestored: suspend (List<Pair<VaultEntry, String>>) -> Unit = {},
) {

    private val root: File
        get() = File(context.getExternalFilesDir(null) ?: context.filesDir, "vault")

    private val playCache: File
        get() = File(context.cacheDir, "vault_play")

    private val entryStore = JsonStore(
        File(context.filesDir, "vault.json"),
        ListSerializer(VaultEntry.serializer()),
    ) { emptyList() }

    private val configStore = JsonStore(
        File(context.filesDir, "vault_config.json"),
        VaultConfig.serializer(),
    ) { VaultConfig() }

    private val _entries = MutableStateFlow<List<VaultEntry>>(emptyList())
    val entries: StateFlow<List<VaultEntry>> = _entries.asStateFlow()

    private val _config = MutableStateFlow(VaultConfig())
    val config: StateFlow<VaultConfig> = _config.asStateFlow()

    private val _unlocked = MutableStateFlow(false)
    val unlocked: StateFlow<Boolean> = _unlocked.asStateFlow()

    private val mutex = Mutex()

    /**
     * 条目 id 的发号器：与 [TrashRepo] 同一套做法。
     *
     * 原来用 `System.currentTimeMillis() + index`：同一批次内 index 不同还安全，
     * 但跨批次（时钟被回拨、或两次添加落在同一毫秒）就会撞号。
     * 撞号的后果很实在：`items(key = { it.id })` 直接抛异常导致保险箱页打不开，
     * `deletePermanently` 按 id 过滤会把另一条一起删掉。
     */
    private val lastId = java.util.concurrent.atomic.AtomicLong(0)

    private val secureRandom = java.security.SecureRandom()

    /** 4 字节随机串（8 位十六进制）：让密文文件名不会因为批次/时钟而重名 */
    private fun randomHex(): String {
        val bytes = ByteArray(4)
        secureRandom.nextBytes(bytes)
        return bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }

    private fun nextId(): Long {
        while (true) {
            val prev = lastId.get()
            val next = maxOf(System.currentTimeMillis(), prev + 1)
            if (lastId.compareAndSet(prev, next)) return next
        }
    }

    /**
     * 把历史数据里重复的 id 重新发号，并把基线抬到最大值之上。
     *
     * 注意一个代价：`vault:<id>` 是播放进度的键，改 id 的那一条会丢掉续播位置。
     * 但它本来就和别人共用一个键、进度也是串的，改掉比一直串着好。
     */
    private fun ensureUniqueIds(list: List<VaultEntry>): List<VaultEntry> {
        val maxExisting = list.maxOfOrNull { it.id } ?: 0L
        lastId.updateAndGet { prev -> maxOf(prev, maxExisting) }
        val seen = HashSet<Long>(list.size)
        var changed = false
        val out = list.map { entry ->
            if (seen.add(entry.id)) {
                entry
            } else {
                changed = true
                entry.copy(id = nextId())
            }
        }
        return if (changed) out else list
    }

    /** 目录所在卷的可用字节数；取不到时返回 [Long.MAX_VALUE]，宁可放行也不误伤 */
    private fun freeBytes(dir: File): Long = runCatching {
        android.os.StatFs(dir.absolutePath).availableBytes
    }.getOrDefault(Long.MAX_VALUE)

    /** 人类可读的体积，只用于错误提示 */
    private fun readSize(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format(java.util.Locale.US, "%.1f GB", bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> String.format(java.util.Locale.US, "%.0f MB", bytes / (1L shl 20).toDouble())
        else -> String.format(java.util.Locale.US, "%.0f KB", bytes / 1024.0)
    }

    /**
     * 自动上锁：解锁后 5 分钟无操作就收回。
     *
     * 之前只有「离开保险箱页面立即上锁」一条规则，副作用是隐藏视频、从播放页
     * 返回这些正常操作都会撞上"再输一遍密码"，看起来就像功能坏了。现在改成
     * 会话式：页内跳转不打断，切后台（ON_STOP）立刻锁，前台闲置 5 分钟也锁。
     */
    private val autoLockScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + Dispatchers.Default,
    )
    private var autoLockJob: kotlinx.coroutines.Job? = null

    val hasPin: Boolean get() = _config.value.enabled

    suspend fun load() {
        val stored = configStore.read()
        // 不在冷启动时解密钥：v3 密钥需要 PIN 才能解，必须等用户验证。
        // 旧格式（仅 Keystore 包裹）保持原样，解锁时会走对应的兼容解包路径。
        _config.value = stored
        val raw = entryStore.read()
        val sorted = raw.sortedByDescending { it.addedAt }
        val healed = ensureUniqueIds(sorted)
        // 把「密文在、索引里没有」的文件补回来（见 adoptOrphans）。放在 load 而不是
        // 只在进入保险箱时扫：隐藏失败的后果是文件对用户不可见，越早补回来越好。
        val adopted = withContext(Dispatchers.IO) { adoptOrphans(healed) }
        val all = (adopted + healed).sortedByDescending { it.addedAt }
        _entries.value = all
        // 有认领或自愈结果就回写，失败由 writeEntries 兜住（下次启动还能再扫一遍）
        if (all != sorted) writeEntries(all)
    }

    /**
     * 认领「密文在、索引里没有」的文件。
     *
     * 隐藏的落盘顺序是「加密写完 → 删原文件 → 记索引」。最后一步失败（空间不足、
     * 目录不可写）时密文已经在 vault 里、原文件也删掉了，索引却不知道它 ——
     * 界面上完全看不到这个文件，磁盘却一直被占着，用户理解成「文件丢了」。
     * 索引写失败本身可以下次再写，但前提是文件找得回来，所以加载时扫一遍目录。
     *
     * 文件名里带着加密时刻（`v_<毫秒>_<序号>_<随机>.bin`），拿不到原名就按它生成
     * 一个可排序的占位名：至少能播、能删、能移出，而不是变成不可见的占用。
     * 加密中断留下的 `.tmp` 没有完整末尾、解不开，直接清掉。
     */
    private fun adoptOrphans(known: List<VaultEntry>): List<VaultEntry> {
        val dir = root
        if (!dir.isDirectory) return emptyList()
        val knownNames = known.mapTo(HashSet()) { it.storedName }
        val out = mutableListOf<VaultEntry>()
        dir.listFiles().orEmpty().forEach { f ->
            if (!f.isFile) return@forEach
            if (f.name.endsWith(".tmp")) {
                runCatching { f.delete() }
                return@forEach
            }
            if (!f.name.endsWith(".bin") || f.name in knownNames) return@forEach
            val millis = ORPHAN_STAMP.find(f.name)?.groupValues?.get(1)?.toLongOrNull()
                ?: f.lastModified().takeIf { it > 0 }
                ?: System.currentTimeMillis()
            val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmm", java.util.Locale.US)
                .format(java.util.Date(millis))
            val placeholder = "已加密视频_$stamp.mp4"
            out += VaultEntry(
                id = nextId(),
                title = "已加密视频_$stamp",
                fileName = placeholder,
                originalPath = "",
                sizeBytes = f.length(),
                addedAt = millis,
                storedName = f.name,
            )
        }
        return out
    }

    /**
     * 按「原文件路径」查保险箱条目 id。
     *
     * 隐藏完成后紧接着要用它：视频进了保险箱，播放器那边拿到的 id 是 `vault:<id>`，
     * 隐藏前记在历史里的观看位置必须挂到这个键上，否则保险箱里从头开始播。
     * 内存里没有（冷启动后保险箱还没打开过）就直接读一次索引文件 ——
     * 这个方法都在 IO 协程里调用，读一次小 JSON 不影响界面。
     */
    suspend fun entryIdForOriginalPath(path: String): Long? {
        if (path.isBlank()) return null
        _entries.value.firstOrNull { it.originalPath == path }?.let { return it.id }
        return runCatching { entryStore.read() }
            .getOrNull()
            ?.firstOrNull { it.originalPath == path }
            ?.id
    }

    // ------------------------------------------------------------ 门禁

    /**
     * PIN 4~20 位，允许字母数字。
     *
     * 下限从 6 放到 4（第 19 轮用户要求）：四位数字是手机上最顺手的长度。
     * 真正保护内容的是「PBKDF2 派生 + Keystore 封装 + 5 次失败退避锁定」，
     * 而不是长度本身；4 位方便日常使用，愿意更长也随便输。
     */
    fun isPinValid(pin: String): Boolean =
        pin.length in MIN_PIN..MAX_PIN && pin.all { it.isLetterOrDigit() }

    /**
     * 把输入规整成半角字母数字。
     *
     * 中文输入法在中文状态下会把数字打成全角（１２３４），全角字符同样
     * `isLetterOrDigit` 为 true，于是"能设进去、却怎么都解不开"——
     * 实测就是这样踩的。这里统一做 NFKC 归一（全角→半角）再过滤，
     * 任何键盘状态、粘贴进来的文本都落到同一套字符集。
     */
    fun normalizePin(raw: String): String =
        java.text.Normalizer.normalize(raw, java.text.Normalizer.Form.NFKC)
            .filter { it in '0'..'9' || it in 'a'..'z' || it in 'A'..'Z' }
            .take(MAX_PIN)

    /** 最近一次设置 PIN 失败的原因（供界面给出可行动的提示） */
    var lastSetPinError: String? = null
        private set

    /**
     * 首次设置或修改 PIN。
     *
     * [biometric] 只表示「用户希望在设置完成后开启指纹」，真正的封装必须等
     * BiometricPrompt 认证成功拿到 Cipher 之后再做（见 [setBiometricWithCipher]），
     * 否则 Keystore 会以 UserNotAuthenticatedException 拒绝，指纹永远开不起来。
     *
     * [currentPin] 只在「磁盘上已有一把包裹好的数据密钥、但内存里没有」时需要：
     * 用来就地解出原来的 DEK 再重包。**绝不能绕过它去新建密钥**——
     * 那会把已有密文的密钥换掉，文件从此永远解不开（见下）。
     */
    suspend fun setPin(
        rawPin: String,
        biometric: Boolean = false,
        currentPin: String? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        lastSetPinError = null
        val pin = normalizePin(rawPin)
        if (!isPinValid(pin)) {
            lastSetPinError = "密码需为 $MIN_PIN~$MAX_PIN 位字母或数字"
            return@withContext false
        }
        val cfg = _config.value

        // 关键：改密码只换「包裹 DEK 的钥匙」，DEK 本身必须保持不变。
        //
        // 旧实现是 `cryptoKeyFromMemory() ?: 新建 DEK`：只要此刻内存里没有密钥
        // （冷启动、自动上锁、切过后台），设置/修改密码就会悄悄换一把新 DEK，
        // 而磁盘上的密文全是旧 DEK 加密的 —— 表现就是「保险箱里的视频打不开、
        // 移不出来、关闭保险箱后文件全没了」，且无法恢复。
        // 现在：内存没有就拿 [currentPin] 现解一次；解不出来且已经存在包裹密钥时，
        // 直接拒绝，绝不覆盖。
        var existingKey = cryptoKeyFromMemory()
        if (existingKey == null && cfg.wrappedKey.isNotBlank()) {
            val candidate = currentPin?.let { normalizePin(it).ifBlank { it } }
            if (candidate != null) {
                existingKey = runCatching {
                    Crypto.unlockWithPin(candidate, cfg.salt, cfg.pinHash, cfg.wrappedKey)
                }.getOrNull()
            }
            if (existingKey == null) {
                lastSetPinError = "保险箱里已有加密文件，请先用原密码解锁再改密码，否则文件会解不开"
                return@withContext false
            }
        }
        runCatching {
            val salt = Crypto.newSalt()
            val finalKey = existingKey ?: Crypto.newWrappedKey(pin, salt).second
            val newWrapped = Crypto.rewrapKey(finalKey, pin, salt)
            Crypto.installKey(finalKey)

            val next = _config.value.copy(
                pinHash = Crypto.sealPinHash(pin, salt),
                salt = salt,
                enabled = true,
                // 换 PIN 会换盐，旧的指纹密文已无法再包同一把 DEK，先清掉等待重新录入
                biometric = false,
                wrappedKey = newWrapped,
                bioWrappedKey = "",
                failedAttempts = 0,
                lockedUntil = 0L,
            )
            if (!writeConfig(next)) error("写入配置失败，请检查存储空间")
            _config.value = next
            _unlocked.value = true
            scheduleAutoLock()
            true
        }.onFailure {
            android.util.Log.e("VaultRepo", "setPin failed", it)
            lastSetPinError = "设置密码失败：${it.message ?: it.javaClass.simpleName}"
        }.getOrDefault(false)
    }

    /**
     * 单独开关指纹解锁，不改密码。
     * 关闭：清掉指纹密文（Keystore 里的生物密钥留着无害，下次开启会重新封装）。
     * 开启必须走 [setBiometricWithCipher]：需要 BiometricPrompt 认证过的 Cipher。
     */
    suspend fun setBiometric(enabled: Boolean): Boolean = withContext(Dispatchers.IO) {
        val cfg = _config.value
        if (!cfg.enabled) return@withContext false
        if (!enabled) {
            val next = cfg.copy(biometric = false, bioWrappedKey = "")
            if (!writeConfig(next)) return@withContext false
            _config.value = next
            return@withContext true
        }
        // 没有认证过的 Cipher 时无法开启：调用方应先用 beginBioEnrollCipher 弹指纹
        false
    }

    /** 开启指纹解锁用的加密 Cipher（交给 BiometricPrompt 作为 CryptoObject） */
    fun beginBioEnrollCipher(): javax.crypto.Cipher? = Crypto.beginBioWrapCipher()

    /**
     * 指纹认证成功后用它返回的 Cipher 封装当前数据密钥，正式开启指纹解锁。
     * 只有已解锁会话（内存里有 DEK）才能做，这也顺便保证「本人 + 已知密码」双条件。
     */
    suspend fun setBiometricWithCipher(cipher: javax.crypto.Cipher): Boolean =
        withContext(Dispatchers.IO) {
            val cfg = _config.value
            if (!cfg.enabled) return@withContext false
            val key = cryptoKeyFromMemory() ?: return@withContext false
            val bio = Crypto.wrapWithBioCipher(cipher, key) ?: return@withContext false
            val next = cfg.copy(biometric = true, bioWrappedKey = bio)
            if (!writeConfig(next)) return@withContext false
            _config.value = next
            true
        }

    /** 当前内存中的密钥（由 verifyPin / 解锁流程安装），用于改 PIN 时重包 */
    private fun cryptoKeyFromMemory(): javax.crypto.spec.SecretKeySpec? =
        Crypto.currentKeyOrNull()

    /** 剩余锁定时间（毫秒），0 表示未锁定 */
    fun lockRemainingMs(): Long =
        (_config.value.lockedUntil - System.currentTimeMillis()).coerceAtLeast(0L)

    /** PIN 输错的震动反馈（best-effort，无震动硬件的设备静默跳过） */
    fun vibrateWrongPin(context: android.content.Context) {
        runCatching {
            val vib = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                val manager = context.getSystemService(android.content.Context.VIBRATOR_MANAGER_SERVICE) as? android.os.VibratorManager
                manager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(android.content.Context.VIBRATOR_SERVICE) as? android.os.Vibrator
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                vib?.vibrate(android.os.VibrationEffect.createOneShot(90, 160))
            } else {
                @Suppress("DEPRECATION")
                vib?.vibrate(90L)
            }
        }
    }

    /**
     * 校验 PIN 并解出数据密钥。
     *
     * 两点关键：
     *  - PBKDF2 在主线程跑会直接把界面冻住（旧版 60 万次 × 2 次要几秒），必须放到后台；
     *  - 校验与解包共用一次派生（[Crypto.unlockWithPin]），比原来快一倍。
     * 打开成功后顺手把旧格式（60 万次 / 旧包装）升级成当前格式，下次解锁更快。
     */
    suspend fun verifyPin(rawPin: String): Boolean {
        val pin = normalizePin(rawPin)
        val cfg = _config.value
        if (!cfg.enabled || cfg.salt.isBlank()) return false
        if (lockRemainingMs() > 0L) return false

        // 候选口令：先用归一化后的半角串；若不通过，再退回用户原样输入。
        // 旧版本（第 19 轮之前）把输入直接落进哈希，中文输入法打出的全角
        // 数字会原样存下来，升级后只认半角就会"密码明明没记错却解不开"。
        // 用原串兜底命中后立刻把摘要与包装改写到归一化串上，之后只需一次尝试。
        val candidates = buildList {
            add(pin)
            val raw = rawPin.trim()
            if (raw.isNotEmpty() && raw != pin) add(raw)
        }

        var key: javax.crypto.spec.SecretKeySpec? = null
        var used: String? = null
        for (candidate in candidates) {
            val k = withContext(Dispatchers.Default) {
                Crypto.unlockWithPin(candidate, cfg.salt, cfg.pinHash, cfg.wrappedKey)
            }
            if (k != null) { key = k; used = candidate; break }
        }

        if (key != null) {
            Crypto.installKey(key)
            _unlocked.value = true
            scheduleAutoLock()
            val canonical = pin.ifBlank { used ?: pin }
            var next = cfg.copy(failedAttempts = 0, lockedUntil = 0L)
            // 旧格式自动升级：重写摘要与包装，下次解锁只跑一次 10 万次派生；
            // 同时把用旧串命中的情况一并收敛到归一化串。
            if (used != canonical ||
                !cfg.pinHash.startsWith(KH2_PREFIX) ||
                (!cfg.wrappedKey.isNullOrBlank() && !cfg.wrappedKey.startsWith(V4_PREFIX))
            ) {
                runCatching {
                    val upgraded = next.copy(
                        pinHash = Crypto.sealPinHash(canonical, cfg.salt),
                        wrappedKey = Crypto.rewrapKey(key, canonical, cfg.salt),
                    )
                    configStore.write(upgraded)
                    next = upgraded
                }
            }
            _config.value = next
            return true
        }

        val attempts = cfg.failedAttempts + 1
        // 5 次后开始退避：5 次 30s，6 次 2min，之后每次翻倍，上限 24h
        val lockedUntil = if (attempts >= 5) {
            val minutes = 1L shl (attempts - 5)
            System.currentTimeMillis() + (minutes * 60_000L).coerceAtMost(24L * 3600_000L)
        } else 0L
        val next = cfg.copy(failedAttempts = attempts, lockedUntil = lockedUntil)
        // 计数只是节流用途，写失败不影响本次判定，更不能让解锁页闪退
        writeConfig(next)
        _config.value = next
        return false
    }

    companion object {
        /** 密码长度下限 / 上限：与设置页、解锁页的提示文案保持一致 */
        /** 前台闲置多久自动上锁 */
        const val AUTO_LOCK_MS = 5L * 60L * 1000L
        const val MIN_PIN = 4
        const val MAX_PIN = 20

        private const val KH2_PREFIX = "KH2:"
        private const val V4_PREFIX = "V4:"

        /** 密文名里的加密时刻：`v_<13 位毫秒>_<序号>_<随机串>.bin` */
        private val ORPHAN_STAMP = Regex("^v_(\\d{13})_")
    }

    /** 指纹认证成功后调用：解密器已由 BiometricPrompt 的 CryptoObject 解出密钥 */
    fun onBiometricUnlocked(cipher: javax.crypto.Cipher): Boolean {
        val ok = Crypto.unwrapWithBioCipher(cipher)
        if (ok) {
            _config.value = _config.value.copy(failedAttempts = 0, lockedUntil = 0L)
            _unlocked.value = true
            scheduleAutoLock()
        }
        return ok
    }

    /** 准备指纹解锁用的 Cipher（需在 BiometricPrompt 中作为 CryptoObject 使用） */
    fun bioCipher(): javax.crypto.Cipher? = Crypto.bioDecryptCipher(_config.value.bioWrappedKey)

    fun lock() {
        // 一并清掉内存中的数据密钥：锁定后内存里不应残留可解密的密钥材料。
        // 播放中的临时解密文件是独立明文副本，由播放器在退出时清理，不受影响。
        autoLockJob?.cancel()
        autoLockJob = null
        Crypto.clearKey()
        _unlocked.value = false
    }

    private fun scheduleAutoLock() {
        autoLockJob?.cancel()
        autoLockJob = autoLockScope.launch {
            kotlinx.coroutines.delay(AUTO_LOCK_MS)
            lock()
        }
    }

    /** 关闭保险箱的结果：移出成功数 / 失败数 / 给用户看的一句话 */
    data class DisableResult(val restored: Int, val failed: Int, val message: String)

    /**
     * 关闭保险箱：把内容全部解密回原目录，全部成功后才清除 PIN。
     *
     * 这里以前是无条件清门禁的，只要有一个文件没搬出去（原目录不可写、
     * 空间不足、文件已经被别处删掉），它就会留在加密目录里，而 PIN 已经被抹掉 ——
     * 文件还在，但**永远解不开了**，用户看到的就是「关闭保险箱后文件全丢了」。
     * 现在改成：只要还有没搬出去的，就保留门禁和密钥，并把原因说清楚，
     * 让他先修好权限/空间再关一次。
     */
    suspend fun disable(
        onProgress: (Int, Int, Float) -> Unit = { _, _, _ -> },
    ): DisableResult {
        val restoredOut = mutableListOf<Pair<VaultEntry, String>>()
        val result = withContext(Dispatchers.IO) {
            mutex.withLock {
                val list = _entries.value
                if (Crypto.currentKeyOrNull() == null) {
                    return@withLock DisableResult(
                        0,
                        list.size,
                        "保险箱已锁定，请先解锁再关闭",
                    )
                }
                var restored = 0
                var failed = 0
                var firstError: String? = null
                list.forEachIndexed { index, entry ->
                    if (restoreInternal(entry, restoredOut) { p -> onProgress(index, list.size, p) } != null) {
                        restored++
                    } else {
                        failed++
                        if (firstError == null) firstError = lastRestoreError
                    }
                }
                if (failed > 0) {
                    return@withLock DisableResult(
                        restored,
                        failed,
                        "有 $failed 个视频没能移出（${firstError ?: "原因未知"}），" +
                            "保险箱已保留，避免文件再也解不开",
                    )
                }
                // 内容已全部解密移出，此时清门禁才是安全的。
                // 写不进去就保持原状：视频都已经在原处了，用户下次还能再关一次；
                // 反过来只清内存不清盘，重启后门禁还在、密文却空了，更难解释。
                if (!writeConfig(VaultConfig())) {
                    return@withLock DisableResult(
                        restored,
                        0,
                        "视频已全部移出，但关闭保险箱失败，请重试",
                    )
                }
                _config.value = VaultConfig()
                autoLockJob?.cancel()
                autoLockJob = null
                Crypto.clearKey()
                _unlocked.value = false
                // 加密目录里只剩空壳时一并清掉，避免占用空间、也让下次是干净状态
                runCatching { if (root.listFiles()?.isEmpty() != false) root.deleteRecursively() }
                // 逐条移出时已经把图搬回本地；这里兜底清一次，防止有孤儿条目留下明文封面
                runCatching { Thumbnails.dropAllVaultThumbs(context) }
                DisableResult(restored, 0, "已移出 $restored 个视频，保险箱已关闭")
            }
        }
        if (restoredOut.isNotEmpty()) onRestored(restoredOut)
        return result
    }

    // ------------------------------------------------------------ 内容

    fun fileOf(entry: VaultEntry): File = File(root, entry.storedName)

    /**
     * 这个条目是不是「可随机读」的 MV4 密文。
     *
     * MV4 能边读边解，播放不需要先落一份明文；MV3 是整体 CBC，只能先整体解密。
     * 判断只看文件头的 magic，不做任何解密。
     */
    fun isRandomAccessible(entry: VaultEntry): Boolean =
        Crypto.probe(fileOf(entry)) is Crypto.FileFormat.Mv4

    fun totalSize(): Long = _entries.value.sumOf { it.sizeBytes }

    /** 把一批视频加密移入保险箱；[group] 用于标记整文件夹隐藏 */
    suspend fun add(
        items: List<VideoItem>,
        group: String = "",
        onProgress: (Int, Int, Float) -> Unit = { _, _, _ -> },
    ): Result<Int> = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!hasPin) return@withLock Result.failure(IllegalStateException("请先设置保险箱密码"))
            // 冷启动后数据密钥不在内存里（设计如此：必须由 PIN / 指纹解出来）。
            // 之前这里直接往下走，Crypto.encrypt 拿不到 key 抛异常 → 每条都跳过，
            // 最后返回 success(0)，界面显示"已将 0 个视频移入保险箱" ——
            // 用户看到的就是"隐藏到保险箱失败"，但没有任何可行动的原因。
            if (Crypto.currentKeyOrNull() == null) {
                return@withLock Result.failure(IllegalStateException("保险箱已锁定，请先解锁后再隐藏"))
            }
            root.mkdirs()
            // 加密要写出一份与明文等大的密文，峰值新增就是这些「明文大小」之和。
            // 不预检的话会加密到一半才失败：已完成的那些已经删了明文、也写了索引，
            // 剩下的留在原地，用户看到的是「隐藏了一部分，还有一部分没动」外加一条空间不足的报错。
            val need = items.sumOf { it.sizeBytes }
            if (need > 0L) {
                val free = freeBytes(root)
                if (need > free) {
                    return@withLock Result.failure(
                        IllegalStateException(
                            "存储空间不足：需要 ${readSize(need)}，当前可用 ${readSize(free)}",
                        ),
                    )
                }
            }
            var done = 0
            var lastError: String? = null
            val added = mutableListOf<VaultEntry>()
            // 整目录隐藏时，记下这些视频所在的目录：全部搬完之后如果它们已经空了，
            // 把空目录收掉（只删空目录，不做递归删除，绝不碰里面的非视频文件）。
            val touchedDirs = linkedSetOf<File>()

            items.forEachIndexed { index, item ->
                val path = item.localPath
                if (path == null) {
                    lastError = "这个视频没有真实文件路径"
                    return@forEachIndexed
                }
                val source = File(path)
                if (!source.exists()) {
                    lastError = "文件已不存在：${item.fileName}"
                    return@forEachIndexed
                }

                // 密文名带一段随机串：只用「毫秒 + 批次序号」时，两次添加若落在同一毫秒
                // 就会重名，加密输出会覆盖已有密文，两条索引指向同一个文件。
                val stored = "v_${System.currentTimeMillis()}_${index}_${randomHex()}.bin"
                val target = File(root, stored)
                val ok = Crypto.encrypt(source, target) { p -> onProgress(index, items.size, p) }
                if (!ok) {
                    android.util.Log.e("VaultRepo", "encrypt failed for ${item.fileName}")
                    runCatching { target.delete() }
                    lastError = "加密失败：${item.fileName}（可能空间不足）"
                    return@forEachIndexed
                }
                // 缩略图跟着条目一起进保险箱：此刻明文还在，缓存里也多半已经有图。
                // 不做解密抽帧（既慢，也违背「内容只在播放时解密」），也不共用普通
                // 缓存目录（那里的图按 LRU 淘汰，淘汰后密文再也补不回来）。
                runCatching { Thumbnails.carryIntoVault(context, item, stored) }
                // 加密成功后才删除明文
                val deleted = source.delete()
                if (!deleted) {
                    android.util.Log.e("VaultRepo", "delete plaintext failed: $path")
                    // 明文删不掉就放弃这条，避免出现「保险箱里一份、外面还看得见」
                    runCatching { target.delete() }
                    // 密文没了，带过来的封面也要一起回滚，否则留在 vault_thumbs 里成孤儿
                    runCatching { Thumbnails.dropVaultThumb(context, stored) }
                    lastError = "无法删除原文件：${item.fileName}（请检查「所有文件访问权限」）"
                    return@forEachIndexed
                }
                FileOps.scan(context, path)
                source.parentFile?.let { touchedDirs += it }

                val entry = VaultEntry(
                    id = nextId(),
                    title = item.title,
                    fileName = item.fileName,
                    originalPath = path,
                    // 只有整目录隐藏（group 非空）才记文件夹名与分组键。
                    // 单个视频的 item.folderName 是它所在的原目录 —— 拿它当分组依据
                    // 会让保险箱里凭空多出一个文件夹，看起来像"加错了东西"。
                    originalFolderName = if (group.isNotBlank()) group else "",
                    groupKey = if (group.isNotBlank()) path.substringBeforeLast('/', "") else "",
                    sizeBytes = item.sizeBytes,
                    durationMs = item.durationMs,
                    width = item.width,
                    height = item.height,
                    addedAt = System.currentTimeMillis(),
                    storedName = stored,
                )
                added += entry
                done++
                // 每加密成功一个就把索引写一次盘。
                //
                // 之前是整批结束才写一次：批量隐藏到一半（手机被杀、空间满、权限异常）
                // 时，已经加密的文件在目录里、索引里却没有它们 —— 界面上既看不到、
                // 也永远解不开，等于"文件丢了"。逐条落盘后，最坏情况也只是少藏几个。
                runCatching { writeEntries(_entries.value + entry) }
                    .onFailure { android.util.Log.e("VaultRepo", "写入保险箱索引失败", it) }
            }
            if (done == 0) {
                Result.failure(IllegalStateException(lastError ?: "没有可移入的视频"))
            } else {
                // 只搬视频、不搬目录：目录里通常还混着字幕、封面等非视频文件，
                // 那些东西必须原地留下。等视频都进保险箱之后，目录若已经空了才收掉。
                touchedDirs.forEach { d ->
                    runCatching {
                        val left = d.listFiles()
                        if (left != null && left.isEmpty()) d.delete()
                    }
                }
                Result.success(done)
            }
        }
    }

    /** 移出保险箱：解密回原目录 */
    suspend fun restore(
        entry: VaultEntry,
        onProgress: (Float) -> Unit = {},
    ): Result<String> {
        val restoredOut = mutableListOf<Pair<VaultEntry, String>>()
        // 与回收站同一条原则：对外只返回 Result，不抛异常。
        // 移出是「先解密落盘、再删密文、最后写索引」，前两步成功时文件已经在原处了，
        // 这时把索引写入异常抛给页面协程，用户看到的就是「移出成功但应用闪退」。
        val result = try {
            withContext(Dispatchers.IO) {
                mutex.withLock {
                    val path = restoreInternal(entry, restoredOut, onProgress)
                    if (path != null) Result.success(path)
                    else Result.failure(
                        IllegalStateException("移出失败：${lastRestoreError ?: "请检查存储空间与权限"}"),
                    )
                }
            }
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (t: Throwable) {
            android.util.Log.e("VaultRepo", "移出失败：${entry.fileName}", t)
            Result.failure(IllegalStateException(t.message ?: "移出失败，请重试"))
        }
        if (restoredOut.isNotEmpty()) {
            // 文件已还原，历史写失败不该反向报错，更不能让异常逃到页面
            try {
                onRestored(restoredOut)
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (t: Throwable) {
                android.util.Log.w("VaultRepo", "移出后回写历史失败", t)
            }
        }
        return result
    }

    /** 最近一次移出失败的原因，供界面/关闭保险箱时给出可行动的提示 */
    var lastRestoreError: String? = null
        private set

    private suspend fun restoreInternal(
        entry: VaultEntry,
        restoredOut: MutableList<Pair<VaultEntry, String>>,
        onProgress: (Float) -> Unit = {},
    ): String? {
        lastRestoreError = null
        // 没解锁就没有数据密钥：这时候说"解密失败"会让人以为文件坏了，
        // 实际只要输一次密码。分开报，用户才知道下一步该干什么。
        if (Crypto.currentKeyOrNull() == null) {
            lastRestoreError = "保险箱已锁定，请先解锁再移出"
            return null
        }
        val source = fileOf(entry)
        if (!source.exists()) {
            lastRestoreError = "保险箱里的文件已丢失"
            return null
        }

        // 认领回来的孤儿没有原路径（索引丢过、文件名也被随机化）：回落到公共 Movies，
        // 至少把内容放回用户能看见的地方。留空会得到 File("") → parent 为 null →
        // 解密结果落进保险箱自己的目录，等于把明文塞进加密目录，比直接失败更糟。
        val original = if (entry.originalPath.isBlank()) {
            @Suppress("DEPRECATION")
            File(
                android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_MOVIES,
                ),
                entry.fileName.ifBlank { entry.storedName },
            )
        } else {
            File(entry.originalPath)
        }
        val parent = original.parentFile
        val dir = if (parent != null && (parent.exists() || parent.mkdirs())) parent else root
        if (!dir.canWrite()) {
            // 安卓 11 起写其它应用的媒体目录需要「所有文件访问权限」，
            // 没有这个权限时解密会在写入阶段失败 —— 之前只说"移出失败"，用户无从下手
            lastRestoreError = "原目录不可写（缺少「所有文件访问权限」）"
            return null
        }
        val target = if (original.exists()) FileOps.uniqueTarget(dir, entry.fileName)
        else File(dir, entry.fileName)

        if (!Crypto.decrypt(source, target, onProgress)) {
            lastRestoreError = "解密失败：密钥与这个文件不匹配，或文件已损坏"
            runCatching { if (target.length() == 0L) target.delete() }
            return null
        }
        runCatching { source.delete() }
        // 图跟着回来：移入时是从本地缓存搬过去的，移出就搬回本地路径的 key，
        // 否则用户刚恢复出来的视频在列表里又是一枚默认图标。
        runCatching { Thumbnails.carryOutOfVault(context, entry.storedName, target.absolutePath) }

        val next = _entries.value.filterNot { it.id == entry.id && it.storedName == entry.storedName }
        writeEntries(next)
        FileOps.scan(context, target.absolutePath)
        restoredOut += entry to target.absolutePath
        return target.absolutePath
    }

    /**
     * 保险箱索引落盘 + 更新内存状态；**写失败不抛**。
     *
     * 与回收站同一条理由：移出/删除都是「先动密文文件、后记索引」，走到这里文件已经
     * 处理完了，索引只是一份可重建的缓存。把它写成致命异常，用户看到的就是
     * 「文件移出成功，然后应用闪退」。
     */
    private suspend fun writeEntries(next: List<VaultEntry>) {
        runCatching { entryStore.write(next) }.onFailure {
            android.util.Log.w("VaultRepo", "写入保险箱索引失败", it)
        }
        _entries.value = next
    }

    /**
     * 门禁配置落盘；返回是否写成功，不抛异常。
     *
     * 与索引不同，门禁写失败**必须让用户知道**（密码没存住，下次就解不开保险箱），
     * 但同样不能抛给页面协程 —— 那是闪退。所以统一改成布尔返回值，由调用方决定
     * 是提示「保存失败」还是整体失败。
     */
    private suspend fun writeConfig(next: VaultConfig): Boolean =
        runCatching { configStore.write(next) }.onFailure {
            android.util.Log.e("VaultRepo", "写入保险箱配置失败", it)
        }.isSuccess

    /** 彻底删除（不可恢复） */
    suspend fun deletePermanently(entry: VaultEntry) = deletePermanently(listOf(entry))

    /** 多选彻底删除：同一把锁内完成删除与一次落盘 */
    suspend fun deletePermanently(entries: Collection<VaultEntry>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            // 「id + 密文名」双条件：历史数据里可能有重复 id（1.0.4 及以前按毫秒发号），
            // 只按 id 过滤会把另一条不相干的记录一起从索引里抹掉。
            val keys = entries.mapTo(HashSet()) { it.id to it.storedName }
            entries.forEach {
                runCatching { fileOf(it).delete() }
                // 图是明文，条目没了必须一起清；否则「彻底删除」之后本地还留着一张画面
                runCatching { Thumbnails.dropVaultThumb(context, it.storedName) }
            }
            val next = _entries.value.filterNot { (it.id to it.storedName) in keys }
            writeEntries(next)
        }
    }

    /**
     * 解密到缓存目录用于播放，返回可直接交给播放器的本地文件。
     * 失败原因写进 [lastPlaybackError]，界面据此给不同的提示
     * （"文件没了" 和 "密钥不对/文件损坏" 对用户来说是两种完全不同的处境）。
     */
    var lastPlaybackError: String? = null
        private set

    suspend fun preparePlayback(entry: VaultEntry, onProgress: (Float) -> Unit = {}): File? =
        withContext(Dispatchers.IO) {
            lastPlaybackError = null
            if (Crypto.currentKeyOrNull() == null) {
                lastPlaybackError = "保险箱已锁定，请先解锁再播放"
                return@withContext null
            }
            val source = fileOf(entry)
            if (!source.exists()) {
                lastPlaybackError = "保险箱里的文件已丢失（${entry.fileName}）"
                return@withContext null
            }
            playCache.mkdirs()
            // 清掉上一次的临时文件，避免缓存无限增长
            runCatching { playCache.listFiles()?.forEach { it.delete() } }
            val free = freeBytes(playCache)
            if (entry.sizeBytes > 0L && entry.sizeBytes > free) {
                lastPlaybackError =
                    "存储空间不足：解密播放需要 ${readSize(entry.sizeBytes)}，可用 ${readSize(free)}"
                return@withContext null
            }
            // 临时文件名不带原片名：这里虽然位于应用私有缓存，但清理工具、adb、
            // 「文件管理」类应用都可能看到，原片名本身就是一条信息。
            // 扩展名保留：部分解码器与字幕匹配仍会参考后缀。
            val ext = entry.fileName.substringAfterLast('.', "")
                .takeIf { it.isNotBlank() && it.length <= 5 && it.all { c -> c.isLetterOrDigit() } }
            val target = File(
                playCache,
                "v_${randomHex()}${randomHex()}" + if (ext != null) ".$ext" else "",
            )
            if (Crypto.decrypt(source, target, onProgress)) {
                target
            } else {
                lastPlaybackError =
                    "解密失败：密钥与这个文件不匹配，或文件已损坏。" +
                        "若它是更早版本加密的，只能彻底删除后重新加入"
                null
            }
        }

    fun clearPlayCache() {
        runCatching { playCache.listFiles()?.forEach { it.delete() } }
    }

    fun entryToVideoItem(entry: VaultEntry, localFile: File): VideoItem = VideoItem(
        id = "vault:${entry.id}",
        title = entry.title,
        fileName = entry.fileName,
        uri = localFile.toURI().toString(),
        source = MediaSource.LOCAL,
        durationMs = entry.durationMs,
        sizeBytes = entry.sizeBytes,
        dateModified = entry.addedAt,
        folderName = entry.originalFolderName,
        folderKey = "vault",
        width = entry.width,
        height = entry.height,
        localPath = localFile.absolutePath,
    )
}
