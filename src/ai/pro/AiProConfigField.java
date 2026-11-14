package ai.pro;

import java.util.List;

/**
 * AI Pro 配置项元数据。可视化设置与 Headless 共用同一份定义。
 */
public final class AiProConfigField {

    public enum Type {
        INTEGER,
        DECIMAL,
        BOOLEAN,
        TEXT,
        LIST,
        SECRET
    }

    public final String key;
    public final String label;
    public final String group;
    public final Type type;
    public final Integer minInteger;
    public final Integer maxInteger;
    public final Double minDecimal;
    public final Double maxDecimal;
    public final List<String> choices;
    public final String unit;
    public final String hint;
    public final boolean advanced;
    public final boolean sensitive;
    public final boolean requiresRestart;

    public AiProConfigField(String key,
                            String label,
                            String group,
                            Type type,
                            Integer minInteger,
                            Integer maxInteger,
                            Double minDecimal,
                            Double maxDecimal,
                            List<String> choices,
                            String unit,
                            String hint,
                            boolean advanced,
                            boolean sensitive,
                            boolean requiresRestart) {
        this.key = key;
        this.label = label;
        this.group = group;
        this.type = type;
        this.minInteger = minInteger;
        this.maxInteger = maxInteger;
        this.minDecimal = minDecimal;
        this.maxDecimal = maxDecimal;
        this.choices = choices == null ? List.of() : List.copyOf(choices);
        this.unit = unit == null ? "" : unit;
        this.hint = hint == null ? "" : hint;
        this.advanced = advanced;
        this.sensitive = sensitive;
        this.requiresRestart = requiresRestart;
    }

    public String rangeHint() {
        StringBuilder builder = new StringBuilder();
        if (!unit.isEmpty()) {
            builder.append(unit);
        }
        String range = null;
        if (minInteger != null && maxInteger != null) {
            range = minInteger + "–" + maxInteger;
        } else if (minDecimal != null && maxDecimal != null) {
            range = trimNumber(minDecimal) + "–" + trimNumber(maxDecimal);
        }
        if (range != null) {
            if (builder.length() > 0) {
                builder.append("，");
            }
            builder.append(range);
        }
        if (!hint.isEmpty()) {
            if (builder.length() > 0) {
                builder.append("；");
            }
            builder.append(hint);
        }
        if (!choices.isEmpty()) {
            if (builder.length() > 0) {
                builder.append("；");
            }
            builder.append("可选：").append(String.join(" / ", choices));
        }
        return builder.toString();
    }

    public String tooltip() {
        return key + (hint.isEmpty() ? "" : "\n" + hint);
    }

    private static String trimNumber(double value) {
        return value == Math.rint(value)
                ? String.valueOf((long) value)
                : String.valueOf(value);
    }
}
