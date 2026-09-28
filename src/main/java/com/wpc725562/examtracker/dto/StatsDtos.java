package com.wpc725562.examtracker.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.util.List;

/**
 * 统计相关的 DTO —— 对应前端的「仪表盘」和「四科看板」。
 */
public final class StatsDtos {

    private StatsDtos() {
    }

    /**
     * 仪表盘总览。
     *
     * <p>分成「今天」「连续」「累计」「考试」四组，和前端页面上四个卡片一一对应。
     * 把它们合成一个接口而不是四个：仪表盘一次打开就要全部数据，
     * 拆成四个接口只会带来四次往返和「四个卡片先后跳出来」的视觉抖动。
     */
    @Schema(description = "仪表盘总览")
    public record OverviewResponse(

            @Schema(description = "统计基准日期（今天）") LocalDate date,

            // ---- 今日 ----
            @Schema(description = "今日任务总数") long todayTotalTasks,
            @Schema(description = "今日已完成任务数") long todayDoneTasks,
            @Schema(description = "今日待完成任务数") long todayPendingTasks,
            @Schema(description = "今日已跳过任务数") long todaySkippedTasks,
            @Schema(description = "今日完成率（0~1）", example = "0.5") double todayCompletionRate,
            @Schema(description = "今日计划投入分钟数") long todayPlannedMinutes,
            @Schema(description = "今日实际投入分钟数（来自打卡）") long todayActualMinutes,

            // ---- 连续打卡 ----
            @Schema(description = "当前连续打卡天数", example = "7") long currentStreakDays,
            @Schema(description = "历史最长连续打卡天数", example = "21") long longestStreakDays,
            @Schema(description = "累计打卡天数（有打卡记录的自然日个数）", example = "48") long totalCheckinDays,
            @Schema(description = "最近一次打卡日期，从未打卡时为 null") LocalDate lastCheckinDate,

            // ---- 累计 ----
            @Schema(description = "累计任务总数") long totalTasks,
            @Schema(description = "累计已完成任务数") long totalDoneTasks,
            @Schema(description = "累计实际投入分钟数") long totalActualMinutes,

            // ---- 考试倒计时 ----
            @Schema(description = "考试日期，未设置为 null") LocalDate examDate,
            @Schema(description = "距考试天数，未设置为 null") Long daysUntilExam
    ) {
    }

    /**
     * 四科看板。
     *
     * @param periodDays 统计窗口天数 —— 回显出来是为了让前端能显示「最近 7 天」这种标题，
     *                   而不是自己硬编码一个数字（两边不一致时非常难查）
     * @param subjects   各科目统计，按 {@code sortOrder} 排列
     */
    @Schema(description = "四科看板")
    public record SubjectBoardResponse(

            @Schema(description = "统计窗口天数", example = "7") int periodDays,
            @Schema(description = "窗口起始日期") LocalDate from,
            @Schema(description = "窗口结束日期（含）") LocalDate to,
            @Schema(description = "窗口内实际总投入分钟数") long periodTotalMinutes,
            @Schema(description = "窗口内应投入总分钟数") long periodTargetMinutes,
            @Schema(description = "窗口整体达成率（0~1）", example = "0.86") double overallAchievementRate,
            @Schema(description = "各科目明细") List<SubjectDtos.SubjectProgressResponse> subjects
    ) {
    }
}
