package moe.ono.hooks.base.api

import com.google.protobuf.UnknownFieldSet
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodHook.MethodHookParam
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import kotlinx.io.core.ByteReadPacket
import kotlinx.io.core.readBytes
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import moe.ono.config.CacheConfig.getIQQntWrapperSessionInstance
import moe.ono.config.CacheConfig.setIQQntWrapperSessionInstance
import moe.ono.config.ConfigManager
import moe.ono.constants.Constants
import moe.ono.constants.Constants.CLAZZ_CPP_PROXY
import moe.ono.ext.getUnknownObject
import moe.ono.ext.getUnknownObjects
import moe.ono.hooks._base.ApiHookItem
import moe.ono.hooks._core.annotation.HookItem
import moe.ono.hooks._core.factory.HookItemFactory
import moe.ono.hooks.item.chat.HoldRevokeMessageCore
import moe.ono.hooks.item.chat.MessageEncryptor
import moe.ono.hooks.item.chat.SelfMessageReactor
import moe.ono.hooks.item.entertainment.BlockBadlanguage
import moe.ono.hooks.item.entertainment.DoNotBrushMeOff
import moe.ono.hooks.protocol.entries.Message
import moe.ono.hooks.protocol.entries.MessagePush
import moe.ono.hooks.protocol.entries.TextMsgExtPbResvAttr
import moe.ono.reflex.ClassUtils
import moe.ono.reflex.MethodUtils
import moe.ono.util.AesUtils
import moe.ono.util.Initiator.load
import moe.ono.util.Logger
import moe.ono.util.QAppUtils
import top.artmoe.inao.entries.InfoSyncPushOuterClass
import top.artmoe.inao.entries.MsgPushOuterClass


@HookItem(path = "API/监听MsfPush")
class QQOnMsfPush : ApiHookItem() {
    override fun entry(classLoader: ClassLoader) {
        val onMSFPushMethod = MethodUtils.create(CLAZZ_CPP_PROXY)
            .params(
                String::class.java,
                ByteArray::class.java,
                ClassUtils.findClass("com.tencent.qqnt.kernel.nativeinterface.PushExtraInfo")
            )
            .methodName("onMsfPush")
            .first()


        hookAfter(load(CLAZZ_CPP_PROXY), {
            run {
                setIQQntWrapperSessionInstance(it.thisObject)
                Logger.i("CppProxy instance captured via constructor: ${getIQQntWrapperSessionInstance()}")
            }
        }, Long::class.javaPrimitiveType)


        hookBefore(onMSFPushMethod) { param ->
            val cmd = param.args[0] as String
            val protoBuf = param.args[1] as ByteArray
            when (cmd) {
                "trpc.msg.register_proxy.RegisterProxy.InfoSyncPush" -> {
                    handleInfoSyncPush(protoBuf, param)
                }
                "trpc.msg.olpush.OlPushService.MsgPush" -> {
                    handleMsgPush(protoBuf, param)
                }
            }
        }
    }

