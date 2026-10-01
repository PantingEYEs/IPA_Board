package com.example.ipa_board.clipboard

object SmsOtpParser {
    private val otpRegexes = listOf(
        Regex("""(?:验证码|動態碼|校验码|確認碼|动态码|code|OTP|Pin|passcode|secret)[：:\s]*([0-9]{4,8})\b""", RegexOption.IGNORE_CASE),
        Regex("""\b([0-9]{4,8})\s*(?:是您的|为您的|是登录|是验证码|为验证码|是确认码|是动态码)""", RegexOption.IGNORE_CASE),
        Regex("""【[^】]+】[^0-9]*([0-9]{4,8})\b"""),
        Regex("""\b([0-9]{4,8})\b""")
    )

    fun extractOtp(messageBody: String): String? {
        if (messageBody.isBlank()) return null

        val isLikelyOtpMessage = messageBody.contains("验证码", ignoreCase = true) ||
            messageBody.contains("校验码", ignoreCase = true) ||
            messageBody.contains("动态码", ignoreCase = true) ||
            messageBody.contains("确认码", ignoreCase = true) ||
            messageBody.contains("code", ignoreCase = true) ||
            messageBody.contains("OTP", ignoreCase = true) ||
            messageBody.contains("PIN", ignoreCase = true) ||
            messageBody.contains("passcode", ignoreCase = true)

        if (!isLikelyOtpMessage) return null

        for (regex in otpRegexes) {
            val match = regex.find(messageBody)
            if (match != null) {
                val candidate = match.groupValues.getOrNull(1) ?: match.groupValues[0]
                if (candidate.length in 4..8 && candidate.all { it.isDigit() }) {
                    return candidate
                }
            }
        }
        return null
    }
}
