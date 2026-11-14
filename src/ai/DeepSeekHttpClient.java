package ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import server.ServerConfig;
import util.JsonUtil;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * DeepSeek 官方 API 客户端：JDK HttpClient + Gson，非流式。
 */
public class DeepSeekHttpClient implements DeepSeekClient {

    private final ServerConfig config;
    private final HttpClient httpClient;

    public DeepSeekHttpClient(ServerConfig config) {
        this.config = config;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(effectiveTimeoutMs()))
                .build();
    }

    @Override
    public String chatCompletion(List<AiMessage> messages) throws Exception {
        return chatCompletionResult(messages).content;
    }

    @Override
    public String chatCompletion(List<AiMessage> messages, int maxTokens)
            throws Exception {
        return chatCompletionResult(messages, maxTokens).content;
    }

    @Override
    public String chatCompletion(List<AiMessage> messages,
                                 int maxTokens,
                                 boolean thinkingEnabled)
            throws Exception {
        return chatCompletionResult(messages, maxTokens,
                thinkingEnabled).content;
    }

    @Override
    public DeepSeekResult chatCompletionResult(List<AiMessage> messages)
            throws Exception {
        return chatCompletionResult(messages, effectiveMaxTokens(),
                config.aiThinking);
    }

    @Override
    public DeepSeekResult chatCompletionResult(List<AiMessage> messages,
                                               int maxTokens)
            throws Exception {
        return chatCompletionResult(messages, maxTokens,
                config.aiThinking);
    }

    @Override
    public DeepSeekResult chatCompletionResult(List<AiMessage> messages,
                                               int maxTokens,
                                               boolean thinkingEnabled)
            throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("model", effectiveModel());
        body.addProperty("stream", false);
        body.addProperty("max_tokens", maxTokens > 0
                ? maxTokens : effectiveMaxTokens());

        JsonObject thinking = new JsonObject();
        thinking.addProperty("type",
                thinkingEnabled ? "enabled" : "disabled");
        body.add("thinking", thinking);

        JsonObject responseFormat = new JsonObject();
        responseFormat.addProperty("type", "json_object");
        body.add("response_format", responseFormat);

        JsonArray array = new JsonArray();
        for (AiMessage message : messages) {
            JsonObject item = new JsonObject();
            item.addProperty("role", message.role);
            if (message.images == null || message.images.isEmpty()) {
                item.addProperty("content", message.content);
            } else {
                JsonArray content = new JsonArray();
                if (!message.content.isEmpty()) {
                    JsonObject text = new JsonObject();
                    text.addProperty("type", "text");
                    text.addProperty("text", message.content);
                    content.add(text);
                }
                for (AiImagePart image : message.images) {
                    JsonObject imagePart = new JsonObject();
                    imagePart.addProperty("type", "image_url");
                    JsonObject url = new JsonObject();
                    url.addProperty("url", "data:" + image.mime
                            + ";base64," + image.base64Data);
                    imagePart.add("image_url", url);
                    content.add(imagePart);
                }
                item.add("content", content);
            }
            array.add(item);
        }
        body.add("messages", array);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.aiBaseUrl))
                .timeout(Duration.ofMillis(effectiveTimeoutMs()))
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + config.aiApiKey)
                .POST(HttpRequest.BodyPublishers.ofString(
                        JsonUtil.stringify(body), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String detail = response.body() == null ? "" : response.body().trim();
            if (detail.length() > 300) {
                detail = detail.substring(0, 300) + "…";
            }
            throw new IllegalStateException("DeepSeek HTTP "
                    + response.statusCode() + (detail.isEmpty() ? "" : "：" + detail));
        }

        JsonObject root = JsonUtil.parseObject(response.body());
        if (root == null || !root.has("choices")
                || !root.get("choices").isJsonArray()) {
            throw new IllegalStateException("DeepSeek 响应缺少 choices");
        }
        JsonArray choices = root.getAsJsonArray("choices");
        if (choices.size() == 0 || !choices.get(0).isJsonObject()) {
            throw new IllegalStateException("DeepSeek 响应没有候选结果");
        }
        JsonObject choice = choices.get(0).getAsJsonObject();
        if (!choice.has("message") || !choice.get("message").isJsonObject()) {
            throw new IllegalStateException("DeepSeek 响应缺少 message");
        }
        JsonObject message = choice.getAsJsonObject("message");
        String content = "";
        if (message.has("content") && !message.get("content").isJsonNull()) {
            content = message.get("content").getAsString();
        }
        boolean reasoningOnly = content.trim().isEmpty()
                && message.has("reasoning_content")
                && !message.get("reasoning_content").isJsonNull()
                && !message.get("reasoning_content").getAsString()
                .trim().isEmpty();
        String finishReason = choice.has("finish_reason")
                && !choice.get("finish_reason").isJsonNull()
                ? choice.get("finish_reason").getAsString() : "";
        String requestId = response.headers().firstValue("x-request-id")
                .orElse("");
        if (requestId.isEmpty() && root.has("id")
                && !root.get("id").isJsonNull()) {
            requestId = root.get("id").getAsString();
        }
        int usedTokens = maxTokens > 0 ? maxTokens : effectiveMaxTokens();
        return new DeepSeekResult(content, finishReason, requestId,
                reasoningOnly, usedTokens, thinkingEnabled);
    }

    private String effectiveModel() {
        if (config.aiProEnabled) {
            String model = config.aiPro.model();
            if (model != null && !model.trim().isEmpty()) {
                return model.trim();
            }
        }
        return config.aiModel;
    }

    private int effectiveMaxTokens() {
        if (config.aiProEnabled) {
            int tokens = config.aiPro.getInt("ai.pro.maxTokens");
            if (tokens > 0) {
                return tokens;
            }
        }
        return config.aiMaxTokens;
    }

    private int effectiveTimeoutMs() {
        if (config.aiProEnabled) {
            int timeout = config.aiPro.getInt("ai.pro.timeoutMs");
            if (timeout > 0) {
                return timeout;
            }
        }
        return config.aiTimeoutMs;
    }
}
