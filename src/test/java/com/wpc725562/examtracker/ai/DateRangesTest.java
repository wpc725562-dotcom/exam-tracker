package com.wpc725562.examtracker.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 相对时间 → 具体日期的换算。
 *
 * <p>这段逻辑存在的意义就是「不让模型算日期」，所以它自己必须算对 ——
 * 一旦算错，表现是「数字看着不太对」，而不是报错，极难发现。
 * 因此这里用<b>固定的 today</b> 把每种取值逐个钉死。
 *
 * <p>选 2026-09-29（周二）作为基准是有意的：它既不是月初也不是周一，
 * 能同时暴露「本周从周一算」和「本月从 1 号算」这两条规则写错的情况。
 */
@DisplayName("DateRanges —— 相对时间换算")
class DateRangesTest {

    /** 2026-09-29 是星期二。 */
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 29);

    @Nested
    @DisplayName("相对范围")
    class RelativeRanges {

        @Test
        @DisplayName("today = 今天到今天")
        void today() {
            DateRanges.Span span = DateRanges.resolve("today", TODAY);
            assertThat(span.from()).isEqualTo(TODAY);
            assertThat(span.to()).isEqualTo(TODAY);
            assertThat(span.days()).isEqualTo(1);
        }

        @Test
        @DisplayName("★ this_week 从**周一**起算，不是周日")
        void thisWeekStartsOnMonday() {
            DateRanges.Span span = DateRanges.resolve("this_week", TODAY);
            assertThat(span.from())
                    .as("2026-09-29 是周二，本周一应是 09-28")
                    .isEqualTo(LocalDate.of(2026, 9, 28));
            assertThat(span.to()).isEqualTo(TODAY);
            assertThat(span.days()).isEqualTo(2);
        }

        @Test
        @DisplayName("★ 周一当天问「本周」，窗口是 1 天而不是 0 天或 7 天")
        void thisWeekOnMondayItself() {
            LocalDate monday = LocalDate.of(2026, 9, 28);
            DateRanges.Span span = DateRanges.resolve("this_week", monday);
            assertThat(span.from()).isEqualTo(monday);
            assertThat(span.days()).isEqualTo(1);
        }

        @Test
        @DisplayName("last_7_days 是**含今天在内**的 7 天")
        void last7Days() {
            DateRanges.Span span = DateRanges.resolve("last_7_days", TODAY);
            assertThat(span.from()).isEqualTo(LocalDate.of(2026, 9, 23));
            assertThat(span.days())
                    .as("含头含尾才是 7 天；算成 8 天是常见写法错误")
                    .isEqualTo(7);
        }

        @Test
        @DisplayName("last_30_days")
        void last30Days() {
            DateRanges.Span span = DateRanges.resolve("last_30_days", TODAY);
            assertThat(span.from()).isEqualTo(LocalDate.of(2026, 8, 31));
            assertThat(span.days()).isEqualTo(30);
        }

        @Test
        @DisplayName("this_month 从当月 1 号起算")
        void thisMonth() {
            DateRanges.Span span = DateRanges.resolve("this_month", TODAY);
            assertThat(span.from()).isEqualTo(LocalDate.of(2026, 9, 1));
            assertThat(span.days()).isEqualTo(29);
        }

        @Test
        @DisplayName("不传 / 空串 / 空白都取默认值 last_7_days")
        void defaultsToLast7Days() {
            for (String raw : new String[]{null, "", "   "}) {
                DateRanges.Span span = DateRanges.resolve(raw, TODAY);
                assertThat(span.from())
                        .as("输入 %s 时应取默认值", raw == null ? "null" : "「" + raw + "」")
                        .isEqualTo(LocalDate.of(2026, 9, 23));
            }
        }

        @Test
        @DisplayName("大小写不敏感")
        void caseInsensitive() {
            assertThat(DateRanges.resolve("THIS_WEEK", TODAY).from())
                    .isEqualTo(DateRanges.resolve("this_week", TODAY).from());
        }

        @Test
        @DisplayName("★ 非法取值抛异常，且消息里列出全部合法取值")
        void illegalRangeListsValidValues() {
            assertThatThrownBy(() -> DateRanges.resolve("yesterday", TODAY))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("yesterday")
                    .hasMessageContaining("this_week")
                    .hasMessageContaining("last_7_days")
                    .as("消息里必须带候选值 —— 模型靠它自我纠正")
                    .hasMessageContaining("今天");
        }
    }

    @Nested
    @DisplayName("显式日期优先于相对范围")
    class ExplicitDates {

        @Test
        @DisplayName("from/to 都给时按它们来，忽略 range")
        void explicitWins() {
            DateRanges.Span span = DateRanges.resolve(
                    "today", "2026-09-01", "2026-09-10", TODAY);
            assertThat(span.from()).isEqualTo(LocalDate.of(2026, 9, 1));
            assertThat(span.to()).isEqualTo(LocalDate.of(2026, 9, 10));
            assertThat(span.days()).isEqualTo(10);
        }

        @Test
        @DisplayName("只给 from 时，结束日取今天")
        void onlyFrom() {
            DateRanges.Span span = DateRanges.resolve(null, "2026-09-25", null, TODAY);
            assertThat(span.from()).isEqualTo(LocalDate.of(2026, 9, 25));
            assertThat(span.to()).isEqualTo(TODAY);
        }

        @Test
        @DisplayName("只给 to 时，起始日往前回溯 7 天（与默认行为一致）")
        void onlyTo() {
            DateRanges.Span span = DateRanges.resolve(null, null, "2026-09-29", TODAY);
            assertThat(span.from()).isEqualTo(LocalDate.of(2026, 9, 23));
            assertThat(span.days()).isEqualTo(7);
        }

        @Test
        @DisplayName("★ 起始日晚于结束日 → 报错，而不是悄悄返回一个空区间")
        void reversedRangeRejected() {
            assertThatThrownBy(() -> DateRanges.resolve(null, "2026-09-10", "2026-09-01", TODAY))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("2026-09-10")
                    .hasMessageContaining("2026-09-01");
        }

        @Test
        @DisplayName("日期格式不对 → 报错并说明期望格式")
        void badDateFormatRejected() {
            assertThatThrownBy(() -> DateRanges.resolve(null, "9月1日", null, TODAY))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("YYYY-MM-DD");

            assertThatThrownBy(() -> DateRanges.resolve(null, null, "2026/09/01", TODAY))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("YYYY-MM-DD");
        }

        @Test
        @DisplayName("不存在的日期（2026-02-30）也要报错，不能当成 3 月 2 日")
        void impossibleDateRejected() {
            assertThatThrownBy(() -> DateRanges.resolve(null, "2026-02-30", null, TODAY))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
