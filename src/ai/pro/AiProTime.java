package ai.pro;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.Locale;

/**
 * AI Pro 本地时间与时区。只暴露日期、时间、星期和时区，不暴露主机信息。
 */
public final class AiProTime {

    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT);
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);

    private AiProTime() {
    }

    public static ZoneId zone(AiProConfig config) {
        String value = config == null
                ? "" : config.getString("ai.pro.time.timezone").trim();
        if (value.isEmpty()) {
            return ZoneId.systemDefault();
        }
        try {
            return ZoneId.of(value);
        } catch (Exception e) {
            return ZoneId.systemDefault();
        }
    }

    public static String today(AiProConfig config) {
        return LocalDate.now(zone(config)).format(DATE);
    }

    public static ZonedDateTime now(AiProConfig config) {
        return ZonedDateTime.now(zone(config));
    }

    public static String promptSection(AiProConfig config) {
        if (config == null || !config.getBoolean("ai.pro.time.enabled")) {
            return "";
        }
        ZonedDateTime now = now(config);
        String zone = config.getString("ai.pro.time.timezone").trim();
        if (zone.isEmpty()) {
            zone = now.getZone().getId();
        }
        return "【服务器本地时间】\n"
                + "日期：" + now.format(DATE) + "\n"
                + "时间：" + now.format(TIME) + "\n"
                + "星期：" + now.getDayOfWeek().getDisplayName(
                TextStyle.FULL, Locale.SIMPLIFIED_CHINESE) + "\n"
                + "时区：" + zone;
    }
}
