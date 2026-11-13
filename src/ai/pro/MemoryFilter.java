package ai.pro;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 记忆安全、PII、无意义内容过滤。
 */
public final class MemoryFilter {

    private static final Pattern PHONE = Pattern.compile("\\b1\\d{10}\\b");
    private static final Pattern ID_CARD = Pattern.compile(
            "\\b\\d{17}[\\dXx]\\b");
    private static final Pattern BANK_CARD = Pattern.compile("\\b\\d{16,19}\\b");
    private static final String[] SENSITIVE = {
            "password", "passwd", "token", "api key", "apikey",
            "密钥", "密码", "验证码", "身份证", "银行卡"
    };
    private static final String[] SAFETY = {
            "违法", "暴力", "色情", "仇恨", "歧视", "诈骗", "制毒", "赌博"
    };
    private static final String[] GREETINGS = {
            "你好", "您好", "谢谢", "谢了", "哈哈", "嗯", "哦", "在吗",
            "早上好", "晚上好", "晚安"
    };

    private MemoryFilter() {
    }

    /**
     * @return 过滤后的内容；null 表示丢弃。
     */
    public static String filter(String content,
                                AiProConfig config,
                                int minChars) {
        String normalized = MemoryText.normalize(content);
        if (normalized.isEmpty()
                || normalized.codePointCount(0, normalized.length()) < minChars
                || meaningless(normalized)) {
            return null;
        }
        String lower = normalized.toLowerCase(Locale.ROOT);
        for (String word : SAFETY) {
            if (lower.contains(word)) {
                return null;
            }
        }
        boolean sensitive = false;
        for (String word : SENSITIVE) {
            if (lower.contains(word)) {
                sensitive = true;
                break;
            }
        }
        if (!sensitive && !config.getBoolean("ai.pro.privacy.allowPhone")
                && (PHONE.matcher(normalized).find()
                || ID_CARD.matcher(normalized).find()
                || BANK_CARD.matcher(normalized).find())) {
            sensitive = true;
        }
        if (sensitive) {
            String mode = config.getString("ai.pro.privacy.mode").trim();
            if (!"redact".equalsIgnoreCase(mode)) {
                return null;
            }
            String redacted = normalized;
            for (String word : SENSITIVE) {
                redacted = redacted.replaceAll("(?i)" + Pattern.quote(word),
                        "[隐私]");
            }
            redacted = PHONE.matcher(redacted).replaceAll("[隐私]");
            redacted = ID_CARD.matcher(redacted).replaceAll("[隐私]");
            redacted = BANK_CARD.matcher(redacted).replaceAll("[隐私]");
            return redacted;
        }
        return normalized;
    }

    public static boolean meaningless(String value) {
        String compact = value.replaceAll("[\\s\\p{Punct}，。！？、；：“”‘’（）【】《》]+", "");
        if (compact.isEmpty()) {
            return true;
        }
        for (String greeting : GREETINGS) {
            if (compact.equals(greeting)) {
                return true;
            }
        }
        return false;
    }

    public static List<Pattern> customPatterns(AiProConfig config) {
        List<Pattern> result = new ArrayList<>();
        String value = config.getString("ai.pro.privacy.patterns");
        if (value == null || value.trim().isEmpty()) {
            return result;
        }
        for (String part : value.split("[,，]")) {
            String regex = part.trim();
            if (regex.isEmpty()) {
                continue;
            }
            try {
                result.add(Pattern.compile(regex));
            } catch (Exception ignored) {
                // 非法自定义正则忽略。
            }
        }
        return result;
    }

    public static boolean matchesCustom(String content,
                                        List<Pattern> patterns) {
        if (patterns == null || patterns.isEmpty()) {
            return false;
        }
        for (Pattern pattern : patterns) {
            Matcher matcher = pattern.matcher(content);
            if (matcher.find()) {
                return true;
            }
        }
        return false;
    }
}
