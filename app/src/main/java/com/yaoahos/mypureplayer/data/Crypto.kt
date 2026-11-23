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

    /** 加密 [src] 到 [dst]，带 HMAC 完整性校验（v3 格式） */
    fun encrypt(src: File, dst: File, onProgress: (Float) -> Unit = {}): Boolean = runCatching {
        val key = dataKey ?: throw IllegalStateException("保险箱密钥尚未初始化")
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val mac = newMac(key)
        val magic = MAGIC.toByteArray(Charsets.US_ASCII)

        dst.parentFile?.mkdirs()
        val tmp = File(dst.parentFile, "${dst.name}.tmp")
        tmp.outputStream().use { out ->
            out.write(magic)
            out.write(iv)
            mac.update(magic)
            mac.update(iv)
            src.inputStream().use { input ->
                val buffer = ByteArray(BUFFER)
                val total = src.length().coerceAtLeast(1L)
                var done = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    done += read
                    val outBytes = cipher.update(buffer, 0, read)
                    if (outBytes != null && outBytes.isNotEmpty()) {
                        out.write(outBytes)
                        mac.update(outBytes)
                    }
                    onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                }
            }
            val tail = cipher.doFinal()
            if (tail != null && tail.isNotEmpty()) {
                out.write(tail)
                mac.update(tail)
            }
            out.write(mac.doFinal())
        }
        atomicReplace(tmp, dst)
        true
    }.getOrElse { t ->
        Log.e(TAG, "encrypt failed: ${src.name}", t)
        runCatching { dst.delete() }
        false
    }

    /** 解密 [src] 到 [dst]，自动识别 v3（带 HMAC）与旧格式（无校验） */
    fun decrypt(src: File, dst: File, onProgress: (Float) -> Unit = {}): Boolean = runCatching {
        val key = dataKey ?: throw IllegalStateException("保险箱密钥尚未初始化")
        src.inputStream().use { input ->
            val header = ByteArray(3)
            var off = 0
            while (off < 3) {
                val read = input.read(header, off, 3 - off)
                if (read <= 0) throw IllegalStateException("密文头不完整")
                off += read
            }
            val isV3 = String(header, Charsets.US_ASCII) == MAGIC
            val iv = ByteArray(16)
            off = 0
            while (off < 16) {
                val read = input.read(iv, off, 16 - off)
                if (read <= 0) throw IllegalStateException("密文头不完整")
                off += read
            }
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, key, IvParameterSpec(iv))

            val mac = if (isV3) {
                newMac(key).apply { update(header); update(iv) }
            } else null
            // 兼容分支：历史上有一段版本把校验值算在**明文**上，这样写出的密文
            // 用现在这版（校验密文）去打，永远对不上 —— 用户那边的文件会全部"解密失败"。
            // 两份摘要同时算，任一命中即接受：新文件走密文校验，旧文件仍能打开。
            val legacyMac = if (isV3) {
                newMac(key).apply { update(header); update(iv) }
            } else null

            val totalLen = src.length() - (if (isV3) 3L + 16L + 32L else 16L)
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
                    // 校验值必须算在**密文**上（Encrypt-then-MAC）。
                    //
                    // 这里曾经写的是 `mac.update(outBytes)` —— 那是解密后的明文。
                    // 加密时算的是密文、解密时算的是明文，两边永远不可能相等，
                    // 于是**每一个**保险箱文件都"校验失败"，视频既打不开也移不出来。
                    // 必须先对刚读到的密文做 MAC，再拿去解密。
                    mac?.update(buffer, 0, read)
                    val outBytes = cipher.update(buffer, 0, read)
                    if (outBytes != null && outBytes.isNotEmpty()) {
                        out.write(outBytes)
                        legacyMac?.update(outBytes)
                    }
                    onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                }
                // 收尾：CBC 去掉填充后的最后一段是明文，不参与校验
                val tail = cipher.doFinal()
                if (tail != null && tail.isNotEmpty()) {
                    out.write(tail)
                    legacyMac?.update(tail)
                }
                if (mac != null) {
                    val stored = ByteArray(32)
                    var s = 0
                    while (s < 32) {
                        val r = input.read(stored, s, 32 - s)
                        if (r <= 0) throw IllegalStateException("密文校验值不完整")
                        s += r
                    }
                    val fresh = mac.doFinal()
                    if (!MessageDigest.isEqual(fresh, stored)) {
                        val legacy = legacyMac?.doFinal()
                        if (legacy == null || !MessageDigest.isEqual(legacy, stored)) {
                            throw IllegalStateException("文件校验失败：内容可能已损坏或被篡改")
                        }
                        Log.w(TAG, "decrypt ${src.name} 命中旧版明文校验值（兼容模式）")
                    }
                }
            }
            atomicReplace(tmp, dst)
        }
        true
    }.getOrElse { t ->
        Log.e(TAG, "decrypt failed: ${src.name}", t)
        runCatching { dst.delete() }
        false
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
