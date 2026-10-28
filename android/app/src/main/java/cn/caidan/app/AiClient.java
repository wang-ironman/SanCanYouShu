package cn.caidan.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Small OpenAI-compatible HTTP client. Call from a worker thread. */
public final class AiClient {
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 60_000;
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    private static final int MAX_IMAGE_DATA_URL_CHARS = 6 * 1024 * 1024;

    private AiClient() {}

    public static void validate(AiSettings.Config config, boolean requireModel) throws Exception {
        if (config == null) {
            throw new IllegalArgumentException("请先填写 AI 服务地址。");
        }
        String baseUrl = trim(config.baseUrl);
        if (baseUrl.isEmpty()) {
            throw new IllegalArgumentException("请填写 AI 服务地址。");
        }
        URI uri;
        try {
            uri = URI.create(baseUrl);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("AI 服务地址格式不正确。");
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        boolean localHttp = "http".equalsIgnoreCase(scheme) && isLocalHost(host);
        if (!("https".equalsIgnoreCase(scheme) || localHttp) || host == null
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("AI 服务地址须使用 HTTPS；仅本机调试地址可使用 HTTP。");
        }
        if (baseUrl.length() > 2048) {
            throw new IllegalArgumentException("AI 服务地址过长。");
        }
        if (trim(config.apiKey).isEmpty()) {
            throw new IllegalArgumentException("请填写 AI 服务密钥。");
        }
        if (config.apiKey.length() > 4096) {
            throw new IllegalArgumentException("AI 服务密钥过长。");
        }
        if (requireModel && trim(config.model).isEmpty()) {
            throw new IllegalArgumentException("请先选择或填写模型名称。");
        }
        if (config.model != null && config.model.length() > 256) {
            throw new IllegalArgumentException("模型名称过长。");
        }
    }

    public static List<String> models(AiSettings.Config config) throws Exception {
        validate(config, false);
        JSONObject response = request(config, endpoint(config.baseUrl, "/models"), "GET", null);
        JSONArray data = response.optJSONArray("data");
        if (data == null) {
            throw new IOException("服务未返回兼容的模型列表，请手动填写模型名称并确认服务支持 OpenAI 格式。");
        }
        List<String> models = new ArrayList<>();
        for (int i = 0; i < data.length(); i++) {
            JSONObject item = data.optJSONObject(i);
            String id = item == null ? "" : item.optString("id", "").trim();
            if (!id.isEmpty() && !models.contains(id)) {
                models.add(id);
            }
        }
        Collections.sort(models, String.CASE_INSENSITIVE_ORDER);
        return models;
    }

    public static String complete(AiSettings.Config config, String system, String user, String imageDataUrl)
            throws Exception {
        validate(config, true);
        if (imageDataUrl != null && !imageDataUrl.isEmpty()
                && (!imageDataUrl.startsWith("data:image/") || !imageDataUrl.contains(";base64,")
                || imageDataUrl.length() > MAX_IMAGE_DATA_URL_CHARS)) {
            throw new IllegalArgumentException("截图格式不支持或文件太大，请选择较小的图片。");
        }
        JSONObject body = new JSONObject();
        body.put("model", config.model.trim());
        body.put("stream", false);
        JSONArray messages = new JSONArray();
        messages.put(new JSONObject().put("role", "system").put("content", system == null ? "" : system));
        JSONObject userMessage = new JSONObject().put("role", "user");
        if (imageDataUrl == null || imageDataUrl.isEmpty()) {
            userMessage.put("content", user == null ? "" : user);
        } else {
            JSONArray content = new JSONArray();
            content.put(new JSONObject().put("type", "text").put("text", user == null ? "" : user));
            content.put(new JSONObject().put("type", "image_url")
                    .put("image_url", new JSONObject().put("url", imageDataUrl)));
            userMessage.put("content", content);
        }
        messages.put(userMessage);
        body.put("messages", messages);

        JSONObject response = request(config, endpoint(config.baseUrl, "/chat/completions"),
                "POST", body.toString());
        JSONArray choices = response.optJSONArray("choices");
        JSONObject choice = choices == null ? null : choices.optJSONObject(0);
        JSONObject message = choice == null ? null : choice.optJSONObject("message");
        if (message == null) {
            throw new IOException("AI 服务未返回有效内容，请检查所选模型是否支持对话。");
        }
        Object content = message.opt("content");
        if (content instanceof String && !((String) content).trim().isEmpty()) {
            return ((String) content).trim();
        }
        if (content instanceof JSONArray) {
            JSONArray parts = (JSONArray) content;
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < parts.length(); i++) {
                JSONObject part = parts.optJSONObject(i);
                if (part != null && "text".equals(part.optString("type"))) {
                    if (text.length() > 0) text.append('\n');
                    text.append(part.optString("text", ""));
                }
            }
            if (text.length() > 0) return text.toString().trim();
        }
        throw new IOException("AI 服务返回了空内容，请重试或更换模型。");
    }

