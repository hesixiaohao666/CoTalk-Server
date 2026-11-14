package ai;

import server.ServerConfig;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 峰时/谷时判断。经典引擎与 AI Pro 共用。
 */
public final class AiSchedule {

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);

    private AiSchedule() {
    }

    public static int chance(ServerConfig config) {
        return chance(config, ZonedDateTime.now(zone(config)));
    }

    public static int chance(ServerConfig config, ZonedDateTime now) {
        return isPeak(config, now)
                ? config.aiPeakChance : config.aiOffPeakChance;
    }

    public static boolean isPeak(ServerConfig config, ZonedDateTime now) {
        if (config == null) {
            return false;
        }
        try {
            ZonedDateTime local = (now == null
                    ? ZonedDateTime.now(zone(config))
                    : now).withZoneSameInstant(zone(config));
            Set<DayOfWeek> days = parseDays(config.aiPeakDays);
            List<TimeRange> periods = parsePeriods(config.aiPeakPeriods);
            if (!days.contains(local.getDayOfWeek())) {
                return false;
            }
            LocalTime time = local.toLocalTime();
            for (TimeRange period : periods) {
                if (!time.isBefore(period.start)
                        && time.isBefore(period.end)) {
                    return true;
                }
            }
        } catch (Exception ignored) {
            // 配置非法时按谷时处理，避免阻塞回复。
        }
        return false;
    }

    public static ZoneId zone(ServerConfig config) {
        String value = config == null ? ""
                : config.aiPeakTimezone == null
                ? "" : config.aiPeakTimezone.trim();
        if (value.isEmpty()) {
            return ZoneId.systemDefault();
        }
        return ZoneId.of(value);
    }

    public static void validate(String days, String periods, String timezone) {
        if (timezone != null && !timezone.trim().isEmpty()) {
            try {
                ZoneId.of(timezone.trim());
            } catch (Exception e) {
                throw new IllegalArgumentException("ai.peak.timezone 时区无效");
            }
        }
        parseDays(days);
        parsePeriods(periods);
    }

    public static String statusText(ServerConfig config) {
        if (config == null) {
            return "未配置";
        }
        return "峰时 " + config.aiPeakChance + "%，谷时 "
                + config.aiOffPeakChance + "%，规则 "
                + config.aiPeakDays + " " + config.aiPeakPeriods;
    }

    private static Set<DayOfWeek> parseDays(String value) {
        Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
        if (value == null || value.trim().isEmpty()) {
            return days;
        }
        for (String part : value.split("[,，\\s]+")) {
            String token = part.trim().toUpperCase(Locale.ROOT);
            if (token.isEmpty()) {
                continue;
            }
            int dash = token.indexOf('-');
            if (dash > 0 && dash < token.length() - 1) {
                DayOfWeek start = day(token.substring(0, dash));
                DayOfWeek end = day(token.substring(dash + 1));
                DayOfWeek current = start;
                while (true) {
                    days.add(current);
                    if (current == end) {
                        break;
                    }
                    current = current.plus(1);
                }
            } else {
                days.add(day(token));
            }
        }
        return days;
    }

    private static List<TimeRange> parsePeriods(String value) {
        List<TimeRange> periods = new ArrayList<>();
        if (value == null || value.trim().isEmpty()) {
            return periods;
        }
        for (String part : value.split("[,，\\s]+")) {
            String token = part.trim();
            if (token.isEmpty()) {
                continue;
            }
            int dash = token.indexOf('-');
            if (dash <= 0 || dash >= token.length() - 1) {
                throw new IllegalArgumentException(
                        "ai.peak.periods 格式应为 HH:mm-HH:mm");
            }
            try {
                LocalTime start = LocalTime.parse(
                        token.substring(0, dash), TIME);
                LocalTime end = LocalTime.parse(
                        token.substring(dash + 1), TIME);
                if (!start.isBefore(end)) {
                    throw new IllegalArgumentException(
                            "ai.peak.periods 开始时间必须早于结束时间");
                }
                periods.add(new TimeRange(start, end));
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException(
                        "ai.peak.periods 时间格式无效");
            }
        }
        return periods;
    }

    private static DayOfWeek day(String value) {
        switch (value.trim().toUpperCase(Locale.ROOT)) {
            case "MON":
            case "MONDAY":
                return DayOfWeek.MONDAY;
            case "TUE":
            case "TUESDAY":
                return DayOfWeek.TUESDAY;
            case "WED":
            case "WEDNESDAY":
                return DayOfWeek.WEDNESDAY;
            case "THU":
            case "THURSDAY":
                return DayOfWeek.THURSDAY;
            case "FRI":
            case "FRIDAY":
                return DayOfWeek.FRIDAY;
            case "SAT":
            case "SATURDAY":
                return DayOfWeek.SATURDAY;
            case "SUN":
            case "SUNDAY":
                return DayOfWeek.SUNDAY;
            default:
                throw new IllegalArgumentException(
                        "ai.peak.days 包含无效日期：" + value);
        }
    }

    private static final class TimeRange {
        final LocalTime start;
        final LocalTime end;

        TimeRange(LocalTime start, LocalTime end) {
            this.start = start;
            this.end = end;
        }
    }
}
