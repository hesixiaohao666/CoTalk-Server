package util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Gson 封装：协议与持久化共用。
 */
public final class JsonUtil {

    private static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .create();

    private JsonUtil() {
    }

    public static JsonObject parseObject(String line) {
        if (line == null || line.trim().isEmpty()) {
            return null;
        }
        try {
            JsonElement element = JsonParser.parseString(line);
            return element.isJsonObject() ? element.getAsJsonObject() : null;
        } catch (Exception e) {
            return null;
        }
    }

    public static String stringify(JsonElement element) {
        return GSON.toJson(element);
    }
}
