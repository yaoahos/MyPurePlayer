package com.yaoahos.mypureplayer.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 保险箱文件加密（信封加密 + PIN 参与派生 + 完整性校验）。
 *
 * 演进历史（必须保持向后兼容，否则用户已隐藏的视频会永久打不开）：
 * - v1/v2：Keystore 里的 KEK 直接包裹一把随机 DEK，文件用 AES/CBC 流式加密，无完整性校验。
 * - v3（当前）：DEK 仍随机生成，但包裹 DEK 的密钥由「Keystore KEK + PIN 派生密钥」共同派生，
 *   没有 PIN 就无法还原 DEK；文件改为 AES/CBC + HMAC-SHA256（Encrypt-then-MAC），可检测篡改。
 *
 * 文件格式：
 * - 旧：[16 字节 IV][CBC 密文]
 * - 新：[3 字节 "MV3"][16 字节 IV][CBC 密文][32 字节 HMAC]
 *
 * 为什么不用 Keystore 密钥直接加密文件：Keystore 密钥 encoded 为 null，交给 Cipher 会抛异常；
 * AES/GCM 走 Keystore 又会在内部缓冲整段明文，几百 MB 视频会 OOM。故用信封 + 流式 CBC + 独立 HMAC。
 */
object Crypto {

    private const val KEK_ALIAS = "mypureplayer_vault_kek_v2" // 别名保持不变，兼容旧 KEK
    private const val BIO_ALIAS = "mypureplayer_vault_bio_v1" // 指纹解锁专用：setUserAuthenticationRequired
    private const val PIN_MAC_ALIAS = "mypureplayer_vault_pinmac_v1" // PIN 摘要封印专用：只做 HMAC(SIGN/VERIFY)
    private const val TRANSFORM = "AES/CBC/PKCS5Padding"
    private const val WRAP_TRANSFORM = "AES/GCM/NoPadding"
    private const val BUFFER = 256 * 1024
    private const val TAG = "VaultCrypto"

    private const val MAGIC = "MV3"
    /**
     * PBKDF2 迭代次数。
     *
     * 旧版一律用 60 万次：一次解锁要跑两遍（校验 PIN 摘要 + 解包数据密钥），
     * 中端机上就是 2 秒起步、老机型能到 6 秒，用户感受就是「输完密码要等半天」。
     * 现在降到 10 万次，并且解锁只派生一次（见 [unlockWithPin]）；
     * PIN 摘要有 Keystore HMAC 封印，攻击者拿不到设备就无法离线穷举，
     * PBKDF2 在这里只是纵深防御，10 万次足够。
     * 旧数据仍按 60 万次校验，成功解锁后自动升级成新格式，下次就快了。
     */
    private const val PBKDF2_ITER = 100_000
    private const val PBKDF2_ITER_LEGACY = 600_000
    private const val HMAC_ALGORITHM = "HmacSHA256"
    /** 旧版包装格式（PIN 派生 60 万次） */
    private const val V3_PREFIX = "V3:"
    /** 新版包装格式（PIN 派生 10 万次） */
    private const val V4_PREFIX = "V4:"
    /** pinHash 经 Keystore KEK 封印后的标记：KH 为旧版（60 万次），KH2 为当前版本 */
    private const val PIN_SEALED_PREFIX = "KH:"
    private const val PIN_SEALED_PREFIX_V2 = "KH2:"

    @Volatile
    private var dataKey: SecretKeySpec? = null

    // ------------------------------------------------------------ KEK（Keystore）

    private fun keyStore(): KeyStore =
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun kek(): SecretKey = keystoreKey(KEK_ALIAS, userAuth = false)

