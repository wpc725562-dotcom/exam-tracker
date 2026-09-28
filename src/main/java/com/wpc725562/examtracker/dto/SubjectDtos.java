package com.wpc725562.examtracker.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * 科目相关的 DTO。
 */
public final class SubjectDtos {

    private SubjectDtos() {
    }

    /** 一周的总分钟数上限（7 × 24 × 60）。用来挡住「目标 100000 分钟」这种明显填错的值。 */
    private static final int MAX_MINUTES_PER_WEEK = 7 * 24 * 60;

    @Schema(description = "新建科目请求")
    public record CreateRequest(

            @Schema(description = "科目名，同一用户下不可重名", example = "数学")
            @NotBlank(message = "科目名不能为空")
            @Size(max = 50, message = "科目名最长 50 个字符")
            String name,

            @Schema(description = "十六进制颜色，用于前端区分科目", example = "#4F46E5")
            @Pattern(regexp = "^#([0-9A-Fa-f]{3}|[0-9A-Fa-f]{6})$",
                    message = "颜色需为 #RGB 或 #RRGGBB 形式")
            String color,

            @Schema(description = "每周计划投入分钟数", example = "420")
            @NotNull(message = "每周目标分钟数不能为空")
            @Min(value = 0, message = "每周目标分钟数不能为负")
            @Max(value = MAX_MINUTES_PER_WEEK, message = "每周目标分钟数不能超过 " + MAX_MINUTES_PER_WEEK)
            Integer targetMinutesPerWeek,

            @Schema(description = "展示顺序，不填则排到最后", example = "1")
            @Min(value = 0, message = "排序值不能为负")
            Integer sortOrder
    ) {
    }

    @Schema(description = "修改科目请求（全量覆盖）")
    public record UpdateRequest(

            @Schema(description = "科目名", example = "数学")
            @NotBlank(message = "科目名不能为空")
            @Size(max = 50, message = "科目名最长 50 个字符")
            String name,

            @Schema(description = "十六进制颜色", example = "#4F46E5")
            @Pattern(regexp = "^#([0-9A-Fa-f]{3}|[0-9A-Fa-f]{6})$",
                    message = "颜色需为 #RGB 或 #RRGGBB 形式")
            String color,

            @Schema(description = "每周计划投入分钟数", example = "420")
            @NotNull(message = "每周目标分钟数不能为空")
            @Min(value = 0, message = "每周目标分钟数不能为负")
            @Max(value = MAX_MINUTES_PER_WEEK, message = "每周目标分钟数不能超过 " + MAX_MINUTES_PER_WEEK)
            Integer targetMinutesPerWeek,

            @Schema(description = "展示顺序", example = "1")
            @Min(value = 0, message = "排序值不能为负")
            Integer sortOrder
    ) {
    }

    @Schema(description = "科目")
    public record SubjectResponse(

            @Schema(description = "科目 id") Long id,
            @Schema(description = "科目名") String name,
            @Schema(description = "颜色") String color,
            @Schema(description = "每周目标分钟数") Integer targetMinutesPerWeek,
            @Schema(description = "展示顺序") Integer sortOrder
    ) {
    }

    /**
     * 「四科看板」用的一行。
     *
     * <p>{@code periodMinutes} / {@code targetMinutesInPeriod} / {@code achievementRate}
     * 都是**按传入的时间窗口**算的，不是全量 —— 看板关心的是「最近这段时间做得怎么样」，
     * 全量数据会被早期的热情拉高，掩盖最近在偷懒这件事。
     */
    @Schema(description = "科目进度统计")
    public record SubjectProgressResponse(

            @Schema(description = "科目 id") Long id,
            @Schema(description = "科目名") String name,
            @Schema(description = "颜色") String color,
            @Schema(description = "每周目标分钟数") Integer targetMinutesPerWeek,

            @Schema(description = "任务总数（全量）") long taskTotal,
            @Schema(description = "已完成任务数（全量）") long taskDone,
            @Schema(description = "未完成任务数（全量）") long taskPending,
            @Schema(description = "完成率（0~1，全量）", example = "0.75") double completionRate,

            @Schema(description = "窗口内实际投入分钟数") long periodMinutes,
            @Schema(description = "窗口内应投入分钟数（按周目标折算）") long targetMinutesInPeriod,
            @Schema(description = "窗口达成率（0~1，可能超过 1）", example = "0.93") double achievementRate,

            @Schema(description = "最近一次打卡日期，从未打卡时为 null") LocalDate lastCheckinDate
    ) {
    }
}
