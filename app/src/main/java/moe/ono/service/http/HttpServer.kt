package moe.ono.service.http

import fi.iki.elonen.NanoHTTPD
import moe.ono.ext.decToSeqBytes
import moe.ono.ext.hexToBytes
import moe.ono.ext.toHex
import moe.ono.hooks.item.developer.QSignHook
import org.json.JSONObject

object HttpServer : NanoHTTPD(7300) {

    fun doStart() { if (!wasStarted()) start(SOCKET_READ_TIMEOUT, false) }

    override fun serve(session: IHTTPSession): Response =
        when (session.uri) {
            "/last" -> newFixedLengthResponse(
                Response.Status.OK,
                "application/json",
                QSignHook.lastResult["latest"] ?: "{}"
            )
            "/sign" -> handleSign(session)
            else    -> newFixedLengthResponse(
                Response.Status.NOT_FOUND,
                "text/plain",
                "404"
            )
        }

    private fun handleSign(session: IHTTPSession): Response {
        val input: Map<String, String> = when (session.method) {
            Method.POST -> {
                val files = HashMap<String, String>()
                try { session.parseBody(files) } catch (e: Exception) { return badRequest("Bad body: $e") }
                val raw = files["postData"] ?: return badRequest("Empty body")
                val obj = runCatching { JSONObject(raw) }.getOrElse { return badRequest("Invalid JSON") }
                mapOf(
                    "cmd"    to obj.optString("cmd"),
                    "buffer" to obj.optString("buffer"),
                    "seq"    to obj.optString("seq"),
                    "uin"    to obj.optString("uin")
                )
            }
            Method.GET  -> session.parameters.mapValues { (_, values) ->
                values.firstOrNull().orEmpty()
            }
            else        -> return newFixedLengthResponse(
                Response.Status.METHOD_NOT_ALLOWED,
                "text/plain",
                "Use GET or POST"
            )
        }

        val cmd    = input["cmd"].orEmpty()
        val buffer = input["buffer"].orEmpty()
        val seqStr = input["seq"].orEmpty()
        val uin    = input["uin"].orEmpty()

        fun String.isHex() = matches(Regex("^[0-9a-fA-F]+$"))
        fun String.isDec() = matches(Regex("^\\d+$"))

        if (cmd.isEmpty() || buffer.isEmpty() || seqStr.isEmpty() || uin.toLongOrNull() == null)
            return badRequest("Missing fields")
        if (!buffer.isHex() || buffer.length % 2 != 0)
            return badRequest("buffer must be even-length HEX string")

        val seqBytes = when {
            seqStr.isHex() && seqStr.length % 2 == 0 -> seqStr.hexToBytes()
            seqStr.isDec()                            -> seqStr.decToSeqBytes()
            else -> return badRequest("seq must be decimal or even-length HEX string")
        }

        val signObj = try {
            QSignHook.callGetSign(cmd, buffer.hexToBytes(), seqBytes, uin)
        } catch (t: Throwable) {
            return newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "text/plain",
                t.stackTraceToString()
            )
        }

        val resp = JSONObject().apply {
            put("extra",  signObj.extra.toHex())
            put("sign",   signObj.sign.toHex())
            put("token",  signObj.token.toHex())
        }

        return newFixedLengthResponse(
            Response.Status.OK,
            "application/json",
            resp.toString()
        )
    }

    private fun badRequest(msg: String) =
        newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", msg)
}
