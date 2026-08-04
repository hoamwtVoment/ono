package moe.ono.creator

import android.util.Base64
import moe.ono.hooks.protocol.buildMessage
import org.json.JSONArray
import org.json.JSONObject

/**
 * Helpers for locating QQ message Elem blocks in decoded numeric-key protobuf JSON.
 *
 * The server response shape is not identical for every command/version, so this
 * deliberately avoids mandatory JSONObject#get* calls and returns diagnostics
 * instead of throwing.
 */
internal object PbMessageTools {

    data class ExtractedElements(
        val value: Any,
        val path: String,
    )

    data class ExtractionResult(
        val elements: ExtractedElements?,
        val error: String?,
    )

    fun extractElements(content: JSONObject?, command: String?): ExtractionResult {
        if (content == null) {
            return ExtractionResult(null, "PB 响应为空")
        }

        // Known QQ message-fetch responses normally expose message(s) under field 6.
        val field6 = content.opt("6")
        if (field6 != null && field6 !== JSONObject.NULL) {
            findElements(field6, "$.6", 0)?.let {
                return ExtractionResult(it, null)
            }
        }

        // Fallback for command/version-specific wrappers: recursively search for
        // message.body.richText.elems, represented as 3 -> 1 -> 2.
        findElements(content, "$", 0)?.let {
            return ExtractionResult(it, null)
        }

        val commandHint = when (command) {
            "MessageSvc.PbGetOneDayRoamMsg" -> "私聊漫游响应"
            "MessageSvc.PbGetGroupMsg" -> "群消息响应"
            null, "" -> "当前响应"
            else -> command
        }
        val field6Hint = if (field6 == null || field6 === JSONObject.NULL) {
            "；顶层字段 6 不存在，服务器可能没有返回消息记录"
        } else {
            "；字段 6 存在，但未找到 3→1→2 的 Elem 结构"
        }
        return ExtractionResult(
            elements = null,
            error = "$commandHint 中未找到消息元素$field6Hint",
        )
    }

    /**
     * Builds the text shown by the fourth UI option: PB (HEX).
     *
     * Ono currently exposes the decoded numeric-key JSON to this dialog, not the
     * untouched server wire bytes. Therefore this is explicitly marked as a
     * re-encoding, rather than pretending it is a byte-perfect packet capture.
     */
    fun buildResponseHexText(content: JSONObject?, command: String?): String {
        if (content == null) return "PB (HEX)\n错误：PB 响应为空"

        return runCatching { buildMessage(content.toString()) }
            .fold(
                onSuccess = { bytes ->
                    buildString {
                        appendLine("PB (HEX) — 重编码")
                        appendLine("来源命令：${command ?: "未知"}")
                        appendLine("字节数：${bytes.size}")
                        appendLine("注意：该 HEX 由数字字段 JSON 重新编码，并非服务器原始 wire bytes。")
                        appendLine()
                        appendLine("HEX:")
                        appendLine(bytes.toHex())
                        appendLine()
                        appendLine("Base64:")
                        append(bytes.toBase64())
                    }
                },
                onFailure = { error ->
                    "PB (HEX)\n生成失败：${error.describe()}"
                },
            )
    }

    /**
     * Builds a copy-friendly report. Bytes are reconstructed from decoded JSON,
     * not guaranteed to be byte-for-byte identical to the original server wire data.
     */
    fun buildReencodedExportReport(content: JSONObject?, command: String?): JSONObject {
        val report = JSONObject()
        report.put("sourceCommand", command ?: JSONObject.NULL)
        report.put(
            "note",
            "以下 HEX/Base64 由已解码的数字字段 JSON 重新编码，仅保证字段语义尽量一致；字段顺序、整数 wire type 与未知字段可能无法和服务器原始字节完全一致。",
        )

        if (content == null) {
            report.put("error", "PB 响应为空")
            return report
        }

        runCatching { buildMessage(content.toString()) }
            .onSuccess { bytes ->
                report.put("responseHex", bytes.toHex())
                report.put("responseBase64", bytes.toBase64())
            }
            .onFailure { error ->
                report.put("responseEncodeError", error.describe())
            }

        val extraction = extractElements(content, command)
        val extracted = extraction.elements
        if (extracted == null) {
            report.put("elementError", extraction.error ?: "未找到消息元素")
            return report
        }

        report.put("elementPath", extracted.path)
        val encodedElements = JSONArray()
        when (val value = extracted.value) {
            is JSONArray -> {
                for (index in 0 until value.length()) {
                    encodedElements.put(encodeElement(value.opt(index), index))
                }
            }

            is JSONObject -> encodedElements.put(encodeElement(value, 0))
            else -> {
                encodedElements.put(
                    JSONObject().apply {
                        put("index", 0)
                        put("error", "Elem 值类型不受支持：${value.javaClass.name}")
                    },
                )
            }
        }
        report.put("elements", encodedElements)
        return report
    }

    private fun encodeElement(value: Any?, index: Int): JSONObject {
        val result = JSONObject().apply { put("index", index) }
        if (value !is JSONObject) {
            result.put(
                "error",
                if (value == null || value === JSONObject.NULL) {
                    "元素为空"
                } else {
                    "元素不是 JSONObject：${value.javaClass.name}"
                },
            )
            return result
        }

        result.put("json", value)
        runCatching { buildMessage(value.toString()) }
            .onSuccess { bytes ->
                result.put("hex", bytes.toHex())
                result.put("base64", bytes.toBase64())
            }
            .onFailure { error -> result.put("error", error.describe()) }
        return result
    }

    private fun findElements(node: Any?, path: String, depth: Int): ExtractedElements? {
        if (node == null || node === JSONObject.NULL || depth > MAX_DEPTH) return null

        when (node) {
            is JSONObject -> {
                extractDirect(node, path)?.let { return it }
                val keys = node.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    findElements(node.opt(key), "$path.$key", depth + 1)?.let { return it }
                }
            }

            is JSONArray -> {
                for (index in 0 until node.length()) {
                    findElements(node.opt(index), "$path[$index]", depth + 1)?.let { return it }
                }
            }
        }
        return null
    }

    private fun extractDirect(message: JSONObject, path: String): ExtractedElements? {
        val body = message.optJSONObject("3") ?: return null
        val richText = body.optJSONObject("1") ?: return null
        val elements = richText.opt("2") ?: return null
        if (elements === JSONObject.NULL) return null
        if (elements !is JSONObject && elements !is JSONArray) return null
        return ExtractedElements(elements, "$path.3.1.2")
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") {
        "%02X".format(it.toInt() and 0xFF)
    }

    private fun ByteArray.toBase64(): String = Base64.encodeToString(this, Base64.NO_WRAP)

    private fun Throwable.describe(): String =
        "${javaClass.simpleName}: ${message ?: "无详细信息"}"

    private const val MAX_DEPTH = 64
}
