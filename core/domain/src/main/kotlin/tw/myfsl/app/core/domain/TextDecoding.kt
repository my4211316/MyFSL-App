package tw.myfsl.app.core.domain

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * 讀使用者匯入的 CSV。Excel 在繁體中文 Windows 上「另存 CSV」常存成 Big5，
 * 「CSV UTF-8」則是 UTF-8（開頭有 BOM），所以兩種都要能讀。
 */
object TextDecoding {

    enum class Encoding(val label: String) { UTF8("UTF-8"), BIG5("Big5") }

    data class Decoded(val text: String, val encoding: Encoding)

    fun decode(bytes: ByteArray): Decoded {
        val withoutBom = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            bytes.copyOfRange(3, bytes.size)
        } else {
            bytes
        }
        strict(withoutBom, Charsets.UTF_8)?.let { return Decoded(it, Encoding.UTF8) }
        val big5 = runCatching { Charset.forName("Big5") }.getOrNull()
        if (big5 != null) {
            return Decoded(String(withoutBom, big5), Encoding.BIG5)
        }
        return Decoded(String(withoutBom, Charsets.UTF_8), Encoding.UTF8)
    }

    /** 匯出時一律用 UTF-8 加 BOM，Excel 打開才不會亂碼。 */
    fun encodeForExcel(text: String): ByteArray =
        byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + text.toByteArray(Charsets.UTF_8)

    private fun strict(bytes: ByteArray, charset: Charset): String? = try {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (e: CharacterCodingException) {
        null
    }
}
