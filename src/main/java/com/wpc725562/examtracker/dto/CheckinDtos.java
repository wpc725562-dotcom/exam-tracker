package com.wpc725562.examtracker.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 打卡相关的 DTO。
 */
public final class CheckinDtos {

    private CheckinDtos() {
    }

    private static final int MAX_MINUTES_PER_DAY = 24 * 60;

    @Schema(description = "新建打卡记录请求")
    public record CreateRequest(

            /**
             * 与 {@code taskId} **二选一**：
             * 传了 {@code taskId} 时本字段会被忽略，科目从任务上推导出来 ——
             * 这样「打卡的科目和任务的科目不一致」这种非法状态**在结构上就无法表达**，
             * 不需要靠事后校验去堵。
             */
            @Schema(description = "科目 id。传了 taskId 时可省略（会从任务推导）", example = "1")
            Long subjectId,

            @Schema(description = "关联的任务 id，可不填（表示不挂在具体任务上的学习时间）", example = "12")
            Long taskId,

            @Schema(description = "打卡日期，不填则默认今天", example = "2026-09-28")
            LocalDate checkinDate,

            @Schema(description = "实际投入分钟数", example = "75")
            @NotNull(message = "实际时长不能为空")
            @Min(value = 1, message = "实际时长至少 1 分钟")
            @Max(value = MAX_MINUTES_PER_DAY, message = "单次打卡不能超过 1440 分钟")
            Integer actualMinutes,

            @Schema(description = "备注", example = "阅读速度比预想慢，下次留 100 分钟")
            @Size(max = 500, message = "备注最长 500 个字符")
            String note
    ) {
    }

    @Schema(description = "打卡记录")
    public record CheckinResponse(

            @Schema(description = "打卡 id") Long id,
            @Schema(description = "科目 id") Long subjectId,
            @Schema(description = "科目名") String subjectName,
            @Schema(description = "关联任务 id，可为 null") Long taskId,
            @Schema(description = "关联任务标题，可为 null") String taskTitle,
            @Schema(description = "打卡日期") LocalDate checkinDate,
            @Schema(description = "实际投入分钟数") Integer actualMinutes,
            @Schema(description = "备注") String note,
            @Schema(description = "记录创建时间") LocalDateTime createdAt
    ) {
    }

    @Schema(description = "某一天的总投入（日历打卡视图用）")
    public record DailyMinutes(

            @Schema(description = "日期") LocalDate date,
            @Schema(description = "当天总投入分钟数") long minutes
    ) {
    }
}
