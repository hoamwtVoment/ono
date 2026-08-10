package moe.ono.hooks.item.chat

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams
import android.widget.LinearLayout
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.view.children
import com.alibaba.fastjson2.TypeReference
import com.github.kyuubiran.ezxhelper.utils.argTypes
import com.github.kyuubiran.ezxhelper.utils.args
import com.github.kyuubiran.ezxhelper.utils.invokeMethod
import com.github.kyuubiran.ezxhelper.utils.newInstance
import com.google.protobuf.ByteString
import com.lxj.xpopup.util.XPopupUtils
import com.tencent.qqnt.kernel.nativeinterface.MsgRecord
import de.robv.android.xposed.XC_MethodHook
import moe.ono.bridge.kernelcompat.ContactCompat
import moe.ono.bridge.kernelcompat.KernelMsgServiceCompat
import moe.ono.bridge.ntapi.ChatTypeConstants
import moe.ono.bridge.ntapi.MsgConstants
import moe.ono.bridge.ntapi.MsgServiceHelper
import moe.ono.bridge.ntapi.NtGrayTipHelper
import moe.ono.bridge.ntapi.RelationNTUinAndUidApi
import moe.ono.bridge.ntapi.RelationNTUinAndUidApi.getUinFromUid
import moe.ono.config.ConfigManager
import moe.ono.hooks._base.BaseSwitchFunctionHookItem
import moe.ono.hooks._core.annotation.HookItem
import moe.ono.hooks._core.factory.HookItemFactory.getItem
import moe.ono.hooks.base.api.QQMessageViewListener
import moe.ono.hooks.base.api.QQMsgViewAdapter
import moe.ono.hooks.clazz
import moe.ono.reflex.ClassUtils
import moe.ono.reflex.ConstructorUtils
import moe.ono.reflex.FieldUtils
import moe.ono.util.AppRuntimeHelper
import moe.ono.util.ContactUtils
import moe.ono.util.Logger
import moe.ono.util.Session
import rikka.core.content.put
import top.artmoe.inao.entries.MsgPushOuterClass
import top.artmoe.inao.entries.QQMessageOuterClass

private var mQQMsgFacade: Any? = null

@HookItem(path = "聊天与消息/防撤回", description = "通用群聊/私聊防撤回（协议防撤回）\n需要保活，此功能目前正处于实验阶段\n目前有两种提醒模式")
class HoldRevokeMsg : BaseSwitchFunctionHookItem() {
    private val ID_ADD_LAYOUT = 0x114519
    private val ID_ADD_TEXTVIEW = 0x114510
    private var retractMessageMap: MutableMap<String, MutableList<String>> = HashMap()
    private val constraintSetClz by lazy { "androidx.constraintlayout.widget.ConstraintSet".clazz!! }
    private val constraintLayoutClz by lazy { "androidx.constraintlayout.widget.ConstraintLayout".clazz!! }

    override fun entry(classLoader: ClassLoader) {
        readData()
        hookAIOMsgUpdate()
    }

    private fun getConfigUtils(): ConfigManager {
        return ConfigManager.getCache()
    }

    private fun hookAIOMsgUpdate() {
        QQMessageViewListener.addMessageViewUpdateListener(
            this,
            object : QQMessageViewListener.OnChatViewUpdateListener {
                override fun onViewUpdateAfter(msgItemView: View, msgRecord: Any) {
                    val rootView = msgItemView as ViewGroup

                    if (!QQMsgViewAdapter.hasContentMessage(rootView)) return

                    val peerUid: String = FieldUtils.create(msgRecord)
                        .fieldName("peerUid")
                        .fieldType(String::class.java)
                        .firstValue(msgRecord)

                    val msgSeq: Long = FieldUtils.create(msgRecord)
                        .fieldName("msgSeq")
                        .fieldType(Long::class.javaPrimitiveType)
                        .firstValue(msgRecord)

                    val senderUin: Long = FieldUtils.create(msgRecord)
                        .fieldName("senderUin")
                        .fieldType(Long::class.java)
                        .firstValue(msgRecord)

                    val msgType: Int = FieldUtils.create(msgRecord)
                        .fieldName("msgType")
                        .fieldType(Int::class.java)
                        .firstValue(msgRecord)

                    val recallPromptTextView = rootView.findViewById<View>(ID_ADD_LAYOUT)
                    if (recallPromptTextView != null) rootView.removeView(recallPromptTextView)

                    var msgTime: Long = FieldUtils.create(msgRecord).fieldName("msgTime")
                        .fieldType(Long::class.javaPrimitiveType).firstValue(msgRecord)


                    msgTime *= 1000

                    if ((System.currentTimeMillis() - msgTime) < 1000) {
                        return
                    }

                    val seqUidList = retractMessageMap[peerUid]
                    seqUidList?.forEach {
                        if (it.split(":")[0] == msgSeq.toString()) {
                            val uid = it.split(":")[1]
                            val uin = getUinFromUid(uid).toLong()
                            val nick = ContactUtils.getDisplayNameForUin(uin.toString())
                            addViewToQQMessageView(rootView, uin.toString(), nick, (Session.cChatType == 1), (uin == senderUin), senderUin, msgType)
                            return@forEach
                        }
                    }
                }

            })
    }

