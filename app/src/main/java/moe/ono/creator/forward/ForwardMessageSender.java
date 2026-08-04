package moe.ono.creator.forward;

import static moe.ono.bridge.ntapi.ChatTypeConstants.C2C;
import static moe.ono.bridge.ntapi.ChatTypeConstants.GROUP;
import static moe.ono.util.Utils.bytesToHex;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.zip.GZIPOutputStream;

import moe.ono.bridge.kernelcompat.ContactCompat;
import moe.ono.creator.PacketHelperDialog;
import moe.ono.hooks.base.util.Toasts;
import moe.ono.hooks.protocol.QPacketHelperKt;
import moe.ono.service.QQInterfaces;
import moe.ono.util.Logger;
import moe.ono.util.SyncUtils;

/** Two-stage merged-forward sender: upload MultiMsg, obtain resid, then send Ark card. */
public final class ForwardMessageSender {
    private static final String CMD = "trpc.group.long_msg_interface.MsgService.SsoSendLongMsg";

    private ForwardMessageSender() {}

    public static void send(
            Context context,
            ForwardMessageDraft draft,
            String currentElements,
            String defaultUin,
            String defaultNickname,
            String sourceText,
            String summaryText,
            boolean completeMode,
            String peer,
            int chatType,
            ContactCompat contactCompat
    ) {
        final List<ForwardMessageNode> nodes = new ArrayList<>(draft.snapshot());
        try {
            if (nodes.isEmpty()) {
                long uin = Long.parseLong(defaultUin.trim());
                nodes.add(new ForwardMessageNode(
                        uin,
                        defaultNickname == null || defaultNickname.trim().isEmpty()
                                ? String.valueOf(uin) : defaultNickname.trim(),
                        currentElements,
                        System.currentTimeMillis() / 1000L
                ));
            }
            for (ForwardMessageNode node : nodes) node.parseElements();
        } catch (Exception e) {
            Toasts.error(context, "转发草稿无效：" + e.getMessage());
            return;
        }

        Toasts.info(context, "正在上传 " + nodes.size() + " 条转发消息…");
        new Thread(() -> {
            try {
                long groupUin = chatType == GROUP ? Long.parseLong(peer) : 0L;
                String fileName = UUID.randomUUID().toString();
                JSONObject multiMsg = buildMultiMsg(nodes, groupUin, chatType == GROUP, fileName);
                byte[] encoded = QPacketHelperKt.buildMessage(multiMsg.toString());
                byte[] compressed = gzip(encoded);
                JSONObject request = buildUploadRequest(peer, chatType, compressed);
                JSONObject response = QQInterfaces.sendBufferAndWait(
                        CMD,
                        true,
                        QPacketHelperKt.buildMessage(request.toString())
                );
                String resid = extractResid(response);
                if (resid.isEmpty()) {
                    throw new IllegalStateException("服务器未返回 resid；响应=" + String.valueOf(response));
                }

                Logger.d("ForwardMessageSender-resid", resid);
                if (!completeMode) {
                    SyncUtils.runOnUiThread(() -> Toasts.success(
                            context,
                            "上传成功（调试模式，未发送卡片）\nresid=" + resid
                    ));
                    return;
                }

                String source = sourceText == null ? "" : sourceText.trim();
                if (source.isEmpty()) source = chatType == GROUP ? "群聊的聊天记录" : "聊天记录";
                String summary = summaryText == null ? "" : summaryText.trim();
                if (summary.isEmpty()) summary = "查看" + nodes.size() + "条转发消息";
                String ark = buildArk(nodes, resid, source, summary, fileName).toString();
                SyncUtils.runOnUiThread(() -> {
                    try {
                        PacketHelperDialog.send_ark_msg(ark, contactCompat);
                        Toasts.success(context, "合并转发已发送");
                    } catch (Exception e) {
                        Logger.e("发送转发 Ark 失败", e);
                        Toasts.error(context, "资源上传成功，但卡片发送失败：" + e.getMessage());
                    }
                });
            } catch (Exception e) {
                Logger.e("ForwardMessageSender", e);
                SyncUtils.runOnUiThread(() -> Toasts.error(context, "合并转发失败：" + e.getMessage()));
            }
        }, "ono-forward-uploader").start();
    }

