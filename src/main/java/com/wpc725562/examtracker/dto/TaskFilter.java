package com.wpc725562.examtracker.dto;

import com.wpc725562.examtracker.domain.Priority;
import com.wpc725562.examtracker.domain.TaskStatus;

import java.time.LocalDate;

/**
 * 任务列表的筛选条件。
 *
 * <p>单独抽成一个 record 而不是让 Service 方法收 6 个参数：
 * 全是 {@code Long} / {@code LocalDate} 的长参数列表极容易传错位置，
 * 而且编译器不会提醒（类型相同）。包成 record 之后，调用处必须写字段名。
 *
 * <p>所有字段都可为 null，表示「这个维度不过滤」。
 *
 * @param from      计划日期起（含）
 * @param to        计划日期止（含）
 * @param subjectId 科目
 * @param status    状态
 * @param priority  优先级
 * @param keyword   标题关键词（模糊匹配）
 */
public record TaskFilter(
        LocalDate from,
        LocalDate to,
        Long subjectId,
        TaskStatus status,
        Priority priority,
        String keyword
) {

    public static TaskFilter none() {
        return new TaskFilter(null, null, null, null, null, null);
    }
}
