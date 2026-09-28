package com.wpc725562.examtracker.controller;

import com.wpc725562.examtracker.common.ApiResponse;
import com.wpc725562.examtracker.common.PageResult;
import com.wpc725562.examtracker.dto.CheckinDtos;
import com.wpc725562.examtracker.security.UserPrincipal;
import com.wpc725562.examtracker.service.CheckinService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/checkins")
@Tag(name = "04. 打卡", description = "学习时长记录")
@SecurityRequirement(name = "bearerAuth")
@Validated
public class CheckinController {

    private final CheckinService checkinService;

    public CheckinController(CheckinService checkinService) {
        this.checkinService = checkinService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "新增打卡",
            description = """
                    两种用法：

                    1. **挂在任务上** —— 只传 `taskId`，科目自动从任务推导（`subjectId` 会被忽略）。
                       这样不会出现「任务属于数学、打卡却记到英语」这种自相矛盾的数据。
                    2. **不挂任务** —— 传 `subjectId`，用于记录随手翻笔记、听听力这类学习时间。

                    `checkinDate` 不传默认今天；不允许填未来日期。
                    """)
    public ApiResponse<CheckinDtos.CheckinResponse> create(@AuthenticationPrincipal UserPrincipal principal,
                                                           @Valid @RequestBody CheckinDtos.CreateRequest request) {
        return ApiResponse.ok(checkinService.create(principal.getUserId(), request));
    }

    @GetMapping
    @Operation(summary = "打卡记录列表",
            description = "不传 from / to 时默认看最近 30 天，避免一次拉出全部历史。")
    public ApiResponse<PageResult<CheckinDtos.CheckinResponse>> list(
            @AuthenticationPrincipal UserPrincipal principal,

            @Parameter(description = "起始日期（含）") @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,

            @Parameter(description = "结束日期（含）") @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,

            @Parameter(description = "科目 id") @RequestParam(required = false) Long subjectId,

            @Parameter(description = "页码，从 1 开始") @RequestParam(defaultValue = "1")
            @Min(value = 1, message = "页码从 1 开始") int page,

            @Parameter(description = "每页条数，最大 200") @RequestParam(defaultValue = "20")
            @Min(value = 1, message = "每页至少 1 条")
            @Max(value = 200, message = "每页最多 200 条") int size) {

        return ApiResponse.ok(checkinService.list(principal.getUserId(), from, to, subjectId, page, size));
    }

    @GetMapping("/daily")
    @Operation(summary = "按天汇总（日历打卡视图）",
            description = "只返回「哪天总共多少分钟」，不返回明细 —— 日历格子只需要这个数字。")
    public ApiResponse<List<CheckinDtos.DailyMinutes>> daily(
            @AuthenticationPrincipal UserPrincipal principal,

            @Parameter(description = "起始日期（含）") @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,

            @Parameter(description = "结束日期（含）") @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        return ApiResponse.ok(checkinService.dailyMinutes(principal.getUserId(), from, to));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除打卡记录")
    public ApiResponse<Void> delete(@AuthenticationPrincipal UserPrincipal principal,
                                    @PathVariable Long id) {
        checkinService.delete(principal.getUserId(), id);
        return ApiResponse.ok();
    }
}
