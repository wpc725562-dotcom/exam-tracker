package com.wpc725562.examtracker.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 连续打卡天数计算的边界测试。
 *
 * <p>这个类之所以值得单独测，是因为它**最容易写错、又最难被肉眼发现**：
 * 算错一天，用户看到的就是一个错误的数字，而且没有任何报错。
 * 抽成纯函数之后，跨月、跨年、闰年、「今天还没打卡」这些情况全都能钉住。
 */
@DisplayName("StreakCalculator —— 连续打卡天数")
class StreakCalculatorTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 28);

    /**
     * 构造「截至 {@code end} 连续 n 天」的日期列表。
     *
     * <p>返回的是**降序**列表（end, end-1, end-2 ...），与仓储层
     * {@code order by checkinDate desc} 的输出一致 —— 输入约定必须对齐，
     * 否则测的就不是真实场景。
     */
    private static List<LocalDate> daysEndingAt(LocalDate end, int n) {
        List<LocalDate> dates = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            dates.add(end.minusDays(i));
        }
        return dates;
    }

    @Nested
    @DisplayName("当前连续天数")
    class CurrentStreak {

        @Test
        @DisplayName("没有打卡记录 -> 0")
        void empty() {
            assertThat(StreakCalculator.currentStreak(List.of(), TODAY)).isZero();
        }

        @Test
        @DisplayName("列表为 null -> 0（不抛 NPE）")
        void nullList() {
            assertThat(StreakCalculator.currentStreak(null, TODAY)).isZero();
        }

        @Test
        @DisplayName("只有今天 -> 1")
        void todayOnly() {
            assertThat(StreakCalculator.currentStreak(List.of(TODAY), TODAY)).isEqualTo(1);
        }

        @Test
        @DisplayName("今天 + 昨天 -> 2")
        void todayAndYesterday() {
            assertThat(StreakCalculator.currentStreak(daysEndingAt(TODAY, 2), TODAY)).isEqualTo(2);
        }

        @Test
        @DisplayName("连续 7 天 -> 7")
        void sevenConsecutive() {
            assertThat(StreakCalculator.currentStreak(daysEndingAt(TODAY, 7), TODAY)).isEqualTo(7);
        }

        @Test
        @DisplayName("★ 今天还没打卡、但昨天打了 -> 连续天数继续算（不归零）")
        void latestIsYesterdayStillCounts() {
            // 这是本功能最关键的语义：每天零点一过就归零，用户会以为记录丢了。
            List<LocalDate> dates = daysEndingAt(TODAY.minusDays(1), 3);
            assertThat(StreakCalculator.currentStreak(dates, TODAY)).isEqualTo(3);
        }

        @Test
        @DisplayName("★ 最近一次打卡是前天 -> 断了，返回 0")
        void latestIsTwoDaysAgoIsBroken() {
            List<LocalDate> dates = daysEndingAt(TODAY.minusDays(2), 5);
            assertThat(StreakCalculator.currentStreak(dates, TODAY)).isZero();
        }

        @Test
        @DisplayName("中间缺一天 -> 只数到缺口为止")
        void gapStopsCounting() {
            // 今天、昨天、[缺 9-26]、9-25、9-24 ...
            List<LocalDate> dates = List.of(
                    TODAY, TODAY.minusDays(1), TODAY.minusDays(3), TODAY.minusDays(4));
            assertThat(StreakCalculator.currentStreak(dates, TODAY)).isEqualTo(2);
        }

        @Test
        @DisplayName("跨月边界（8-31 → 9-1）能连上")
        void acrossMonthBoundary() {
            LocalDate sep1 = LocalDate.of(2026, 9, 1);
            List<LocalDate> dates = daysEndingAt(sep1, 3); // 9-1, 8-31, 8-30
            assertThat(dates).contains(LocalDate.of(2026, 8, 31));
            assertThat(StreakCalculator.currentStreak(dates, sep1)).isEqualTo(3);
        }

        @Test
        @DisplayName("跨年边界（12-31 → 1-1）能连上")
        void acrossYearBoundary() {
            LocalDate jan1 = LocalDate.of(2026, 1, 1);
            List<LocalDate> dates = daysEndingAt(jan1, 3); // 1-1, 2025-12-31, 2025-12-30
            assertThat(dates).contains(LocalDate.of(2025, 12, 31));
            assertThat(StreakCalculator.currentStreak(dates, jan1)).isEqualTo(3);
        }

        @Test
        @DisplayName("闰年 2-29 能连上（2028 是闰年）")
        void leapDay() {
            LocalDate mar1 = LocalDate.of(2028, 3, 1);
            List<LocalDate> dates = daysEndingAt(mar1, 3); // 3-1, 2-29, 2-28
            assertThat(dates).contains(LocalDate.of(2028, 2, 29));
            assertThat(StreakCalculator.currentStreak(dates, mar1)).isEqualTo(3);
        }

        @Test
        @DisplayName("数据里出现未来日期 -> 视为异常，返回 0（不虚报）")
        void futureDateIsNotCounted() {
            List<LocalDate> dates = List.of(TODAY.plusDays(1), TODAY, TODAY.minusDays(1));
            assertThat(StreakCalculator.currentStreak(dates, TODAY)).isZero();
        }
    }

    @Nested
    @DisplayName("历史最长连续天数")
    class LongestStreak {

        @Test
        @DisplayName("没有记录 -> 0")
        void empty() {
            assertThat(StreakCalculator.longestStreak(List.of())).isZero();
            assertThat(StreakCalculator.longestStreak(null)).isZero();
        }

        @Test
        @DisplayName("只有一天 -> 1")
        void single() {
            assertThat(StreakCalculator.longestStreak(List.of(TODAY))).isEqualTo(1);
        }

        @Test
        @DisplayName("★ 历史最长的一段比当前这段长 -> 取历史")
        void historicalRunWins() {
            // 3 月份连着 5 天，然后断了；现在只有 2 天。历史纪录应该是 5。
            List<LocalDate> dates = new ArrayList<>();
            dates.addAll(daysEndingAt(TODAY, 2));
            LocalDate march = LocalDate.of(2026, 3, 10);
            dates.addAll(daysEndingAt(march, 5));
            assertThat(StreakCalculator.longestStreak(dates)).isEqualTo(5);
        }

        @Test
        @DisplayName("当前这段更长 -> 取当前")
        void currentRunWins() {
            List<LocalDate> dates = new ArrayList<>();
            dates.addAll(daysEndingAt(TODAY, 4));
            dates.addAll(daysEndingAt(LocalDate.of(2026, 3, 10), 2));
            assertThat(StreakCalculator.longestStreak(dates)).isEqualTo(4);
        }

        @Test
        @DisplayName("全部连续 -> 等于总天数")
        void allConsecutive() {
            assertThat(StreakCalculator.longestStreak(daysEndingAt(TODAY, 30))).isEqualTo(30);
        }

        @Test
        @DisplayName("完全不相邻 -> 1")
        void allIsolated() {
            List<LocalDate> dates = List.of(
                    TODAY, TODAY.minusDays(5), TODAY.minusDays(20));
            assertThat(StreakCalculator.longestStreak(dates)).isEqualTo(1);
        }

        @Test
        @DisplayName("最长一段不包含今天（且早于昨天）时，当前连续是 0 但历史最长不为 0")
        void longestIndependentOfCurrent() {
            List<LocalDate> dates = daysEndingAt(LocalDate.of(2026, 3, 10), 6);
            assertThat(StreakCalculator.currentStreak(dates, TODAY)).isZero();
            assertThat(StreakCalculator.longestStreak(dates)).isEqualTo(6);
        }
    }
}
