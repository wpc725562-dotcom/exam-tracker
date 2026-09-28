package com.wpc725562.examtracker.service;

import java.time.LocalDate;
import java.util.List;

/**
 * 连续打卡天数的计算。
 *
 * <p>抽成纯函数（不依赖任何 Spring 组件、不碰数据库）有两个好处：
 * <ol>
 *   <li>可以直接写单元测试，把「跨月」「今天还没打卡」「中间断了一天」这些
 *       边界情况全钉住 —— 这类逻辑一旦写错，用户看到的就是一个错误的数字，
 *       而且很难凭肉眼发现；</li>
 *   <li>不引入 mock，测试跑得飞快。</li>
 * </ol>
 *
 * <p><b>输入约定：</b>{@code datesDesc} 是**去重且降序**的打卡日期列表
 * （由 SQL 的 {@code distinct + order by desc} 保证）。这里不再排一次 ——
 * 排序规则有两处实现时，迟早会不一致。
 */
public final class StreakCalculator {

    private StreakCalculator() {
    }

    /**
     * 当前连续打卡天数。
     *
     * <p>关键规则：**「今天还没打卡」不算断**。如果最近一次打卡是昨天，
     * 连续天数继续算 —— 否则每天零点一过，所有用户的连续天数都会瞬间归零，
     * 那显然不是用户想要的语义。只有当最近一次打卡早于昨天时，才算真的断了。
     *
     * @param datesDesc 去重、降序的打卡日期
     * @param today     今天
     * @return 当前连续天数，没有记录或已断则为 0
     */
    public static long currentStreak(List<LocalDate> datesDesc, LocalDate today) {
        if (datesDesc == null || datesDesc.isEmpty()) {
            return 0;
        }

        LocalDate latest = datesDesc.get(0);
        boolean stillAlive = latest.equals(today) || latest.equals(today.minusDays(1));
        if (!stillAlive) {
            return 0;
        }

        long streak = 0;
        LocalDate expected = latest;
        for (LocalDate date : datesDesc) {
            if (date.equals(expected)) {
                streak++;
                expected = expected.minusDays(1);
            } else {
                // 列表是降序的，一旦遇到不等于期望值的日期，说明中间缺了至少一天，
                // 后面的更早，不可能再连上 —— 直接结束。
                break;
            }
        }
        return streak;
    }

    /**
     * 历史最长连续打卡天数。
     *
     * <p>必须遍历完整列表 —— 不能只看当前这一段。用户可能三月份连着打过 30 天，
     * 然后断了，现在只有 3 天，历史纪录仍然是 30。
     */
    public static long longestStreak(List<LocalDate> datesDesc) {
        if (datesDesc == null || datesDesc.isEmpty()) {
            return 0;
        }

        long best = 1;
        long run = 1;

        for (int i = 1; i < datesDesc.size(); i++) {
            LocalDate previous = datesDesc.get(i - 1);
            LocalDate current = datesDesc.get(i);

            // 降序列表里，「上一条比当前晚一天」= 这两天是连着的
            if (previous.minusDays(1).equals(current)) {
                run++;
            } else {
                run = 1;
            }
            best = Math.max(best, run);
        }
        return best;
    }
}
