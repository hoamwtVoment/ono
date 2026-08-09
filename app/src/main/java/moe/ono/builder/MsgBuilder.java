package moe.ono.builder;

import static moe.ono.util.Initiator.loadClass;

import androidx.annotation.NonNull;

import com.tencent.qqnt.kernel.nativeinterface.ArkElement;
import com.tencent.qqnt.kernel.nativeinterface.FileElement;
import com.tencent.qqnt.kernel.nativeinterface.MsgElement;
import com.tencent.qqnt.kernel.nativeinterface.TextElement;

import java.io.File;
import java.lang.reflect.Method;
import java.util.UUID;

import moe.ono.reflex.XClass;
import moe.ono.reflex.XField;
import moe.ono.reflex.XMethod;
import moe.ono.util.AppRuntimeHelper;
import moe.ono.util.DataUtils;
import moe.ono.util.HostInfo;
import moe.ono.util.Initiator;
import moe.ono.util.Logger;
import moe.ono.util.QAppUtils;
import moe.ono.util.QQVersion;

public class MsgBuilder {
    public static MsgElement nt_build_text(String text){
        TextElement textElement = new TextElement();
        textElement.setContent(text);

        MsgElement msgElement = new MsgElement();
        msgElement.setElementType(1);
        msgElement.setTextElement(textElement);
        return msgElement;
    }

    @NonNull
    public static MsgElement nt_build_ark(@NonNull String json) {
        MsgElement msgElement = new MsgElement();
        ArkElement arkElement = new ArkElement(json,null,null);
        msgElement.setArkElement(arkElement);
        msgElement.setElementType(10);
        return msgElement;
    }

    public static MsgElement nt_build_pic(String path){
        try {
            Object helper = XClass.newInstance(Initiator.loadClass("com.tencent.qqnt.msg.api.impl.MsgUtilApiImpl"));
            return XMethod.obj(helper).name("createPicElement").ret(MsgElement.class).param(String.class,boolean.class,int.class).invoke(path,true,0);
        } catch (Exception e) {
            Logger.e("MsgBuilder.nt_build_pic",e);
            throw new RuntimeException(e);
        }
    }

    public static MsgElement nt_build_sticker(String path){
        try {
            Object helper = XClass.newInstance(Initiator.loadClass("com.tencent.qqnt.msg.api.impl.MsgUtilApiImpl"));
            return XMethod.obj(helper).name("createPicElement").ret(MsgElement.class).param(String.class,boolean.class,int.class).invoke(path,true,1);
        } catch (Exception e) {
            Logger.e("MsgBuilder.nt_build_pic",e);
            throw new RuntimeException(e);
        }
    }


