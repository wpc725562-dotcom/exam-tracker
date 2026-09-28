package com.wpc725562.examtracker.ai;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 把「相对时间说法」换算成具体的日期区间。
 *
 * <p><b>为什么不让模型直接给日期：</b>LLM 算日期是不可靠的 —— 它没有日历，
 * 「上周三」在它那里是一次推理而不是一次查表，算错的时候看起来还特别自信。
 * 所以工具只接受 {@code this_week} / {@code last_7_days} 这种**相对语义**，
 * 由这里换算成 {@link LocalDate}。换算逻辑是纯函数、可确定性单测，
 * 出错会当场被测试抓住，而不是变成一个「数字看着不太对」的答案。
 *
 * <p>「一周」按<b>周一</b>起算（中文语境习惯），不是周日。
 */
final class DateRanges {

    static final String TODAY = "today";
    static final String THIS_WEEK = "this_week";
    static final String LAST_7_DAYS = "last_7_days";
    static final String LAST_30_DAYS = "last_30_days";
    static final String THIS_MONTH = "this_month";

    /** 合法取值，出错时原样回给模型 —— 让它自己纠正，而不是猜一个别的值。 */
    static final List<String> ALL = List.of(TODAY, THIS_WEEK, LAST_7_DAYS, LAST_30_DAYS, THIS_MONTH);

    private static final String DEFAULT_RANGE = LAST_7_DAYS;

    private DateRanges() {
    }

    /**
     * 一段闭区间日期。
     *
     * @param from 起始日（含）
     * @param to   结束日（含）
     */
    record Span(LocalDate from, LocalDate to) {

        /** 区间天数，闭区间所以 +1。 */
        int days() {
            return (int) ChronoUnit.DAYS.between(from, to) + 1;
        }
    }

    /**
     * 解析相对时间范围。
     *
     * @param raw   模型给的值，可为 null（用默认值）或 null/空字符串
     * @param today 今天。**由调用方传入而不是在这里 {@code LocalDate.now()}** ——
     *              否则这个方法就没法确定性地单测了
     * @throws IllegalArgumentException 取值不在 {@link #ALL} 里时抛出，
     *                                  消息里带上全部合法取值
     */
    static Span resolve(String raw, LocalDate today) {
        String key = (raw == null || raw.isBlank()) ? DEFAULT_RANGE : raw.trim().toLowerCase();

        return switch (key) {
            case TODAY -> new Span(today, today);
            case THIS_WEEK -> new Span(today.with(DayOfWeek.MONDAY), today);
            case LAST_7_DAYS -> new Span(today.minusDays(6), today);
            case LAST_30_DAYS -> new Span(today.minusDays(29), today);
            case THIS_MONTH -> new Span(today.withDayOfMonth(1), today);
            default -> throw new IllegalArgumentException(
                    "时间范围「" + raw + "」不合法，只能是：" + String.join(" / ", ALL)
                            + "（分别表示 今天 / 本周 / 最近7天 / 最近30天 / 本月）");
        };
    }

    /**
     * 显式日期优先，其次相对范围。
     *
     * <p>两种用法二选一：模型要么给 {@code range}，要么给 {@code from}/{@code to}。
     * 都给了就以显式日期为准（用户问「9 月 1 日到 9 月 10 日」时只有后者能表达）。
     *
     * @throws IllegalArgumentException 日期格式不对，或起始日晚于结束日
     */
    static Span resolve(String range, String from, String to, LocalDate today) {
        if (from == null && to == null) {
            return resolve(range, today);
        }

        LocalDate start = from == null ? null : parseDate(from, "from");
        LocalDate end = to == null ? null : parseDate(to, "to");

        if (start == null) {
            // 只给了结束日：往前回溯 7 天，和「不传时间范围」的默认行为一致
            start = end.minusDays(6);
        }
        if (end == null) {
            end = today;
        }
        if (start.isAfter(end)) {
            throw new IllegalArgumentException(
                    "起始日期 " + start + " 晚于结束日期 " + end + "，请检查这两个日期");
        }
        return new Span(start, end);
    }

    private static LocalDate parseDate(String raw, String field) {
        try {
            return LocalDate.parse(raw.trim());
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(
                    "参数 " + field + " 的日期「" + raw + "」格式不对，需要 YYYY-MM-DD 形式，例如 2026-09-01");
        }
    }
}
