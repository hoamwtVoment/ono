package moe.ono.service.inject

import android.content.Intent
import androidx.core.content.IntentCompat
import com.tencent.qphone.base.remote.FromServiceMsg
import com.tencent.qphone.base.remote.ToServiceMsg
import moe.ono.service.QQInterfaces
import moe.ono.util.Logger
import moe.ono.util.QAppUtils
import moe.ono.util.toMap
import mqq.app.MSFServlet
import mqq.app.Packet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

abstract class BaseServlet: MSFServlet() {
    override fun onReceive(intent: Intent, fromServiceMsg: FromServiceMsg) {
        val toServiceMsg: ToServiceMsg =
            IntentCompat.getParcelableExtra(
                intent,
                ToServiceMsg::class.java.simpleName,
                ToServiceMsg::class.java
            )!!
        fromServiceMsg.attributes[FromServiceMsg::class.java.simpleName] = toServiceMsg
        Logger.d(toServiceMsg.toString())
        Logger.d(toServiceMsg.extraData.toMap().toString())
        Logger.d("${this::class.java.simpleName} -> onReceive: $fromServiceMsg")
        Logger.d(fromServiceMsg.extraData.toMap().toString())
        QQInterfaces.seqReceiveMap[toServiceMsg.appSeq] = fromServiceMsg
    }

    override fun onSend(intent: Intent, packet: Packet) {
        val toServiceMsg: ToServiceMsg? =
            IntentCompat.getParcelableExtra(
                intent,
                ToServiceMsg::class.java.simpleName,
                ToServiceMsg::class.java
            )
        toServiceMsg?.let {
            packet.setSSOCommand(toServiceMsg.serviceCmd)
            packet.putSendData(toServiceMsg.wupBuffer)
            packet.setTimeout(toServiceMsg.timeout)
            @Suppress("UNCHECKED_CAST")
            packet.attributes = toServiceMsg.attributes as HashMap<String, Any>?
            if (!toServiceMsg.isNeedCallback) {
                packet.setNoResponse()
            }
        }
    }
}