    @SuppressLint("SetTextI18n")
    private fun addViewToQQMessageView(rootView: ViewGroup, uin : String?, nick : String?,
                                       pm : Boolean, revBySelf : Boolean, senderUin : Long, msgType : Int) {
        val context = rootView.context
        val parentLayoutId = rootView.id
        val contentId: Int = QQMsgViewAdapter.getContentViewId()
        // 制定约束布局参数 用反射做 不然androidx引用的是模块的而不是QQ自身的
        val newLayoutParams: LayoutParams = ConstructorUtils.newInstance(
            ClassUtils.findClass("androidx.constraintlayout.widget.ConstraintLayout\$LayoutParams"),
            arrayOf<Class<*>?>(
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            ),
            LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT
        ) as LayoutParams
        FieldUtils.create(newLayoutParams)
            .fieldName("startToStart")
            .setFirst(newLayoutParams, parentLayoutId)

        FieldUtils.create(newLayoutParams)
            .fieldName("endToEnd")
            .setFirst(newLayoutParams, parentLayoutId)

        FieldUtils.create(newLayoutParams)
            .fieldName("topToTop")
            .setFirst(newLayoutParams, contentId)


        val layout = LinearLayout(rootView.context).apply {
            layoutParams = ConstraintLayout.LayoutParams(
                0 /* MATCH_CONSTRAINT */,
                ConstraintLayout.LayoutParams.WRAP_CONTENT
            )
            id = ID_ADD_LAYOUT
            val drawable = GradientDrawable()
            drawable.shape = GradientDrawable.RECTANGLE
            drawable.setColor(Color.BLACK)
            drawable.cornerRadius = 10f
            drawable.alpha = 0x22
            background = drawable

            val _4 = XPopupUtils.dp2px(rootView.context, 4f)
            val _6 = XPopupUtils.dp2px(rootView.context, 6f)
            setPadding(_6, _4, _6, _4)
        }

        val textView = TextView(rootView.context).apply {
            id = ID_ADD_TEXTVIEW
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setTextColor(Color.WHITE)
        }

        if (!pm){
            textView.text = "消息被 $nick ($uin) 撤回"
            if (revBySelf) {
                textView.text = "消息被自己撤回"
            }
        } else {
            textView.text = "该消息已被撤回"
        }

        layout.gravity = Gravity.CENTER
        layout.addView(textView)
        rootView.addView(layout)

        val constraintSet = constraintSetClz.newInstance(args())!!
        constraintSet.invokeMethod("clone", args(rootView), argTypes(constraintLayoutClz))
        val i_msg = rootView.children.indexOfFirst { it is LinearLayout && it.id != View.NO_ID }
        val id_msg = rootView.getChildAt(i_msg).id
        val id_name = rootView.getChildAt(i_msg - 1).id
        constraintSet.invokeMethod(
            "connect",
            args(ID_ADD_LAYOUT, ConstraintLayout.LayoutParams.TOP, id_msg, ConstraintLayout.LayoutParams.BOTTOM, 0),
            argTypes(Int::class.java, Int::class.java, Int::class.java, Int::class.java, Int::class.java)
        )
        if (senderUin != AppRuntimeHelper.getLongAccountUin()) {
            constraintSet.invokeMethod(
                "connect",
                args(ID_ADD_LAYOUT, ConstraintSet.LEFT, id_name, ConstraintSet.LEFT),
                argTypes(Int::class.java, Int::class.java, Int::class.java, Int::class.java)
            )
            if (pm) {
                // 调整私聊显示边距
                constraintSet.invokeMethod(
                    "setMargin",
                    args(ID_ADD_LAYOUT, ConstraintSet.START, XPopupUtils.dp2px(rootView.context, 10f)),
                    argTypes(Int::class.java, Int::class.java, Int::class.java)
                )
            } else if (!pm && msgType == MsgConstants.MSG_TYPE_FILE) {
                // 调整群聊文件边距
                constraintSet.invokeMethod(
                    "setMargin",
                    args(ID_ADD_LAYOUT, ConstraintSet.START, XPopupUtils.dp2px(rootView.context, 55f)),
                    argTypes(Int::class.java, Int::class.java, Int::class.java)
                )
            }
        } else {
            constraintSet.invokeMethod(
                "connect",
                args(ID_ADD_LAYOUT, ConstraintSet.RIGHT, id_name, ConstraintSet.RIGHT),
                argTypes(Int::class.java, Int::class.java, Int::class.java, Int::class.java)
            )
            if (pm) {
                // 调整私聊显示边距
                constraintSet.invokeMethod(
                    "setMargin",
                    args(ID_ADD_LAYOUT, ConstraintSet.END, XPopupUtils.dp2px(rootView.context, 10f)),
                    argTypes(Int::class.java, Int::class.java, Int::class.java)
                )
            }
        }
        constraintSet.invokeMethod("applyTo", args(rootView), argTypes(constraintLayoutClz))
    }


