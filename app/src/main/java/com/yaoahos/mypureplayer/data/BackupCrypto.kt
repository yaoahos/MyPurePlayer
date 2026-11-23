package com.yaoahos.mypureplayer.data

import android.util.Base64
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 备份文件加密：口令 → PBKDF2(HMAC-SHA256) → AES-256-GCM。
 *
 * 为什么用口令而不是 Keystore 密钥：Keystore 密钥绑在本机，换设备、重装后根本解不开，
 * 那备份就失去了"换机器恢复设置"的意义。口令是唯一能跟着用户走的东西。
 *
 * 文件格式（纯 ASCII 文本，方便邮件/聊天/网盘直传，不依赖二进制传输）：
 * ```
 * MYPUREPLAYER-BACKUP 1
 * <base64(salt[16] || iv[12] || ciphertext+tag)>
 * ```
 * 第一行是魔数与版本，用来区分"加密备份"与历史上可能残留的明文 JSON；
 * salt 每次随机，所以同一个口令两次备份的密文不同；GCM 自带完整性校验，
 * 改一个字节就会解密失败，不会还原出半截配置。
 */
object BackupCrypto {

    private const val MAGIC = "MYPUREPLAYER-BACKUP"
    private const val VERSION = 1

    /** 迭代次数：手机上约 100~200ms，足以把离线爆破成本抬到不可接受 */
    private const val ITERATIONS = 120_000
    private const val KEY_BITS = 256
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    /** 口令最短长度；太短的口令配再多的迭代次数也没意义 */
    const val MIN_PASSWORD = 6

    fun isEncrypted(text: String): Boolean = text.trimStart().startsWith(MAGIC)

    fun encrypt(plain: String, password: String): String {
        require(password.length >= MIN_PASSWORD) { "备份密码至少 $MIN_PASSWORD 位" }
        val random = SecureRandom()
        val salt = ByteArray(SALT_BYTES).also { random.nextBytes(it) }
        val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(password, salt), GCMParameterSpec(TAG_BITS, iv))
        val sealed = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val body = Base64.encodeToString(salt + iv + sealed, Base64.DEFAULT)
        return "$MAGIC $VERSION\n$body"
    }

    fun decrypt(text: String, password: String): String {
        val lines = text.trim().lineSequence().toList()
        val header = lines.firstOrNull()?.trim().orEmpty()
        val version = header.removePrefix(MAGIC).trim().toIntOrNull()
        if (!header.startsWith(MAGIC) || version == null) {
            throw IllegalArgumentException("这不是加密的配置备份")
        }
        if (version > VERSION) throw IllegalArgumentException("备份文件版本较新，请升级应用后再恢复")
        if (password.isEmpty()) throw IllegalArgumentException("该备份已加密，请先输入备份密码")

        val blob = runCatching {
            Base64.decode(lines.drop(1).joinToString("").filterNot { it.isWhitespace() }, Base64.NO_WRAP)
        }.getOrElse { throw IllegalArgumentException("备份文件已损坏") }
        if (blob.size <= SALT_BYTES + IV_BYTES) throw IllegalArgumentException("备份文件已损坏")

        val salt = blob.copyOfRange(0, SALT_BYTES)
        val iv = blob.copyOfRange(SALT_BYTES, SALT_BYTES + IV_BYTES)
        val sealed = blob.copyOfRange(SALT_BYTES + IV_BYTES, blob.size)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, deriveKey(password, salt), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(sealed), Charsets.UTF_8)
        } catch (_: Exception) {
            // GCM 校验失败只有两种可能：口令错，或文件被改过。不区分，避免变成口令试探的工具。
            throw IllegalArgumentException("密码不正确，或备份文件已损坏")
        }
    }

    private fun deriveKey(password: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_BITS)
        return try {
            val raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            SecretKeySpec(raw, "AES")
        } finally {
            spec.clearPassword()
        }
    }
}
