package moe.ono.hooks.item.developer

import android.annotation.SuppressLint
import android.provider.Settings
import de.robv.android.xposed.XposedHelpers
import moe.ono.ext.toHex
import moe.ono.hostInfo
import moe.ono.hooks._base.BaseSwitchFunctionHookItem
import moe.ono.hooks._core.annotation.HookItem
import moe.ono.service.http.HttpServer
import moe.ono.util.Initiator.loadClass
import moe.ono.util.Logger
import moe.ono.util.SyncUtils
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap


@SuppressLint("DiscouragedApi")
@HookItem(
    path = "开发者选项/移动端身份与签名探针",
    description = "记录移动端身份和 FEKit 签名，用于与电脑 QQNT 对比；结果仅保存在 QQ 私有目录"
)
class QSignHook : BaseSwitchFunctionHookItem() {
    companion object {
        val lastResult: ConcurrentHashMap<String, String> = ConcurrentHashMap()

        data class SignTriple(val extra: ByteArray, val sign: ByteArray, val token: ByteArray)

        private fun qimeiService(): Any? = runCatching {
            val qRoute = loadClass("com.tencent.mobileqq.qroute.QRoute")
            val serviceClass = loadClass("com.tencent.mobileqq.qimei.api.IQimeiService")
            XposedHelpers.callStaticMethod(qRoute, "api", serviceClass)
        }.getOrNull()

        private fun qimei(method: String): String = runCatching {
            XposedHelpers.callMethod(qimeiService() ?: return@runCatching "", method) as? String ?: ""
        }.getOrDefault("")

        private fun currentQua(): String =
            "V1_AND_SQ_${hostInfo.versionName}_${hostInfo.versionCode}_YYB_D"

        private fun currentAndroidId(): String = runCatching {
            Settings.Secure.getString(hostInfo.application.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull().orEmpty()

        fun identity(): JSONObject {
            val feKit = runCatching { com.tencent.mobileqq.fe.FEKit.getInstance() }.getOrNull()
            val whiteList = runCatching { feKit?.cmdWhiteList.orEmpty() }.getOrDefault(emptyList())
            return JSONObject().apply {
                put("platform", "Android")
                put("packageName", hostInfo.packageName)
                put("versionName", hostInfo.versionName)
                put("versionCode", hostInfo.versionCode)
                put("qua", currentQua())
                put("androidId", currentAndroidId())
                put("qimei16", qimei("getQimei16"))
                put("qimei36", qimei("getQimei36"))
                put("signCommandWhitelist", whiteList)
                put("timestamp", System.currentTimeMillis())
            }
        }

        private fun diagnosticsFile() = File(hostInfo.application.filesDir, "ono-client-sign-probe.json")

        fun persist(lastSignature: JSONObject? = null) {
            runCatching {
                val previous = runCatching { JSONObject(diagnosticsFile().readText()) }.getOrNull()
                val result = JSONObject().apply {
                    put("identity", identity())
                    put("lastSignature", lastSignature ?: previous?.optJSONObject("lastSignature") ?: JSONObject())
                }
                diagnosticsFile().writeText(result.toString(2))
            }.onFailure { Logger.e("persist client sign diagnostics", it) }
        }

        fun diagnostics(): JSONObject = runCatching {
            JSONObject(diagnosticsFile().readText())
        }.getOrElse { JSONObject().put("identity", identity()).put("lastSignature", JSONObject()) }

        fun callGetSign(
            cmd: String,
            buffer: ByteArray,
            seq: ByteArray,
            uin: String,
        ): SignTriple {
            return runCatching {
                val signClass = loadClass("com.tencent.mobileqq.sign.QQSecuritySign")
                val qsecClass = loadClass("com.tencent.mobileqq.qsec.qsecurity.QSec")

                val qsecObj = XposedHelpers.newInstance(qsecClass)
                val instance = XposedHelpers.callStaticMethod(signClass, "getInstance")

                val qua = currentQua()
                Logger.d("callGetSign: $cmd, $buffer, $seq, $uin, $qua")
                val resultObj = XposedHelpers.callMethod(
                    instance, "getSign",
                    qsecObj, qua, cmd, buffer, seq, uin
                )

                SignTriple(
                    XposedHelpers.getObjectField(resultObj, "extra") as ByteArray,
                    XposedHelpers.getObjectField(resultObj, "sign") as ByteArray,
                    XposedHelpers.getObjectField(resultObj, "token") as ByteArray
                )
            }.getOrElse {
                Logger.e("callGetSign ERROR", it)
                throw it
            }
        }
    }

    @SuppressLint("DiscouragedApi")
    override fun entry(classLoader: ClassLoader) {
        HttpServer.doStart()
        persist()

        val signClass = loadClass("com.tencent.mobileqq.sign.QQSecuritySign")
        val qsecClass = loadClass("com.tencent.mobileqq.qsec.qsecurity.QSec")

        val m = XposedHelpers.findMethodExact(
            signClass,
            "getSign",
            qsecClass,
            String::class.java,
            String::class.java,
            ByteArray::class.java,
            ByteArray::class.java,
            Long::class.javaPrimitiveType,
        )
        hookAfter(m) { param ->
            val result = param.result ?: return@hookAfter
            val extra  = XposedHelpers.getObjectField(result, "extra")  as ByteArray
            val sign   = XposedHelpers.getObjectField(result, "sign")   as ByteArray
            val token  = XposedHelpers.getObjectField(result, "token")  as ByteArray
            val buffer = param.args.getOrNull(3) as? ByteArray ?: ByteArray(0)
            val seq = param.args.getOrNull(4) as? ByteArray ?: ByteArray(0)

            val json = JSONObject().apply {
                put("cmd", param.args.getOrNull(2)?.toString().orEmpty())
                put("qua", param.args.getOrNull(1)?.toString().orEmpty())
                put("uin", param.args.getOrNull(5)?.toString().orEmpty())
                put("seq", seq.toHex())
                put("bufferBytes", buffer.size)
                put("bufferSha256", MessageDigest.getInstance("SHA-256").digest(buffer).toHex())
                put("extra", extra.toHex())
                put("sign",  sign.toHex())
                put("token", token.toHex())
                put("extraBytes", extra.size)
                put("signBytes", sign.size)
                put("tokenBytes", token.size)
                put("timestamp", System.currentTimeMillis())
            }
            lastResult["latest"] = json.toString()
            persist(json)
        }
    }

    override fun targetProcess(): Int {
        return SyncUtils.PROC_MSF
    }
}
