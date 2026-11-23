package com.yaoahos.mypureplayer.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * SMB 凭据落盘加密。
 *
 * 用 Keystore 里一把固定 KEK（AES-256/GCM，不要求用户认证——否则冷启动无法自动
 * 还原已保存的共享）加密 NAS 密码。密文以 "CR:" 开头；空串和没有前缀的历史明文
 * 原样返回，保证从旧版本升级后已保存的服务器仍能连上。
 */
class CredentialCipher(private val alias: String = DEFAULT_ALIAS) {

    companion object {
        private const val DEFAULT_ALIAS = "mypureplayer_smb_cred_v1"
        private const val PREFIX = "CR:"
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val combined = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return PREFIX + Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    fun decrypt(stored: String): String {
        if (stored.isEmpty() || !stored.startsWith(PREFIX)) return stored // 历史明文
        return runCatching {
            val combined = Base64.decode(stored.removePrefix(PREFIX), Base64.NO_WRAP)
            if (combined.size <= 12) return stored
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, combined.copyOfRange(0, 12)))
            String(cipher.doFinal(combined.copyOfRange(12, combined.size)), Charsets.UTF_8)
        }.getOrElse { t ->
            // 解密失败返回原文只会让用户看到莫名的「认证失败」；留一条日志才能定位
            // （常见原因：系统还原后 Keystore 换了新钥匙，旧密文全部失效）
            android.util.Log.e("CredentialCipher", "SMB 密码解密失败，按未保存处理：${t.message}")
            ""
        }
    }
}
