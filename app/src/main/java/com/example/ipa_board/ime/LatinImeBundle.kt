package com.example.ipa_board.ime

/** Fixed source build; the JNI, packet and unchanged dictionary form one compatibility contract. */
internal object LatinImeBundle {
    const val UPSTREAM_VERSION = "4.1"
    const val UPSTREAM_REVISION = "9f5bb635c2e8609dcd95dc7506c0c58fba82a52c"
    const val BUILD_REVISION = "ad79fe388ced2d9944d81a159f72b5ef6a972fce"
    const val BUILD_ID = "6ddfef54ed2c518f5435994eccb14cb2b19897da8ee826c179c8b124aac03258"
    const val DICTIONARY_SHA256 = "bd950ef4b57655120eee65cee62a5d216a63f721d9a8bb759ce2022437840443"
    const val DICTIONARY_SIZE = 2_956_881L
    val identity: String get() = "ipa-latinime;abi=1;packet=1;heliboard=$UPSTREAM_VERSION;" +
        "source=$UPSTREAM_REVISION;dictionary=$DICTIONARY_SHA256;build=$BUILD_ID"

    fun verifyIdentity(actual: ByteArray) {
        check(actual.contentEquals(identity.toByteArray(Charsets.UTF_8))) { "IPA_LATINIME_BUILD_MISMATCH" }
    }
}