    public static MsgElement nt_build_pic_guild(String path){
        try {
            Object helper = XClass.newInstance(Initiator.loadClass("com.tencent.qqnt.msg.api.impl.MsgUtilApiImpl"));
            return XMethod.obj(helper).name("createPicElementForGuild").ret(MsgElement.class).param(String.class,boolean.class,int.class).invoke(path,true,0);
        } catch (Exception e) {
            Logger.e("MsgBuilder.nt_build_pic_guild",e);
            throw new RuntimeException(e);
        }
    }
    public static Object build_pic(Object _SessionInfo,String path){
        try {
            Object picObj = XMethod.clz("com.tencent.mobileqq.activity.ChatActivityFacade").ret(Initiator.loadClass("com.tencent.mobileqq.data.ChatMessage"))
                    .param(
                            Initiator.loadClass("com.tencent.mobileqq.app.QQAppInterface"),
                            Initiator.loadClass("com.tencent.mobileqq.activity.aio.SessionInfo"),
                            String.class
                    ).invoke(AppRuntimeHelper.getQQAppInterface(), _SessionInfo, path);
            XField.obj(picObj).name("md5").set(DataUtils.getFileMD5(new File(path)));
            XField.obj(picObj).name("uuid").set(DataUtils.getFileMD5(new File(path)) + ".jpg");
            XField.obj(picObj).name("localUUID").set(UUID.randomUUID().toString());
            XMethod.obj(picObj).name("prewrite").ret(void.class).invoke();
            return picObj;
        }catch (Exception e){
            Logger.e("MsgBuilder.build_pic", e);
            return null;
        }
    }
    public static Object copy_new_flash_chat(Object source){
        try {
            Method ArkChatObj;
            if (HostInfo.requireMinQQVersion(QQVersion.QQ_8_8_90)){
                ArkChatObj = XMethod.clz("com.tencent.mobileqq.service.h.r")
                        .ret(Initiator.loadClass("com.tencent.mobileqq.data.MessageForArkFlashChat"))
                        .param(
                                Initiator.loadClass("com.tencent.mobileqq.app.QQAppInterface"),
                                String.class, String.class, int.class,
                                Initiator.loadClass("com.tencent.mobileqq.data.ArkFlashChatMessage")
                        ).get();
            }else {
                ArkChatObj = XMethod.clz("com.tencent.mobileqq.service.message.MessageRecordFactory")
                        .ret(Initiator.loadClass("com.tencent.mobileqq.data.MessageForArkFlashChat"))
                        .param(
                                Initiator.loadClass("com.tencent.mobileqq.app.QQAppInterface"),
                                String.class, String.class, int.class,
                                Initiator.loadClass("com.tencent.mobileqq.data.ArkFlashChatMessage")
                        ).get();
            }

            Object sArk = XField.obj(source).name("ark_app_message").get();
            int isTroop = XField.obj(source).name("istroop").type(int.class).get();
            String FriendUin = XField.obj(source).name("frienduin").type(String.class).get();
            return ArkChatObj.invoke(null, QAppUtils.getAppRuntime(), FriendUin, QAppUtils.getCurrentUin(), isTroop, sArk);
        } catch (Exception e) {
            return null;
        }
    }
    public static Object copy_market_face_msg(Object source){
        try {
            Object mMessageRecord = build_common_message_record(-2007);
            XMethod.obj(mMessageRecord).name("initInner").ret(void.class).param(String.class, String.class, String.class, String.class, long.class, int.class, int.class, long.class)
                    .invoke(QAppUtils.getCurrentUin(), XField.obj(source).name("frienduin").get(), QAppUtils.getCurrentUin(), "[原创表情]", System.currentTimeMillis() / 1000,
                            -2007,
                            XField.obj(source).name("istroop").get(), System.currentTimeMillis() / 1000);
            XField.obj(mMessageRecord).name("msgData").set(XField.obj(source).name("msgData").type(byte[].class).get());

            String strName = XField.obj(source).name("sendFaceName").get();

            if (strName != null) {
                XField.obj(mMessageRecord).name("sendFaceName").set(strName);
            }
            XMethod.obj(mMessageRecord).name("doParse").ret(void.class).invoke();
            return rebuild_message(mMessageRecord);
        }catch (Exception e){
            Logger.e("MsgBuilder.copy_market_face_msg", e);
            return null;
        }
    }
    public static Object copy_poke_msg(Object source){
        try {
            Object PokeEmo = XClass.newInstance(Initiator.loadClass("com.tencent.mobileqq.data.MessageForPokeEmo"));
            XField.obj(PokeEmo).name("msgtype").set(-5018);
            XField.obj(PokeEmo).name("pokeemoId").set(13);
            XField.obj(PokeEmo).name("pokeemoPressCount").set(XField.obj(source).name("pokeemoPressCount").get());
            XField.obj(PokeEmo).name("emoIndex").set(XField.obj(source).name("emoIndex").get());
            XField.obj(PokeEmo).name("summary").set(XField.obj(source).name("summary").get());
            XField.obj(PokeEmo).name("emoString").set(XField.obj(source).name("emoString").get());
            XField.obj(PokeEmo).name("emoCompat").set(XField.obj(source).name("emoCompat").get());
            XMethod.obj(PokeEmo).name("initMsg").ret(void.class).invoke();


            String friendInfo = XField.obj(source).name("frienduin").type(String.class).get();
            int istroop = XField.obj(source).name("istroop").type(int.class).get();

            XMethod.clz(HostInfo.requireMinQQVersion(QQVersion.QQ_8_9_0) ? Initiator.loadClass("com.tencent.mobileqq.service.h.r"):
                            Initiator.loadClass("com.tencent.mobileqq.service.message.MessageRecordFactory"))
                    .ret(void.class)
                    .param(
                            Initiator.loadClass("com.tencent.common.app.AppInterface"),
                            Initiator.loadClass("com.tencent.mobileqq.data.MessageRecord"),
                            String.class,
                            String.class,
                            int.class
                    ).invoke(QAppUtils.getAppRuntime(), PokeEmo, friendInfo, QAppUtils.getCurrentUin(), istroop);
            return PokeEmo;
        } catch (Exception e) {
            Logger.e("MsgBuilder.copy_poke_msg", e);
            return null;
        }
    }
    public static Object rebuild_message(Object record){
        try{
            if (HostInfo.requireMinQQVersion(QQVersion.QQ_8_9_0)){
                return XMethod.clz("com.tencent.mobileqq.service.h.r").ret(Initiator.loadClass("com.tencent.mobileqq.data.MessageRecord")).invoke(record);
            }else {
                return XMethod.clz("com.tencent.mobileqq.service.message.MessageRecordFactory").ret(Initiator.loadClass("com.tencent.mobileqq.data.MessageRecord")).invoke(record);
            }

        }catch (Exception e){
            return null;
        }
    }
    public static Object build_common_message_record(int type){
        try{
            Method CallMethod = null;
            if (HostInfo.requireMinQQVersion(QQVersion.QQ_8_9_0)){
                CallMethod = XMethod.clz("com.tencent.mobileqq.service.h.r")
                        .name("d")
                        .ret(Initiator.loadClass("com.tencent.mobileqq.data.MessageRecord"))
                        .param(int.class).get();
            } else if (HostInfo.requireMinQQVersion(QQVersion.QQ_8_8_93)){

                CallMethod = XMethod.clz("com.tencent.mobileqq.service.message.MessageRecordFactory")
                        .name("d")
                        .ret(Initiator.loadClass("com.tencent.mobileqq.data.MessageRecord"))
                        .param(int.class).get();
            }else {
                CallMethod = XMethod.clz("com.tencent.mobileqq.service.message.MessageRecordFactory")
                        .name("a")
                        .ret(Initiator.loadClass("com.tencent.mobileqq.data.MessageRecord"))
                        .param(int.class).get();
            }
            return CallMethod.invoke(null,type);
        }catch (Exception e){
            return null;
        }
    }
}
