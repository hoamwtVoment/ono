package moe.ono.creator.forward;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** One independent record inside a QQ MultiMsg forward container. */
public final class ForwardMessageNode {
    private long uin;
    private String nickname;
    private String elementsJson;
    private long timeSeconds;

    public ForwardMessageNode(long uin, String nickname, String elementsJson, long timeSeconds) {
        this.uin = uin;
        this.nickname = nickname == null ? "" : nickname;
        this.elementsJson = elementsJson == null ? "[]" : elementsJson;
        this.timeSeconds = timeSeconds > 0 ? timeSeconds : System.currentTimeMillis() / 1000L;
    }

    public ForwardMessageNode copy() {
        return new ForwardMessageNode(uin, nickname, elementsJson, timeSeconds);
    }

    public long getUin() { return uin; }
    public void setUin(long uin) { this.uin = uin; }
    public String getNickname() { return nickname; }
    public void setNickname(String nickname) { this.nickname = nickname == null ? "" : nickname; }
    public String getElementsJson() { return elementsJson; }
    public void setElementsJson(String elementsJson) { this.elementsJson = elementsJson == null ? "[]" : elementsJson; }
    public long getTimeSeconds() { return timeSeconds; }
    public void setTimeSeconds(long timeSeconds) { this.timeSeconds = timeSeconds; }

    public JSONArray parseElements() throws JSONException {
        String source = elementsJson.trim();
        if (source.isEmpty()) throw new JSONException("消息元素不能为空");
        if (source.startsWith("[")) return new JSONArray(source);
        if (source.startsWith("{")) return new JSONArray().put(new JSONObject(source));
        throw new JSONException("消息元素必须是 JSON 对象或数组");
    }

    public String previewText() {
        try {
            JSONArray elements = parseElements();
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < elements.length(); i++) {
                JSONObject element = elements.optJSONObject(i);
                if (element == null) continue;
                JSONObject text = element.optJSONObject("1");
                if (text != null) {
                    String value = text.optString("1", "");
                    if (!value.isEmpty()) out.append(value);
                } else if (element.has("4")) {
                    out.append("[图片]");
                } else if (element.has("6")) {
                    out.append("[语音]");
                } else if (element.has("12")) {
                    out.append("[结构化消息]");
                } else if (element.has("51")) {
                    out.append("[卡片]");
                } else {
                    out.append("[消息]");
                }
                if (out.length() >= 80) break;
            }
            String result = out.toString().replace('\n', ' ').trim();
            return result.isEmpty() ? "[消息]" : result;
        } catch (Exception ignored) {
            return "[无法预览]";
        }
    }
}