    private fun handleInfoSyncPush(buffer: ByteArray, param: MethodHookParam) {
        val infoSyncPush = InfoSyncPushOuterClass.InfoSyncPush.parseFrom(buffer)
        infoSyncPush.syncRecallContent.syncInfoBodyList.forEach { syncInfoBody ->
            syncInfoBody.msgList.forEach { qqMessage ->
                val msgType = qqMessage.messageContentInfo.msgType
                val msgSubType = qqMessage.messageContentInfo.msgSubType
                if ((msgType == 732 && msgSubType == 17) || (msgType == 528 && msgSubType == 138)) {
                    val newInfoSyncPush = infoSyncPush.toBuilder().apply {
                        syncRecallContent = syncRecallContent.toBuilder().apply {
                            for (i in 0 until syncInfoBodyCount) {
                                setSyncInfoBody(
                                    i, getSyncInfoBody(i).toBuilder().clearMsg().build()
                                )
                            }
                        }.build()
                    }.build()
                    param.args[1] = newInfoSyncPush.toByteArray()
                }
            }
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun handleMsgPush(buffer: ByteArray, param: MethodHookParam) {
        val newMsgPush = ProtoBuf.decodeFromByteArray<MessagePush>(buffer)  // TODO: 只保留 newMsgPush
        val msgPush = MsgPushOuterClass.MsgPush.parseFrom(buffer)
        val msg = msgPush.qqMessage
//         if (msgTargetUid != EnvHelper.getQQAppRuntime().currentUid) return  //  不是当前用户接受就返回
        val msgType = msg.messageContentInfo.msgType
        val msgSubType = msg.messageContentInfo.msgSubType


        val operationInfoByteArray = msg.messageBody.operationInfo.toByteArray()


        when (msgType) {
            // 82 - Group | 166 - C2C
            82, 166 -> {
                BlockBadlanguage().filter(param)
                DoNotBrushMeOff().filter(param)

                if (msgType == 82) {
                    if (msgPush.qqMessage.messageHead.senderPeerId == QAppUtils.getCurrentUin().toLong()) {
                        SelfMessageReactor().react(msgPush.qqMessage.messageHead.senderInfo.peerId, msgPush.qqMessage.messageContentInfo.msgSeq.toLong())
                    }
                    val msgBody = newMsgPush.msgBody
                    tryDecryptMsg(param, msgBody)
                }
            }

            732 -> when (msgSubType) {
                17 -> HoldRevokeMessageCore.onGroupRecallByMsgPush(operationInfoByteArray, msgPush, param)
            }

            528 -> when (msgSubType) {
                138 -> HoldRevokeMessageCore.onC2CRecallByMsgPush(operationInfoByteArray, msgPush, param)
            }
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun tryDecryptMsg(param: MethodHookParam, msgBody: Message) {
        val encryptKey = ConfigManager.dGetString(
            Constants.PrekCfgXXX + HookItemFactory.getItem(MessageEncryptor::class.java).path,
            "ono"
        )
        if (encryptKey.isBlank()) {
            return
        }
        val encryptedText = msgBody.body?.richMsg?.elems?.firstOrNull { it.text != null } ?: return
        val resvBuffer = encryptedText.text?.resv ?: return
        kotlin.runCatching {
            val encryptMsg = ProtoBuf.decodeFromByteArray<TextMsgExtPbResvAttr>(resvBuffer).wording ?: return
            if (encryptMsg.size <= 8) return // 不是加密消息
            val aesKey = AesUtils.md5(encryptKey)
            val encryptBuffer = ByteReadPacket(encryptMsg)
            if(0x114514 != encryptBuffer.readInt()) return // 不是加密消息
            if(encryptKey.hashCode() != encryptBuffer.readInt()) return // 密钥不匹配
            val decryptMsg = AesUtils.aesDecrypt(encryptBuffer.readBytes(), aesKey)
            val decryptMsgBody = UnknownFieldSet.parseFrom(decryptMsg)
            val decryptRichText = decryptMsgBody.getUnknownObject(1)

            val oldMsgPush = UnknownFieldSet.parseFrom(param.args[1] as ByteArray)
            val oldMsg = oldMsgPush.getUnknownObject(1)
            val oldMsgBody = oldMsg.getUnknownObject(3)
            val oldRichText = oldMsgBody.getUnknownObject(1)

            val newRichText = UnknownFieldSet.newBuilder(decryptRichText)
            val newElements = oldRichText.getUnknownObjects(2).mapNotNull {
                if (!it.hasField(1) && !it.hasField(6)) it else null
            }
            newRichText.mergeField(2, UnknownFieldSet.Field.newBuilder().also { field ->
                newElements.forEach { field.addLengthDelimited(it.toByteString()) }
            }.build())

            val newMsgBody = UnknownFieldSet.newBuilder(oldMsgBody)
            newMsgBody.clearField(1)
            newMsgBody.addField(1, UnknownFieldSet.Field.newBuilder().also { field ->
                field.addLengthDelimited(newRichText.build().toByteString())
            }.build())

            val newMsg = UnknownFieldSet.newBuilder(oldMsg)
            newMsg.clearField(3)
            newMsg.addField(3, UnknownFieldSet.Field.newBuilder().also { field ->
                field.addLengthDelimited(newMsgBody.build().toByteString())
            }.build())

            val newMsgPush = UnknownFieldSet.newBuilder(oldMsgPush)
            newMsgPush.clearField(1)
            newMsgPush.addField(1, UnknownFieldSet.Field.newBuilder().also { field ->
                field.addLengthDelimited(newMsg.build().toByteString())
            }.build())

            //PlatformTools.copyToClipboard(text = "Decrypt:" + newMsgPush.build().toByteArray().toHexString())

            param.args[1] = newMsgPush.build().toByteArray()
        }
    }

}
