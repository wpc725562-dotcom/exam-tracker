package com.wpc725562.examtracker.repository.projection;

/**
 * 「某科目有多少个任务」——任务按科目聚合的结果。
 *
 * <p>用一个通用的 {@code value} 字段而不是 {@code count} / {@code total}，
 * 是为了让同一个 record 同时服务于「全部任务数」和「已完成任务数」两个查询，
 * 调用方靠方法名区分语义。
 *
 * @param subjectId 科目 id
 * @param value     聚合值（本项目中是任务个数）
 */
public record SubjectCount(Long subjectId, Long value) {
}
