package com.wpc725562.examtracker.repository.projection;

import java.time.LocalDate;

/**
 * 「某科目最近一次打卡是哪天」。
 *
 * @param subjectId       科目 id
 * @param lastCheckinDate 该科目最后一次打卡的日期；从没打过卡则不会出现在结果里
 */
public record SubjectLastCheckin(Long subjectId, LocalDate lastCheckinDate) {
}