    /**
     * PIN 摘要封印专用密钥：用途是 SIGN/VERIFY 的 HMAC 密钥。
     * 不能复用 kek()（那是 AES/GCM 的 ENCRYPT/DECRYPT 密钥），
     * 否则 Mac.init(kek()) 会抛 InvalidKeyException，导致设置密码失败。
     */
    private fun pinMacKey(): SecretKey {
        val ks = keyStore()
        (ks.getEntry(PIN_MAC_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val spec = KeyGenParameterSpec.Builder(
            PIN_MAC_ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
        ).setKeySize(256).build()
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, "AndroidKeyStore")
        gen.init(spec)
        return gen.generateKey()
    }

    /** 指纹解锁用的 KEK：密钥材料受系统门禁保护，必须用户认证后才能使用 */
    fun bioKey(): SecretKey = keystoreKey(BIO_ALIAS, userAuth = true)

    private fun keystoreKey(alias: String, userAuth: Boolean): SecretKey {
        val ks = keyStore()
        (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val builder = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
        if (userAuth) {
            builder.setUserAuthenticationRequired(true)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                // 0 = 每次使用都必须重新认证，不设有效期缓存
                builder.setUserAuthenticationValidityDurationSeconds(-1)
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                builder.setUnlockedDeviceRequired(true)
            }
        }
        generator.init(builder.build())
        return generator.generateKey()
    }

    /**
     * PIN 派生密钥：PBKDF2-HMAC-SHA256(pin, salt) -> 256bit
     *
     * 不用 `SecretKeyFactory("PBKDF2WithHmacSHA256")`：Android 上它走 BouncyCastle 的
     * 通用路径，实测 60 万次要 **851ms**（这台机器），用户感知就是「输完密码要等一下」。
     * PBKDF2 本身只是「一遍 HMAC 迭代 + 逐字节异或」，直接调用 Conscrypt 的
     * `Mac("HmacSHA256")` 做同样的事能快 2~3 倍，输出完全一致（有单测对齐标准实现）。
     *
     * dkLen=32 恰好是 SHA-256 一个整块，所以只需要算 T1，不需要分块循环。
     */
    internal fun pbkdf2Sha256(
        pin: String,
        salt: ByteArray,
        iterations: Int,
        dkLen: Int = 32,
    ): ByteArray {
        val hmac = javax.crypto.Mac.getInstance("HmacSHA256")
        hmac.init(SecretKeySpec(pin.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val blockCount = (dkLen + 31) / 32
        val out = ByteArray(blockCount * 32)
        val blockIndex = ByteArray(4)
        for (block in 1..blockCount) {
            // INT(i) 大端
            blockIndex[0] = ((block shr 24) and 0xFF).toByte()
            blockIndex[1] = ((block shr 16) and 0xFF).toByte()
            blockIndex[2] = ((block shr 8) and 0xFF).toByte()
            blockIndex[3] = (block and 0xFF).toByte()
            hmac.update(salt)
            hmac.update(blockIndex)
            var u = hmac.doFinal() // U1（Mac 在 doFinal 后自动复位到初始状态）
            val t = u.copyOf()
            for (i in 2..iterations) {
                u = hmac.doFinal(u)
                for (j in t.indices) {
                    t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
                }
            }
            System.arraycopy(t, 0, out, (block - 1) * 32, 32)
        }
        return out.copyOf(dkLen)
    }

    private fun pinKey(pin: String, salt: String, iter: Int = PBKDF2_ITER): SecretKeySpec =
        SecretKeySpec(pbkdf2Sha256(pin, salt.hexToBytes(), iter), "AES")

    // ------------------------------------------------------------ DEK（数据密钥）

    /**
     * 解锁专用：一次 PBKDF2 同时完成「校验 PIN」与「解包数据密钥」。
     *
     * 早期版本是「先验 PIN 摘要、再解包密钥」两次派生，60 万次迭代翻倍执行，
     * 中端机上光等密码就两三秒。新旧两种格式内部用的是同一个迭代次数，
     * 所以这里派生一次就能同时喂给摘要校验和密钥解包。
     *
     * @return 解出的数据密钥；PIN 错误 / 密文损坏返回 null（不会写入 [dataKey]）
     */
    fun unlockWithPin(
        pin: String,
        salt: String,
        storedPinHash: String,
        wrappedKey: String?,
    ): SecretKeySpec? {
        if (storedPinHash.isBlank() || salt.isBlank()) return null
        val modern = storedPinHash.startsWith(PIN_SEALED_PREFIX_V2)
        val iter = if (modern) PBKDF2_ITER else PBKDF2_ITER_LEGACY
        // 新旧混合配置（旧摘要 + 新包装，或反之）会用到两套迭代次数。
        // 同一次解锁里缓存派生结果 —— 否则 60 万次要跑两遍，中端机直接多等一秒多。
        val derivedCache = HashMap<Int, SecretKeySpec?>()
        fun derive(n: Int): SecretKeySpec? = derivedCache.getOrPut(n) {
            runCatching { pinKey(pin, salt, n) }.getOrNull()
        }
        val derived = derive(iter) ?: return null
        // 期望值必须与落盘形式逐字符一致：封印格式带 "KH2:" / "KH:" 前缀，
        // 只比 HMAC 本体（64 字符）会与落盘的 68/67 字符恒不相等——
        // 结果就是「密码刚设完、马上解不开」。带上各自的前缀再比。
        val expected = when {
            storedPinHash.startsWith(PIN_SEALED_PREFIX_V2) ->
                PIN_SEALED_PREFIX_V2 + sealDigestWith(derived)
            storedPinHash.startsWith(PIN_SEALED_PREFIX) ->
                PIN_SEALED_PREFIX + sealDigestWith(derived)
            else -> derived.encoded.toHex()
        }
        if (!pinEquals(expected, storedPinHash)) return null
        if (wrappedKey.isNullOrBlank()) return null

        val raw = when {
            wrappedKey.startsWith(V4_PREFIX) ->
                unwrapV4(wrappedKey, derive(PBKDF2_ITER) ?: return null)
            wrappedKey.startsWith(V3_PREFIX) ->
                unwrapV3(wrappedKey, derive(PBKDF2_ITER_LEGACY) ?: return null)
            else -> unwrapLegacy(wrappedKey)
        } ?: return null
        return SecretKeySpec(raw, "AES")
    }

    /** 生成全新 DEK 并用 PIN + Keystore 双因子包裹，返回 (密文, 原始密钥) */
    fun newWrappedKey(pin: String, salt: String): Pair<String, SecretKeySpec> {
        val raw = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val key = SecretKeySpec(raw, "AES")
        return wrapV4(raw, pinKey(pin, salt)) to key
    }

    /** 用新 PIN 重新包裹同一把 DEK（改密码），不重加密文件 */
    fun rewrapKey(key: SecretKeySpec, pin: String, salt: String): String {
        val raw = key.encoded ?: throw IllegalStateException("数据密钥不可导出")
        return wrapV4(raw, pinKey(pin, salt))
    }

    /**
     * 指纹解锁用的「加密」Cipher。
     *
     * 不能直接拿 bioKey() 去 doFinal：该密钥带 setUserAuthenticationRequired，
     * 系统只允许在 BiometricPrompt 认证成功后放行加密/解密。正确姿势就是
     * 先 init 出一个 Cipher、把它作为 CryptoObject 交给弹窗，认证成功后再 doFinal。
     * 旧代码在设置指纹时直接做加密，必然抛 UserNotAuthenticatedException，
     * 表现出来就是「开启指纹解锁失败」。
     */
    fun beginBioWrapCipher(): Cipher? = runCatching {
        Cipher.getInstance(WRAP_TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, bioKey()) }
    }.onFailure { Log.w(TAG, "beginBioWrapCipher failed: ${it.javaClass.simpleName}") }.getOrNull()

    /** 用已通过指纹认证的 Cipher 包裹 DEK，得到可脱机保存的密文 */
    fun wrapWithBioCipher(cipher: Cipher, key: SecretKeySpec): String? = runCatching {
        val raw = key.encoded ?: throw IllegalStateException("数据密钥不可导出")
        val combined = cipher.iv + cipher.doFinal(raw)
        "BIO:" + Base64.encodeToString(combined, Base64.NO_WRAP)
    }.onFailure { Log.w(TAG, "wrapWithBioCipher failed: ${it.javaClass.simpleName}: ${it.message}") }.getOrNull()

    /** 为指纹解锁准备 Cipher（DECRYPT_MODE），交给 BiometricPrompt.CryptoObject */
    fun bioDecryptCipher(wrapped: String): Cipher? = runCatching {
        if (!wrapped.startsWith("BIO:")) return null
        val combined = Base64.decode(wrapped.removePrefix("BIO:"), Base64.NO_WRAP)
        if (combined.size <= 28) return null
        val cipher = Cipher.getInstance(WRAP_TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, bioKey(), GCMParameterSpec(128, combined.copyOfRange(0, 12)))
        // 把待解密的密文暂存，认证成功后由 unwrapWithBioCipher 使用
        pendingBioCiphertext = combined.copyOfRange(12, combined.size)
        cipher
    }.getOrNull()

    private var pendingBioCiphertext: ByteArray? = null

    /** 指纹认证成功后，用系统返回的 Cipher 解出 DEK 并安装 */
    fun unwrapWithBioCipher(cipher: Cipher): Boolean = runCatching {
        val body = pendingBioCiphertext ?: return false
        val raw = cipher.doFinal(body)
        dataKey = SecretKeySpec(raw, "AES")
        pendingBioCiphertext = null
        true
    }.getOrDefault(false)

    fun installKey(key: SecretKeySpec) { dataKey = key }
    fun currentKeyOrNull(): SecretKeySpec? = dataKey
    fun clearKey() { dataKey = null }

    // 内层 PIN 派生密钥包 DEK，外层 Keystore KEK 再包一层
    private fun wrapV4(raw: ByteArray, pinDerived: SecretKeySpec): String {
        val innerCipher = aesGcmEncrypt(pinDerived)
        val innerBlob = innerCipher.iv + innerCipher.doFinal(raw)
        val outerCipher = aesGcmEncrypt(kek())
        val combined = outerCipher.iv + outerCipher.doFinal(innerBlob)
        return V4_PREFIX + Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    private fun unwrapV4(stored: String, pinDerived: SecretKeySpec): ByteArray? =
        unwrapInner(stored.removePrefix(V4_PREFIX), pinDerived)

    /** 旧格式包装：PIN 派生 60 万次，只用于读取历史数据 */
    private fun unwrapV3(stored: String, pinDerived: SecretKeySpec): ByteArray? =
        unwrapInner(stored.removePrefix(V3_PREFIX), pinDerived)

    private fun unwrapInner(base64Body: String, pinDerived: SecretKeySpec): ByteArray? = runCatching {
        val combined = Base64.decode(base64Body, Base64.NO_WRAP)
        if (combined.size <= 28) return null
        val outer = Cipher.getInstance(WRAP_TRANSFORM)
        outer.init(Cipher.DECRYPT_MODE, kek(), GCMParameterSpec(128, combined.copyOfRange(0, 12)))
        val innerBlob = outer.doFinal(combined.copyOfRange(12, combined.size))
        if (innerBlob.size <= 28) return null
        val inner = Cipher.getInstance(WRAP_TRANSFORM)
        inner.init(
            Cipher.DECRYPT_MODE,
            pinDerived,
            GCMParameterSpec(128, innerBlob.copyOfRange(0, 12)),
        )
        inner.doFinal(innerBlob.copyOfRange(12, innerBlob.size))
    }.getOrNull()

    private fun aesGcmEncrypt(key: SecretKey): Cipher {
        val cipher = Cipher.getInstance(WRAP_TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return cipher
    }

    private fun unwrapLegacy(stored: String): ByteArray? = runCatching {
        if (stored.startsWith(V3_PREFIX)) return null
        val combined = Base64.decode(stored, Base64.NO_WRAP)
        if (combined.size <= 12) return null
        val cipher = Cipher.getInstance(WRAP_TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, kek(), GCMParameterSpec(128, combined.copyOfRange(0, 12)))
        cipher.doFinal(combined.copyOfRange(12, combined.size))
    }.getOrNull()

    // ------------------------------------------------------------ 文件加解密
    //
    // 1.0.0 ~ 1.0.4：整文件 AES-256-CBC + 末尾 32 字节 HMAC（"MV3"）。
    // CBC 是链式模式，第 N 块必须从第 1 块算起，所以播放第 60% 的位置也得先把
    // 前 60% 解完 —— 保险箱里的视频因此只能先整体解密到缓存再播（等几十秒、占双倍空间）。
    //
    // 1.0.5 起改成 MV4：定长分块 + 每块独立 AES-GCM。每块的 nonce 由 baseNonce 和
    // 块序号拼出，因此可以任意跳读、单独校验；1MB 一块时 1.5GB 的视频就是 1500 个
    // 独立块，拖动进度条只需要解目标那一块。
    //
    // 布局：[3B "MV4"][12B baseNonce][4B blockSize][8B plainLength] + 若干「密文 + 16B tag」
    //
    // MV3 仍然可读（老文件不迁移；用户把视频移出再重新加入就自然升级成 MV4）。

    private const val MAGIC_V4 = "MV4"
    private const val BLOCK_SIZE = 1024 * 1024
    private const val GCM_TAG = 16
    private const val V4_HEADER = 3 + 12 + 4 + 8

    /** 密文格式探针的结果 */
    sealed class FileFormat {
        /** 1.0.0 ~ 1.0.4：整文件 CBC + 末尾 HMAC */
        object Mv3 : FileFormat()

        /** 1.0.5 起：分块 GCM，可随机读 */
        class Mv4(
            val baseNonce: ByteArray,
            val blockSize: Int,
            val plainLength: Long,
        ) : FileFormat()
    }

    /**
     * 读密文开头判断格式。
     *
     * 只认正式版写出过的两种：MV3 与 MV4。开发期短暂存在过的「无 magic」和
     * 「HMAC 算在明文上」两种变体从未随正式版发布，已删除对应兼容分支 ——
     * 留着只会让每次解密多走两次无用的 HMAC 计算，也让格式判断变得含糊。
     */
    fun probe(src: File): FileFormat? = runCatching {
        java.io.RandomAccessFile(src, "r").use { raf ->
            val head = ByteArray(3)
            if (raf.read(head) != 3) return null
            when (String(head, Charsets.US_ASCII)) {
                MAGIC -> FileFormat.Mv3
                MAGIC_V4 -> {
                    val rest = ByteArray(12 + 4 + 8)
                    var read = 0
                    while (read < rest.size) {
                        val n = raf.read(rest, read, rest.size - read)
                        if (n <= 0) return null
                        read += n
                    }
                    val blockSize = readIntBE(rest, 12)
                    val plainLength = readLongBE(rest, 16)
                    if (blockSize !in 64 * 1024..16 * 1024 * 1024 || plainLength < 0) return null
                    FileFormat.Mv4(rest.copyOfRange(0, 12), blockSize, plainLength)
                }
                else -> null
            }
        }
    }.getOrNull()

    /** 加密 [src] 到 [dst]（MV4 分块格式） */
    fun encrypt(src: File, dst: File, onProgress: (Float) -> Unit = {}): Boolean = runCatching {
        val key = dataKey ?: throw IllegalStateException("保险箱密钥尚未初始化")
        val plainLength = src.length()
        val baseNonce = ByteArray(12)
        // 后 4 字节留给块序号，前 8 字节随机：同一把密钥下还有 2^64 的空间
        val rnd = ByteArray(8)
        SecureRandom().nextBytes(rnd)
        System.arraycopy(rnd, 0, baseNonce, 0, 8)

        dst.parentFile?.mkdirs()
        val tmp = File(dst.parentFile, "${dst.name}.tmp")
        tmp.outputStream().use { out ->
            out.write(MAGIC_V4.toByteArray(Charsets.US_ASCII))
            out.write(baseNonce)
            out.write(intBE(BLOCK_SIZE))
            out.write(longBE(plainLength))
            if (plainLength == 0L) return@use
            src.inputStream().use { input ->
                val buffer = ByteArray(BLOCK_SIZE)
                var index = 0
                var done = 0L
                while (done < plainLength) {
                    val want = minOf(BLOCK_SIZE.toLong(), plainLength - done).toInt()
                    var read = 0
                    while (read < want) {
                        val n = input.read(buffer, read, want - read)
                        if (n < 0) break
                        read += n
                    }
                    if (read <= 0) break
                    val cipher = Cipher.getInstance(WRAP_TRANSFORM)
                    cipher.init(
                        Cipher.ENCRYPT_MODE,
                        key,
                        GCMParameterSpec(128, nonceFor(baseNonce, index)),
                    )
                    // GCM 的 doFinal 输出 = 密文 + 16 字节 tag，把 tag 直接跟在块后面
                    out.write(cipher.doFinal(buffer, 0, read))
                    done += read
                    index++
                    onProgress((done.toFloat() / plainLength.coerceAtLeast(1L)).coerceIn(0f, 1f))
                }
            }
        }
        atomicReplace(tmp, dst)
        true
    }.getOrElse { t ->
        Log.e(TAG, "encrypt failed: ${src.name}", t)
        runCatching { dst.delete() }
        false
    }

    /** 解密 [src] 到 [dst]，MV3 / MV4 都支持 */
    fun decrypt(src: File, dst: File, onProgress: (Float) -> Unit = {}): Boolean = runCatching {
        val key = dataKey ?: throw IllegalStateException("保险箱密钥尚未初始化")
        when (val format = probe(src)) {
            is FileFormat.Mv4 -> decryptMv4(src, dst, format, key, onProgress)
            FileFormat.Mv3 -> decryptMv3(src, dst, key, onProgress)
            null -> throw IllegalStateException("无法识别的加密文件格式")
        }
        true
    }.getOrElse { t ->
        Log.e(TAG, "decrypt failed: ${src.name}", t)
        runCatching { dst.delete() }
        false
    }

    /** MV4 顺序解密（移出保险箱、或老播放路径需要完整明文时用） */
    private fun decryptMv4(
        src: File,
        dst: File,
        format: FileFormat.Mv4,
        key: SecretKeySpec,
        onProgress: (Float) -> Unit,
    ) {
        val blockSize = format.blockSize
        val plainLength = format.plainLength
        val total = plainLength.coerceAtLeast(1L)
        dst.parentFile?.mkdirs()
        val tmp = File(dst.parentFile, "${dst.name}.tmp")
        java.io.RandomAccessFile(src, "r").use { raf ->
            raf.seek(V4_HEADER.toLong())
            tmp.outputStream().use { out ->
                val buffer = ByteArray(blockSize + GCM_TAG)
                var index = 0
                var done = 0L
                while (done < plainLength) {
                    val plainWant = minOf(blockSize.toLong(), plainLength - done).toInt()
                    val readLen = plainWant + GCM_TAG
                    var read = 0
                    while (read < readLen) {
                        val n = raf.read(buffer, read, readLen - read)
                        if (n <= 0) throw IllegalStateException("密文不完整")
                        read += n
                    }
                    val cipher = Cipher.getInstance(WRAP_TRANSFORM)
                    cipher.init(
                        Cipher.DECRYPT_MODE,
                        key,
                        GCMParameterSpec(128, nonceFor(format.baseNonce, index)),
                    )
                    // GCM 会校验 tag：校验失败抛 AEADBadTagException，由外层转成「解密失败」
                    out.write(cipher.doFinal(buffer, 0, readLen))
                    done += plainWant
                    index++
                    onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                }
            }
        }
        atomicReplace(tmp, dst)
    }

    /** MV3 顺序解密：整文件 CBC + 末尾 HMAC（1.0.0 ~ 1.0.4 写出的文件） */
    private fun decryptMv3(
        src: File,
        dst: File,
        key: SecretKeySpec,
        onProgress: (Float) -> Unit,
    ) {
        src.inputStream().use { input ->
            val header = ByteArray(3)
            var off = 0
            while (off < 3) {
                val read = input.read(header, off, 3 - off)
                if (read <= 0) throw IllegalStateException("密文头不完整")
                off += read
            }
            if (String(header, Charsets.US_ASCII) != MAGIC) {
                throw IllegalStateException("无法识别的加密文件格式")
            }
            val iv = ByteArray(16)
            off = 0
            while (off < 16) {
                val read = input.read(iv, off, 16 - off)
                if (read <= 0) throw IllegalStateException("密文头不完整")
                off += read
            }
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, key, IvParameterSpec(iv))

            val mac = newMac(key).apply { update(header); update(iv) }

            val totalLen = src.length() - (3L + 16L + 32L)
            val total = totalLen.coerceAtLeast(1L)
            var done = 0L
            dst.parentFile?.mkdirs()
            val tmp = File(dst.parentFile, "${dst.name}.tmp")
            tmp.outputStream().use { out ->
                val buffer = ByteArray(BUFFER)
                while (done < totalLen) {
                    val want = (totalLen - done).coerceAtMost(BUFFER.toLong()).toInt()
                    val read = input.read(buffer, 0, want)
                    if (read <= 0) break
                    done += read
                    // 校验值算在密文上（Encrypt-then-MAC）：必须先 MAC 再解密
                    mac.update(buffer, 0, read)
                    val outBytes = cipher.update(buffer, 0, read)
                    if (outBytes != null && outBytes.isNotEmpty()) out.write(outBytes)
                    onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                }
                val tail = cipher.doFinal()
                if (tail != null && tail.isNotEmpty()) out.write(tail)
                val stored = ByteArray(32)
                var s = 0
                while (s < 32) {
                    val r = input.read(stored, s, 32 - s)
                    if (r <= 0) throw IllegalStateException("密文校验值不完整")
                    s += r
                }
                if (!MessageDigest.isEqual(mac.doFinal(), stored)) {
                    throw IllegalStateException("文件校验失败：内容可能已损坏或被篡改")
                }
            }
            atomicReplace(tmp, dst)
        }
    }

    /**
     * MV4 密文的随机读取器：按需解密单个块，缓存最近一块。
     *
     * 播放器每读一段就调一次 [read]，顺序播放时命中缓存、只有换块才真正解密；
     * 拖动进度条时直接跳到目标块解（1MB 的 GCM 解密在硬件加速下是毫秒级），
     * 不需要把前面几十万个块都过一遍。
     */
    class BlockReader(private val file: File, private val key: SecretKeySpec) : java.io.Closeable {
        private val raf = java.io.RandomAccessFile(file, "r")
        private val blockSize: Int
        private val plainLength: Long
        private val baseNonce: ByteArray
        private var cachedIndex = -1
        private var cached: ByteArray? = null

        init {
            val head = ByteArray(V4_HEADER)
            var read = 0
            while (read < head.size) {
                val n = raf.read(head, read, head.size - read)
                if (n <= 0) {
                    raf.close()
                    throw java.io.IOException("保险箱文件头不完整")
                }
                read += n
            }
            if (String(head, 0, 3, Charsets.US_ASCII) != MAGIC_V4) {
                raf.close()
                throw java.io.IOException("不是可随机读取的保险箱文件")
            }
            baseNonce = head.copyOfRange(3, 15)
            blockSize = readIntBE(head, 15)
            plainLength = readLongBE(head, 19)
            if (blockSize !in 64 * 1024..16 * 1024 * 1024 || plainLength < 0) {
                raf.close()
                throw java.io.IOException("保险箱文件头损坏")
            }
        }

        /** 明文总长度（解密后的大小） */
        fun plainLength(): Long = plainLength

        /**
         * 从明文偏移 [plainOffset] 读取至多 [length] 字节到 [buffer]。
         * 返回实际读到的字节数；已在末尾则返回 -1。
         */
        fun read(plainOffset: Long, buffer: ByteArray, offset: Int, length: Int): Int {
            if (plainOffset >= plainLength) return -1
            val want = minOf(length.toLong(), plainLength - plainOffset).toInt()
            var copied = 0
            var pos = plainOffset
            while (copied < want) {
                val index = (pos / blockSize).toInt()
                val inBlock = (pos % blockSize).toInt()
                val block = blockAt(index) ?: break
                val n = minOf(block.size - inBlock, want - copied)
                if (n <= 0) break
                System.arraycopy(block, inBlock, buffer, offset + copied, n)
                copied += n
                pos += n
            }
            return if (copied == 0) -1 else copied
        }

        private fun blockAt(index: Int): ByteArray? {
            cached?.let { if (cachedIndex == index) return it }
            val plainStart = index.toLong() * blockSize
            if (plainStart >= plainLength) return null
            val plainLen = minOf(blockSize.toLong(), plainLength - plainStart).toInt()
            val offset = V4_HEADER.toLong() + index.toLong() * (blockSize + GCM_TAG)
            val buf = ByteArray(plainLen + GCM_TAG)
            raf.seek(offset)
            var read = 0
            while (read < buf.size) {
                val n = raf.read(buf, read, buf.size - read)
                if (n <= 0) return null
                read += n
            }
            val cipher = Cipher.getInstance(WRAP_TRANSFORM)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key,
                GCMParameterSpec(128, nonceFor(baseNonce, index)),
            )
            val plain = cipher.doFinal(buf)
            cached = plain
            cachedIndex = index
            return plain
        }

        override fun close() {
            runCatching { raf.close() }
            cached = null
        }
    }

    /** 块序号拼进 baseNonce 的后 4 字节（大端），保证同一文件内每块 nonce 唯一 */
    private fun nonceFor(base: ByteArray, index: Int): ByteArray {
        val nonce = base.copyOf(12)
        nonce[8] = (index ushr 24).toByte()
        nonce[9] = (index ushr 16).toByte()
        nonce[10] = (index ushr 8).toByte()
        nonce[11] = index.toByte()
        return nonce
    }

    private fun intBE(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )

    private fun longBE(value: Long): ByteArray = ByteArray(8) { i ->
        (value ushr (56 - i * 8)).toByte()
    }

    private fun readIntBE(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 24) or
            ((bytes[offset + 1].toInt() and 0xff) shl 16) or
            ((bytes[offset + 2].toInt() and 0xff) shl 8) or
            (bytes[offset + 3].toInt() and 0xff)

    private fun readLongBE(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 0 until 8) {
            value = (value shl 8) or (bytes[offset + i].toLong() and 0xff)
        }
        return value
    }

    private fun newMac(key: SecretKeySpec): Mac {
        val md = MessageDigest.getInstance("SHA-256")
        val macKey = md.digest(key.encoded!! + "mypureplayer-mac-v1".toByteArray(Charsets.US_ASCII))
        return Mac.getInstance(HMAC_ALGORITHM).apply { init(SecretKeySpec(macKey, HMAC_ALGORITHM)) }
    }

    private fun atomicReplace(tmp: File, dst: File) {
        val ok = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            runCatching {
                java.nio.file.Files.move(
                    tmp.toPath(), dst.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                )
                true
            }.getOrDefault(false)
        } else {
            if (dst.exists()) dst.delete()
            tmp.renameTo(dst)
        }
        if (!ok) {
            runCatching { tmp.delete() }
            throw IllegalStateException("文件写入失败：${dst.name}")
        }
    }