    private static JSONObject buildMultiMsg(
            List<ForwardMessageNode> nodes,
            long groupUin,
            boolean group,
            String fileName
    ) throws Exception {
        JSONArray records = new JSONArray();
        int baseSeq = ThreadLocalRandom.current().nextInt(1000, 900000);
        for (int i = 0; i < nodes.size(); i++) {
            ForwardMessageNode node = nodes.get(i);
            JSONObject head = new JSONObject()
                    .put("17", 8)
                    .put("1", node.getUin())
                    .put("2", node.getUin())
                    .put("3", 82)
                    .put("4", 1)
                    .put("5", baseSeq + i)
                    .put("6", node.getTimeSeconds());
            if (group) {
                head.put("9", new JSONObject()
                        .put("1", groupUin)
                        .put("2", 1)
                        .put("3", 0)
                        .put("4", node.getNickname())
                        .put("6", 1)
                        .put("7", 1));
            } else {
                // C2C records use the peer display block that Ono's original forward builder used.
                head.put("8", new JSONObject()
                        .put("1", 10001)
                        .put("4", node.getNickname())
                        .put("5", 2));
            }

            JSONObject contentHead = new JSONObject()
                    .put("1", 1)
                    .put("2", 0)
                    .put("3", 0);

            JSONObject body = new JSONObject().put(
                    "1",
                    new JSONObject().put("2", node.parseElements())
            );

            records.put(new JSONObject()
                    .put("1", head)
                    .put("2", contentHead)
                    .put("3", body));
        }

        JSONObject item = new JSONObject()
                .put("1", fileName)
                .put("2", new JSONObject().put("1", records));
        return new JSONObject().put("2", new JSONArray().put(item));
    }

    private static byte[] gzip(byte[] input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(output)) {
            gzip.write(input);
        }
        return output.toByteArray();
    }

    private static JSONObject buildUploadRequest(String peer, int chatType, byte[] compressed) throws Exception {
        JSONObject info = new JSONObject()
                .put("1", chatType == C2C ? 1 : 3)
                // LongMsgUid.field 2. Group uploads require both the string peer and groupCode.
                .put("2", new JSONObject().put("2", peer))
                .put("4", "hex->" + bytesToHex(compressed));
        if (chatType == GROUP) {
            info.put("3", Long.parseLong(peer));
        }
        return new JSONObject()
                .put("2", info)
                .put("15", new JSONObject()
                        .put("1", 4)
                        .put("2", 1)
                        .put("3", 7)
                        .put("4", 0));
    }

    private static String extractResid(JSONObject response) {
        if (response == null) return "";
        Object rawResult = response.opt("2");
        if (rawResult instanceof JSONObject) {
            String direct = ((JSONObject) rawResult).optString("3", "");
            if (!direct.isEmpty()) return direct;
        } else if (rawResult instanceof JSONArray) {
            JSONArray results = (JSONArray) rawResult;
            for (int i = 0; i < results.length(); i++) {
                JSONObject item = results.optJSONObject(i);
                if (item == null) continue;
                String direct = item.optString("3", "");
                if (!direct.isEmpty()) return direct;
            }
        }
        return findBase64LikeString(response, 0);
    }

    private static String findBase64LikeString(Object value, int depth) {
        if (value == null || depth > 8) return "";
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            JSONArray names = object.names();
            if (names == null) return "";
            for (int i = 0; i < names.length(); i++) {
                Object child = object.opt(names.optString(i));
                String found = findBase64LikeString(child, depth + 1);
                if (!found.isEmpty()) return found;
            }
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) {
                String found = findBase64LikeString(array.opt(i), depth + 1);
                if (!found.isEmpty()) return found;
            }
        } else if (value instanceof String) {
            String text = (String) value;
            if (text.length() >= 32 && text.matches("[A-Za-z0-9+/=_-]+")) return text;
        }
        return "";
    }

    private static JSONObject buildArk(
            List<ForwardMessageNode> nodes,
            String resid,
            String source,
            String summary,
            String fileName
    ) throws Exception {
        String uniseq = fileName;
        JSONArray news = new JSONArray();
        for (int i = 0; i < Math.min(4, nodes.size()); i++) {
            ForwardMessageNode node = nodes.get(i);
            news.put(new JSONObject().put(
                    "text",
                    node.getNickname() + ": " + node.previewText()
            ));
        }
        JSONObject detail = new JSONObject()
                .put("news", news)
                .put("resid", resid)
                .put("source", source)
                .put("summary", summary)
                .put("uniseq", uniseq);
        JSONObject extra = new JSONObject()
                .put("filename", uniseq)
                .put("tsum", nodes.size());
        return new JSONObject()
                .put("app", "com.tencent.multimsg")
                .put("config", new JSONObject()
                        .put("autosize", 1)
                        .put("forward", 1)
                        .put("round", 1)
                        .put("type", "normal")
                        .put("width", 300))
                .put("desc", "[聊天记录]")
                .put("extra", extra.toString() + "\n")
                .put("meta", new JSONObject().put("detail", detail))
                .put("prompt", "[聊天记录]")
                .put("ver", "0.0.0.5")
                .put("view", "contact");
    }
}
