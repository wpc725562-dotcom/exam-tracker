package com.wpc725562.examtracker.controller;

import com.wpc725562.examtracker.common.ApiResponse;
import com.wpc725562.examtracker.dto.StatsDtos;
import com.wpc725562.examtracker.security.UserPrincipal;
import com.wpc725562.examtracker.service.StatsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/stats")
@Tag(name = "05. 统计", description = "仪表盘与四科看板")
@SecurityRequirement(name = "bearerAuth")
@Validated
public class StatsController {

    private final StatsService statsService;

    public StatsController(StatsService statsService) {
        this.statsService = statsService;
    }

    @GetMapping("/overview")
    @Operation(summary = "仪表盘总览",
            description = """
                    一次返回四个卡片要的全部数据：

                    - **今日**：任务数 / 完成数 / 待办 / 已跳过 / 完成率 / 计划时长 / 实际时长
                    - **连续打卡**：当前连续天数、历史最长、累计打卡天数、最近打卡日期
                    - **累计**：任务总数、完成数、实际投入总时长
                    - **考试倒计时**：考试日期与剩余天数（未设置时为 null）

                    「今天还没打卡」**不算断签** —— 连续天数的判定从昨天起算，
                    否则每天零点一过所有人的连续天数都会归零。
                    """)
    public ApiResponse<StatsDtos.OverviewResponse> overview(@AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.ok(statsService.overview(principal.getUserId()));
    }

    @GetMapping("/subjects")
    @Operation(summary = "四科看板",
            description = """
                    每科一行，包含：

                    - 任务总数 / 已完成 / 未完成 / 完成率（**全量**）
                    - 窗口内实际投入时长、按周目标折算的应投入时长、达成率
                    - 最近一次打卡日期

                    `days` 不传默认 7 天。窗口相关字段都按这个窗口算 ——
                    看板关心的是「最近做得怎么样」，全量数据会被早期的热情拉高，
                    掩盖最近在偷懒这件事。
                    """)
    public ApiResponse<StatsDtos.SubjectBoardResponse> subjects(
            @AuthenticationPrincipal UserPrincipal principal,

            @Parameter(description = "统计窗口天数，默认 7，最大 365", example = "7")
            @RequestParam(required = false)
            @Min(value = 1, message = "统计天数至少为 1")
            @Max(value = 365, message = "统计天数最多 365") Integer days) {

        return ApiResponse.ok(statsService.subjectBoard(principal.getUserId(), days));
    }
}
