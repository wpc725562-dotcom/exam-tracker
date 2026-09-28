package com.wpc725562.examtracker.dto;

import com.wpc725562.examtracker.domain.Priority;
import com.wpc725562.examtracker.domain.TaskStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 任务相关的 DTO。
 */
public final class TaskDtos {

    private TaskDtos() {
    }

    /** 一天 1440 分钟。任务计划时长超过这个数一定是填错了。 */
    private static final int MAX_MINUTES_PER_DAY = 24 * 60;

    @Schema(description = "新建任务请求")
    public record CreateRequest(

            @Schema(description = "所属科目 id", example = "1")
            @NotNull(message = "科目不能为空")
            Long subjectId,

            @Schema(description = "任务标题", example = "做一套 2024 年真题（选择+填空）")
            @NotBlank(message = "任务标题不能为空")
            @Size(max = 200, message = "任务标题最长 200 个字符")
            String title,

            @Schema(description = "计划完成日期", example = "2026-09-28")
            @NotNull(message = "计划日期不能为空")
            LocalDate planDate,

            @Schema(description = "计划投入分钟数", example = "90")
            @NotNull(message = "计划时长不能为空")
            @Min(value = 1, message = "计划时长至少 1 分钟")
            @Max(value = MAX_MINUTES_PER_DAY, message = "计划时长不能超过 1440 分钟")
            Integer planMinutes,

            @Schema(description = "优先级，默认 MEDIUM", example = "HIGH")
            Priority priority,

            @Schema(description = "备注", example = "错题要整理到错题本")
            @Size(max = 500, message = "备注最长 500 个字符")
            String note
    ) {
    }

    @Schema(description = "修改任务请求（全量覆盖，不含状态）")
    public record UpdateRequest(

            @Schema(description = "所属科目 id", example = "1")
            @NotNull(message = "科目不能为空")
            Long subjectId,

            @Schema(description = "任务标题", example = "做一套 2024 年真题（选择+填空）")
            @NotBlank(message = "任务标题不能为空")
            @Size(max = 200, message = "任务标题最长 200 个字符")
            String title,

            @Schema(description = "计划完成日期", example = "2026-09-28")
            @NotNull(message = "计划日期不能为空")
            LocalDate planDate,

            @Schema(description = "计划投入分钟数", example = "90")
            @NotNull(message = "计划时长不能为空")
            @Min(value = 1, message = "计划时长至少 1 分钟")
            @Max(value = MAX_MINUTES_PER_DAY, message = "计划时长不能超过 1440 分钟")
            Integer planMinutes,

            @Schema(description = "优先级", example = "HIGH")
            Priority priority,

            @Schema(description = "备注", example = "错题要整理到错题本")
            @Size(max = 500, message = "备注最长 500 个字符")
            String note
    ) {
    }

    /**
     * 单独的状态变更请求。
     *
     * <p>为什么不把状态放进 {@link UpdateRequest}：改标题和「标记完成」是两个不同的动作，
     * 频率也差很多（前者偶尔改，后者每天点）。分开之后，
     * 「标记完成」这个高频操作的请求体只有一个字段，也不会因为前端漏传
     * 标题就把任务改成空。
     */
    @Schema(description = "变更任务状态请求")
    public record StatusRequest(

            @Schema(description = "目标状态：TODO / DONE / SKIPPED", example = "DONE")
            @NotNull(message = "状态不能为空")
            TaskStatus status
    ) {
    }

    @Schema(description = "任务")
    public record TaskResponse(

            @Schema(description = "任务 id") Long id,
            @Schema(description = "所属科目 id") Long subjectId,
            @Schema(description = "所属科目名（直接给出来，省得前端再查一次）") String subjectName,
            @Schema(description = "任务标题") String title,
            @Schema(description = "计划完成日期") LocalDate planDate,
            @Schema(description = "计划分钟数") Integer planMinutes,
            @Schema(description = "优先级") Priority priority,
            @Schema(description = "状态") TaskStatus status,
            @Schema(description = "备注") String note,
            @Schema(description = "完成时间，未完成时为 null") LocalDateTime completedAt,
            @Schema(description = "创建时间") LocalDateTime createdAt,
            @Schema(description = "最后修改时间") LocalDateTime updatedAt
    ) {
    }
}