    // ------------------------------------------------------------ PIN 校验

    fun newSalt(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.toHex()
    }

    /** PBKDF2-HMAC-SHA256 派生 PIN 校验值（十六进制） */
    fun hashPin(pin: String, salt: String): String = hashPin(pin, salt, PBKDF2_ITER)

    private fun hashPin(pin: String, salt: String, iter: Int): String =
        pbkdf2Sha256(pin, salt.hexToBytes(), iter).toHex()

    /**
     * 把 PIN 摘要用 Android Keystore 中不可导出的 KEK 做 HMAC 封印后落盘。
     * 攻击者即使拷走 vault_config.json，缺少设备 Keystore 密钥也无法离线穷举 PIN；
     * root 也无法导出该密钥（Keystore/TEE 保护）。
     */
    fun sealPinHash(pin: String, salt: String): String {
        val derived = pinKey(pin, salt)
        return PIN_SEALED_PREFIX_V2 + sealDigestWith(derived)
    }

    /** 用已派生的 PIN 密钥对摘要做 HMAC 封印（供 [unlockWithPin] 复用同一次派生） */
    private fun sealDigestWith(derived: SecretKeySpec): String {
        val digest = derived.encoded.toHex()
        val mac = Mac.getInstance(HMAC_ALGORITHM).apply { init(pinMacKey()) }
        return mac.doFinal(digest.hexToBytes()).toHex()
    }

    /** 恒定时间比较，防时序侧信道 */
    fun pinEquals(a: String, b: String): Boolean =
        a.length == b.length && MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun String.hexToBytes(): ByteArray {
        val out = ByteArray(length / 2)
        for (i in out.indices) {
            out[i] = substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return out
    }
}
