package ai.pro;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 记忆文本归一化、bigram、相似度与哈希。
 */
public final class MemoryText {

    private MemoryText() {
    }

    public static String normalize(String value) {
        if (value == null) {
            return "";
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC);
        StringBuilder builder = new StringBuilder(normalized.length());
        boolean space = false;
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            if (Character.isISOControl(c)) {
                if (!space) {
                    builder.append(' ');
                    space = true;
                }
                continue;
            }
            if (Character.isWhitespace(c)) {
                if (!space) {
                    builder.append(' ');
                    space = true;
                }
                continue;
            }
            builder.append(c);
            space = false;
        }
        return builder.toString().trim();
    }

    public static String contentHash(String content) {
        String normalized = normalize(content).toLowerCase(Locale.ROOT);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(
                    normalized.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                hex.append(String.format("%02x", b & 0xFF));
            }
            return hex.toString();
        } catch (Exception e) {
            return Integer.toHexString(normalized.hashCode());
        }
    }

    public static Set<String> tokens(String value) {
        Set<String> result = new HashSet<>();
        String text = normalize(value);
        if (text.isEmpty()) {
            return result;
        }
        StringBuilder token = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isTokenChar(c)) {
                token.append(Character.toLowerCase(c));
            } else if (token.length() > 0) {
                addToken(result, token.toString());
                token.setLength(0);
            }
        }
        if (token.length() > 0) {
            addToken(result, token.toString());
        }
        return result;
    }

    public static Set<String> tokens(String content,
                                     String keywordsJson,
                                     String tagsJson) {
        Set<String> result = tokens(content);
        result.addAll(listTokens(keywordsJson));
        result.addAll(listTokens(tagsJson));
        return result;
    }

    public static double similarity(Set<String> left, Set<String> right) {
        if (left.isEmpty() && right.isEmpty()) {
            return 1.0;
        }
        double dice = dice(left, right);
        return dice;
    }

    /**
     * 0.7 × bigram Dice + 0.3 × 关键词 Jaccard。
     */
    public static double similarity(Memory left, Memory right) {
        double dice = dice(tokens(left.content), tokens(right.content));
        double jaccard = jaccard(
                listValues(left.keywords), listValues(right.keywords));
        return Math.max(0, Math.min(1, 0.7 * dice + 0.3 * jaccard));
    }

    public static double dice(Set<String> left, Set<String> right) {
        if (left.isEmpty() || right.isEmpty()) {
            return 0.0;
        }
        int common = 0;
        for (String token : left) {
            if (right.contains(token)) {
                common++;
            }
        }
        return (2.0 * common) / (left.size() + right.size());
    }

    public static double jaccard(Set<String> left, Set<String> right) {
        if (left.isEmpty() || right.isEmpty()) {
            return 0.0;
        }
        int common = 0;
        for (String value : left) {
            if (right.contains(value)) {
                common++;
            }
        }
        return (double) common / (left.size() + right.size() - common);
    }

    public static Set<String> listValues(String jsonArray) {
        Set<String> result = new HashSet<>();
        if (jsonArray == null || jsonArray.trim().isEmpty()) {
            return result;
        }
        try {
            com.google.gson.JsonElement element =
                    com.google.gson.JsonParser.parseString(jsonArray);
            if (element.isJsonArray()) {
                for (com.google.gson.JsonElement item : element.getAsJsonArray()) {
                    if (item.isJsonPrimitive()
                            && item.getAsJsonPrimitive().isString()) {
                        String value = normalize(item.getAsString())
                                .toLowerCase(Locale.ROOT);
                        if (!value.isEmpty()) {
                            result.add(value);
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            // 非 JSON 时按逗号分隔兜底
            for (String part : jsonArray.split("[,，]+")) {
                String value = normalize(part).toLowerCase(Locale.ROOT);
                if (!value.isEmpty()) {
                    result.add(value);
                }
            }
        }
        return result;
    }

    private static Set<String> listTokens(String jsonArray) {
        Set<String> result = new HashSet<>();
        for (String value : listValues(jsonArray)) {
            result.addAll(tokens(value));
        }
        return result;
    }

    private static void addToken(Set<String> result, String token) {
        if (token.isEmpty()) {
            return;
        }
        if (token.length() <= 1) {
            result.add(token);
            return;
        }
        boolean cjk = containsCjk(token);
        if (!cjk) {
            result.add(token);
        }
        for (int i = 0; i < token.length() - 1; i++) {
            result.add(token.substring(i, i + 2));
        }
    }

    private static boolean isTokenChar(char c) {
        return Character.isLetterOrDigit(c);
    }

    private static boolean containsCjk(String value) {
        for (int i = 0; i < value.length(); i++) {
            Character.UnicodeScript script = Character.UnicodeScript.of(
                    value.charAt(i));
            if (script == Character.UnicodeScript.HAN
                    || script == Character.UnicodeScript.HIRAGANA
                    || script == Character.UnicodeScript.KATAKANA
                    || script == Character.UnicodeScript.HANGUL) {
                return true;
            }
        }
        return false;
    }
}
