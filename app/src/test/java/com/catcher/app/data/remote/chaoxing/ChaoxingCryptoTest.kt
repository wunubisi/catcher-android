package com.catcher.app.data.remote.chaoxing

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 登录表单加密的回归测试。
 *
 * 期望值是按电脑端 crypto.go 的同一套参数（AES-128-CBC + PKCS7 + Base64，IV = Key）
 * 独立算出来的；只要这里变红，就说明和电脑端不再互通。
 */
class ChaoxingCryptoTest {

    @Test
    fun `手机号加密结果与电脑端一致`() {
        assertEquals("2CcXQDU5xG3pU5HRjV8jMw==", ChaoxingCrypto.encrypt("13812345678"))
    }

    @Test
    fun `密码加密结果与电脑端一致`() {
        assertEquals("IZqmaLNP9z5yXfIYamy9wg==", ChaoxingCrypto.encrypt("abc123"))
    }

    @Test
    fun `加密后能解回原文`() {
        val encrypted = ChaoxingCrypto.encrypt("13812345678")
        assertEquals("13812345678", ChaoxingCrypto.decrypt(encrypted))
    }

    @Test
    fun `块大小的整数倍也要补一块`() {
        val encrypted = ChaoxingCrypto.encrypt("0123456789abcdef")
        assertEquals("0123456789abcdef", ChaoxingCrypto.decrypt(encrypted))
        // 16 字节明文 + PKCS7 补满 16 字节，Base64 后是 44 字符
        assertEquals(44, encrypted.length)
    }
}
