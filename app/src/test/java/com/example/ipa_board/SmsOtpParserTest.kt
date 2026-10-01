package com.example.ipa_board

import com.example.ipa_board.clipboard.SmsOtpParser
import org.junit.Assert.*
import org.junit.Test

class SmsOtpParserTest {

    @Test
    fun extractsOtpFromChineseSmsMessage() {
        val msg1 = "【某某记】您的验证码是 123456，请在5分钟内输入。"
        assertEquals("123456", SmsOtpParser.extractOtp(msg1))

        val msg2 = "【腾讯】验证码：582914，用来登录账号。"
        assertEquals("582914", SmsOtpParser.extractOtp(msg2))
    }

    @Test
    fun extractsOtpFromEnglishSmsMessage() {
        val msg1 = "Your verification code is 849201. Do not share."
        assertEquals("849201", SmsOtpParser.extractOtp(msg1))

        val msg2 = "Code: 483921 for verification."
        assertEquals("483921", SmsOtpParser.extractOtp(msg2))
    }

    @Test
    fun returnsNullForNonOtpMessage() {
        val msg1 = "Your order #12345678 has been shipped."
        assertNull(SmsOtpParser.extractOtp(msg1))

        val msg2 = "Hello, let's meet at 4:30pm."
        assertNull(SmsOtpParser.extractOtp(msg2))
    }
}