    /**
     * 写入本地撤回记录
     */
    fun writeAndRefresh(peerUid: String, msgSeq: Int, uid: String) {
        var seqUidList: MutableList<String>? = retractMessageMap[peerUid]
        if (seqUidList == null) {
            seqUidList = ArrayList()
        }

        seqUidList.add("$msgSeq:$uid")
        // 刷新map
        retractMessageMap[peerUid] = seqUidList
        getConfigUtils().edit().put("retractMessageMap", retractMessageMap).apply()
    }

    /**
     * 从本地读取撤回记录数据
     */
    private fun readData() {
        val type = object : TypeReference<MutableMap<String, MutableList<String>>>() {}
        var localRetractMessageMap = getConfigUtils().cGetObject("retractMessageMap", type)
        if (localRetractMessageMap == null) {
            localRetractMessageMap = HashMap()
        }
        this.retractMessageMap = localRetractMessageMap
    }
}

object HoldRevokeMessageCore {
    fun onC2CRecallByMsgPush(
        operationInfoByteArray: ByteArray,
        msgPush: MsgPushOuterClass.MsgPush,
        param: XC_MethodHook.MethodHookParam,
    ) {
        if (!getItem(HoldRevokeMsg::class.java).isEnabled) {
            return
        }
        val operationInfo = QQMessageOuterClass.QQMessage.MessageBody.C2CRecallOperationInfo.parseFrom(operationInfoByteArray)
        // msg seq
        val recallMsgSeq = operationInfo.info.msgSeq
        val operatorUid = operationInfo.info.operatorUid

        val senderUid = operationInfo.info.operatorUid

        // 本地消息key 用这个判断是不是已经撤回的消息
        val retracting = getItem(HoldRevokeMsg::class.java)
        retracting.writeAndRefresh(operatorUid, recallMsgSeq, operatorUid)

        val selfUin = AppRuntimeHelper.getAccount()
        val selfUid = RelationNTUinAndUidApi.getUidFromUin(selfUin)

        val newOperationInfoByteArray = operationInfo.toBuilder().apply {
            info = info.toBuilder().apply {
                msgSeq = 1
            }.build()
        }.build().toByteArray()

        val newMsgPush = msgPush.toBuilder().apply {
            qqMessage = qqMessage.toBuilder().apply {
                messageBody = messageBody.toBuilder().apply {
                    setOperationInfo(
                        ByteString.copyFrom(newOperationInfoByteArray)
                    )
                }.build()
            }.build()
        }.build()
        param.args[1] = newMsgPush.toByteArray()

        val builder = NtGrayTipHelper.NtGrayTipJsonBuilder()

        if (selfUid == senderUid) {
            builder.appendText("你")
            return
        } else {
            builder.appendText("对方")
        }
        builder.appendText("尝试撤回")
        builder.append(NtGrayTipHelper.NtGrayTipJsonBuilder.MsgRefItem("一条消息",
            recallMsgSeq.toLong()
        ))

        NtGrayTipHelper.addLocalJsonGrayTipMsg(
            AppRuntimeHelper.getAppRuntime()!!,
            ContactCompat(ChatTypeConstants.C2C, senderUid, ""),
            NtGrayTipHelper.createLocalJsonElement(NtGrayTipHelper.AIO_AV_GROUP_NOTICE.toLong(), builder.build().toString(), ""),
            true,
            true
        ) { result, uin ->
            if (result != 0) {
                Logger.e("GagInfoDisclosure error: addLocalJsonGrayTipMsg failed, result=$result, uin=$uin")
            }
        }
    }

