package com.catcher.app.data.secret

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.catcher.app.domain.CatcherException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 本机凭据加密。对应电脑端的 internal/infra/secretstore（Windows DPAPI）。
 *
 * 安卓没有 DPAPI，等价物是 **Android Keystore**：密钥由 TEE/StrongBox 保管，
 * 只对本应用可用；落库的是 AES-GCM 密文，解密失败一律要求重新登录，绝不回退明文。
 */
class SecretVault {

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    /** 返回 "base64(iv):base64(ciphertext)" */
    fun encrypt(plaintext: String): String {
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
                Base64.encodeToString(encrypted, Base64.NO_WRAP)
        } catch (e: Exception) {
            throw CatcherException.internal("加密本机凭据失败: ${e.message}", e)
        }
    }

    fun decrypt(token: String): String {
        return try {
            val parts = token.split(":")
            if (parts.size != 2) throw IllegalStateException("密文格式错误")
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val data = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(data), Charsets.UTF_8)
        } catch (e: Exception) {
            // 解密失败视为登录态不可用，调用方据此要求重新登录
            throw CatcherException.authExpired("读取本机凭据失败，请重新登录: ${e.message}")
        }
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "catcher.session.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
