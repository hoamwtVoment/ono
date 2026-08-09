package moe.ono.creator.forward;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Iterator;

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
        JSONArray elements;
        if (source.startsWith("[")) {
            elements = new JSONArray(source);
        } else if (source.startsWith("{")) {
            elements = new JSONArray().put(new JSONObject(source));
        } else {
            throw new JSONException("消息元素必须是 JSON 对象或数组");
        }
        if (elements.length() == 0) {
            throw new JSONException("消息元素数组不能为空");
        }
        for (int i = 0; i < elements.length(); i++) {
            JSONObject element = elements.optJSONObject(i);
            if (element == null) {
                throw new JSONException("第 " + (i + 1) + " 个消息元素必须是 JSON 对象");
            }
            if (element.length() == 0) {
                throw new JSONException("第 " + (i + 1) + " 个消息元素不能为空");
            }
            validateNumericKeys(element, "[" + i + "]");
        }
        return elements;
    }

    private static void validateNumericKeys(Object value, String path) throws JSONException {
        if (value == null || value == JSONObject.NULL) {
            throw new JSONException(path + " 的值不能为 null");
        } else if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                int fieldNumber;
                try {
                    fieldNumber = Integer.parseInt(key);
                } catch (NumberFormatException e) {
                    throw new JSONException(path + " 含有非数字字段名：" + key);
                }
                if (fieldNumber <= 0 || fieldNumber > 536_870_911) {
                    throw new JSONException(path + " 含有无效字段号：" + key);
                }
                validateNumericKeys(object.opt(key), path + "." + key);
            }
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) {
                validateNumericKeys(array.opt(i), path + "[" + i + "]");
            }
        } else if (value instanceof Boolean || value instanceof Double || value instanceof Float) {
            throw new JSONException(path + " 的值必须是整数、字符串、对象或数组");
        }
    }

    public String previewText() {
        try {
            JSONArray elements = parseElements();
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < elements.length(); i++) {
                JSONObject element = elements.optJSONObject(i);
                if (element == null) continue;
                String text = extractText(element);
                if (!text.isEmpty()) {
                    out.append(text);
                } else if (element.has("2")) {
                    out.append("[表情]");
                } else if (element.has("3") || element.has("4") || element.has("8")) {
                    out.append("[图片]");
                } else if (element.has("6")) {
                    out.append("[动画表情]");
                } else if (element.has("12")) {
                    out.append("[卡片]");
                } else if (element.has("51") || element.has("53")) {
                    out.append("[卡片]");
                }
                if (out.length() >= 80) break;
            }
            String result = out.toString().replaceAll("[\\r\\n\\t]+", " ")
                    .replaceAll(" {2,}", " ")
                    .trim();
            int codePointCount = result.codePointCount(0, result.length());
            if (codePointCount > 80) {
                int end = result.offsetByCodePoints(0, 80);
                result = result.substring(0, end) + "…";
            }
            return result.isEmpty() ? "消息" : result;
        } catch (Exception ignored) {
            return "消息";
        }
    }

    private static String extractText(JSONObject element) {
        JSONObject protobufText = element.optJSONObject("1");
        if (protobufText != null) {
            String content = protobufText.optString("1", "");
            if (!content.isEmpty()) return content;
        }

        return "";
    }
}