    fun onGroupRecallByMsgPush(
        operationInfoByteArray: ByteArray,
        msgPush: MsgPushOuterClass.MsgPush,
        param: XC_MethodHook.MethodHookParam,
    ) {
        if (!getItem(HoldRevokeMsg::class.java).isEnabled) {
            return
        }
        val firstPart = operationInfoByteArray.copyOfRange(0, 7)
        val secondPart = operationInfoByteArray.copyOfRange(7, operationInfoByteArray.size)

        val operationInfo =
            QQMessageOuterClass.QQMessage.MessageBody.GroupRecallOperationInfo.parseFrom(secondPart)
        // msg seq
        val recallMsgSeq = operationInfo.info.msgInfo.msgSeq
        // group uin
        val groupPeerId = operationInfo.peerId.toString()

        val operatorUid = operationInfo.info.operatorUid
        val senderUid = operationInfo.info.msgInfo.senderUid

        val newOperationInfoByteArray = firstPart + (operationInfo.toBuilder().apply {
            msgSeq = 1
            info = info.toBuilder().apply {
                msgInfo = msgInfo.toBuilder().setMsgSeq(1).build()
            }.build()
        }.build().toByteArray())

        val newMsgPush = msgPush.toBuilder().apply {
            qqMessage = qqMessage.toBuilder().apply {
                messageBody = messageBody.toBuilder().apply {
                    setOperationInfo(
                        ByteString.copyFrom(newOperationInfoByteArray)
                    )
                }.build()
            }.build()
        }.build()
        param.args[1] = newMsgPush.toByteArray()

        val contact = ContactCompat(ChatTypeConstants.GROUP, groupPeerId, "")
        val app = AppRuntimeHelper.getAppRuntime()
        val kmsgSvc: KernelMsgServiceCompat? = app?.let { MsgServiceHelper.getKernelMsgService(it) }

        // I don't know why, but...
        // IKernelMsgService.getMsgsByMsgId callback: result=0, errMsg=null, msgList=[](empty list)
        // IKernelMsgService.getMsgsBySeqList does not invoke callback at all, and no log
        // Only kmsgSvc.getSingleMsg works.
        kmsgSvc!!.getSingleMsg(contact, recallMsgSeq.toLong(), (label@{ queryResult, errMsg, msgList ->
            try {
                var msgObject: MsgRecord? = null
                if (queryResult == 0 && msgList != null && !msgList.isEmpty()) {
                    msgObject = msgList[0]
                } else if (queryResult == 0) {
                    Logger.d("onRecallSysMsgForNT: msg not found, msgSeq=$recallMsgSeq")
                } else {
                    Logger.e("onRecallSysMsgForNT error: getMsgsByMsgId failed, result=$queryResult, errMsg=$errMsg")
                }

                val selfUin = AppRuntimeHelper.getAccount()
                val selfUid = RelationNTUinAndUidApi.getUidFromUin(selfUin)

                val retracting = getItem(HoldRevokeMsg::class.java)
                retracting.writeAndRefresh(groupPeerId, recallMsgSeq, operatorUid)

                Logger.d("getMessageLegacy($groupPeerId, 0, $recallMsgSeq, ${msgPush.qqMessage.messageContentInfo.msgUid})\nmsgObject->"+msgObject.toString())
                val builder = NtGrayTipHelper.NtGrayTipJsonBuilder()
                if (selfUid == senderUid) {
                    builder.appendText("你")
                    return@label
                } else {
                    builder.append(
                        NtGrayTipHelper.NtGrayTipJsonBuilder.UserItem(
                            getUinFromUid(operatorUid),
                            operatorUid,
                            ContactUtils.getDisplayNameForUid(operatorUid)
                        )
                    )
                }

                builder.appendText("尝试撤回")
                if (operatorUid != senderUid){
                    builder.append(
                        NtGrayTipHelper.NtGrayTipJsonBuilder.UserItem(
                            getUinFromUid(senderUid),
                            senderUid,
                            ContactUtils.getDisplayNameForUid(senderUid)
                        )
                    )
                    builder.appendText("的")
                }

                builder.append(NtGrayTipHelper.NtGrayTipJsonBuilder.MsgRefItem("一条消息",
                    recallMsgSeq.toLong()
                ))

                if (msgObject == null) {
                    builder.appendText("（没收到, seq=$recallMsgSeq）")
                }

                NtGrayTipHelper.addLocalJsonGrayTipMsg(
                    AppRuntimeHelper.getAppRuntime()!!,
                    ContactCompat(ChatTypeConstants.GROUP, groupPeerId, ""),
                    NtGrayTipHelper.createLocalJsonElement(NtGrayTipHelper.AIO_AV_GROUP_NOTICE.toLong(), builder.build().toString(), ""),
                    true,
                    true
                ) { result, uin ->
                    if (result != 0) {
                        Logger.e("GagInfoDisclosure error: addLocalJsonGrayTipMsg failed, result=$result, uin=$uin")
                    }
                }
            } catch (e: Exception) {
                Logger.e(e)
            }
        }))
    }
}