    private static JSONObject request(AiSettings.Config config, String address, String method, String body)
            throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(address).openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod(method);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setUseCaches(false);
            connection.setRequestProperty("Authorization", "Bearer " + config.apiKey.trim());
            connection.setRequestProperty("Accept", "application/json");
            if (body != null) {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                connection.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(bytes);
                }
            }
            int status = connection.getResponseCode();
            if (status >= 300 && status < 400) {
                throw new IOException("AI 服务返回了重定向。请直接填写最终的 HTTPS 服务地址。");
            }
            InputStream stream = status >= 200 && status < 300
                    ? connection.getInputStream() : connection.getErrorStream();
            byte[] response = stream == null ? new byte[0] : readLimited(stream);
            if (status < 200 || status >= 300) {
                throw new IOException(httpError(status));
            }
            try {
                return new JSONObject(new String(response, StandardCharsets.UTF_8));
            } catch (org.json.JSONException error) {
                throw new IOException("AI 服务返回的数据格式不正确，请检查服务兼容性。");
            }
        } catch (IOException error) {
            String message = error.getMessage();
            if (message != null && (message.startsWith("AI 服务") || message.startsWith("模型名称")
                    || message.startsWith("请先") || message.startsWith("无法读取"))) {
                throw error;
            }
            throw new IOException("无法连接 AI 服务，请检查网络、服务地址和模型设置。", error);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static byte[] readLimited(InputStream input) throws IOException {
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int count;
            while ((count = stream.read(buffer)) != -1) {
                total += count;
                if (total > MAX_RESPONSE_BYTES) {
                    throw new IOException("AI 服务响应过大，请检查服务设置后重试。");
                }
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private static String endpoint(String baseUrl, String suffix) throws Exception {
        String base = trim(baseUrl);
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        String lower = base.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith("/chat/completions")) base = base.substring(0, base.length() - "/chat/completions".length());
        else if (lower.endsWith("/models")) base = base.substring(0, base.length() - "/models".length());
        lower = base.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith("/v1")) return base + suffix;
        URI uri = URI.create(base);
        if (uri.getPath() == null || uri.getPath().isEmpty() || "/".equals(uri.getPath())) {
            return base + "/v1" + suffix;
        }
        return base + suffix;
    }

    private static boolean isLocalHost(String host) {
        return host != null && ("localhost".equalsIgnoreCase(host)
                || "127.0.0.1".equals(host) || "::1".equals(host) || "[::1]".equals(host)
                || "10.0.2.2".equals(host));
    }

    private static String httpError(int status) {
        switch (status) {
            case 401:
            case 403:
                return "AI 服务拒绝了请求，请检查服务密钥和权限。";
            case 404:
                return "找不到 AI 接口，请检查服务地址是否兼容 OpenAI 格式。";
            case 429:
                return "AI 服务请求过于频繁或额度不足，请稍后重试。";
            default:
                if (status >= 500) return "AI 服务暂时不可用，请稍后重试。";
                return "AI 服务请求失败（HTTP " + status + "），请检查地址、密钥和模型设置。";
        }
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
