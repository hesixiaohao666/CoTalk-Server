package ai.pro;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import server.ServerConfig;
import util.JsonUtil;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * DeepSeek Search API 客户端。搜索与聊天是两次独立请求。
 */
public class AiProSearchClient {

    private final ServerConfig config;
    private final HttpClient httpClient;

    public AiProSearchClient(ServerConfig config) {
        this.config = config;
        int timeout = Math.max(1000, config.aiPro.getInt(
                "ai.pro.search.timeoutMs"));
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(timeout))
                .build();
    }

    public List<AiProSearchResult> search(String query) throws Exception {
        String baseUrl = config.aiPro.getString("ai.pro.search.baseUrl").trim();
        if (baseUrl.isEmpty()) {
            throw new IllegalStateException("搜索 API 地址为空");
        }
        if (config.aiApiKey == null || config.aiApiKey.trim().isEmpty()) {
            throw new IllegalStateException("未配置 AI API Key");
        }

        JsonObject body = new JsonObject();
        body.addProperty("query", query);
        body.addProperty("count", Math.max(1, config.aiPro.getInt(
                "ai.pro.search.count")));
        body.addProperty("need_content", config.aiPro.getBoolean(
                "ai.pro.search.needContent"));

        int timeout = Math.max(1000, config.aiPro.getInt(
                "ai.pro.search.timeoutMs"));
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl))
                .timeout(Duration.ofMillis(timeout))
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + config.aiApiKey.trim())
                .POST(HttpRequest.BodyPublishers.ofString(
                        JsonUtil.stringify(body), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = httpClient.send(
                request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("搜索 API HTTP "
                    + response.statusCode());
        }

        JsonObject root = JsonUtil.parseObject(response.body());
        if (root == null || !root.has("results")
                || !root.get("results").isJsonArray()) {
            throw new IllegalStateException("搜索响应缺少 results");
        }
        JsonArray array = root.getAsJsonArray("results");
        List<AiProSearchResult> results = new ArrayList<>();
        for (JsonElement element : array) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject item = element.getAsJsonObject();
            results.add(new AiProSearchResult(
                    string(item, "title"),
                    string(item, "url"),
                    string(item, "snippet"),
                    string(item, "summary"),
                    string(item, "date"),
                    string(item, "site_name")));
        }
        return results;
    }

    private static String string(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return "";
        }
        try {
            return object.get(key).getAsString();
        } catch (Exception e) {
            return "";
        }
    }
}
