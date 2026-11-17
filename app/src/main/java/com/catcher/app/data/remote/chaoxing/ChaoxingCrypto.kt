package com.catcher.app.data.remote.chaoxing

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 学习通登录表单的固定密钥，IV 与密钥相同（不是随机 IV）。
 *
 * 算法：AES-128-CBC + PKCS7 + Base64；明文就是原文，不做 JSON 包装。
 * 与电脑端 chaoxing/crypto.go 逐字节一致（用 java.util.Base64 而不是 android.util.Base64，
 * 这样这段逻辑可以在 JVM 单元测试里直接验证）。
 */
object ChaoxingCrypto {
    private const val AES_KEY = "u2oh6Vu^HWe4_AES"

    fun encrypt(plaintext: String): String {
        val keyBytes = AES_KEY.toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(keyBytes, "AES"),
            IvParameterSpec(keyBytes),
        )
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(encrypted)
    }

    /** 只给单元测试做反解校验用 */
    internal fun decrypt(token: String): String {
        val keyBytes = AES_KEY.toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(keyBytes, "AES"),
            IvParameterSpec(keyBytes),
        )
        return String(cipher.doFinal(Base64.getDecoder().decode(token)), Charsets.UTF_8)
    }
}
