package com.wpc725562.examtracker.repository.projection;

import java.time.LocalDate;

/**
 * 「某一天总共投入了多少分钟」——打卡记录按日期聚合的结果。
 *
 * <p>用 record 而不是 {@code Object[]}：{@code Object[]} 的下标含义只存在于
 * 写查询那一刻的脑子里，编译期没有任何保护；换成 record 之后字段名就是文档。
 */
public record DateMinutes(LocalDate date, Long minutes) {
}
