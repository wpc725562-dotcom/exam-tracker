package com.wpc725562.examtracker.repository.projection;

/**
 * 「某科目总共投入了多少分钟」——打卡记录按科目聚合的结果。
 *
 * @param subjectId 科目 id
 * @param minutes   实际投入分钟数合计
 */
public record SubjectMinutes(Long subjectId, Long minutes) {
}
