package com.wpc725562.examtracker.controller;

import com.wpc725562.examtracker.common.ApiResponse;
import com.wpc725562.examtracker.common.PageResult;
import com.wpc725562.examtracker.domain.Priority;
import com.wpc725562.examtracker.domain.TaskStatus;
import com.wpc725562.examtracker.dto.TaskDtos;
import com.wpc725562.examtracker.dto.TaskFilter;
import com.wpc725562.examtracker.security.UserPrincipal;
import com.wpc725562.examtracker.service.TaskService;
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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/tasks")
@Tag(name = "03. 任务", description = "每日任务的增删改查与状态流转")
@SecurityRequirement(name = "bearerAuth")
// @Validated 是为了让方法参数上的 @Min / @Max 生效（@RequestParam 上的约束
// 需要类级别的 @Validated 才会被 AOP 拦截）。少了它，size=99999 会静默通过。
@Validated
public class TaskController {

    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    @GetMapping
    @Operation(summary = "任务列表（分页 + 多条件筛选）",
            description = """
                    所有筛选条件都是可选的，不传就是「这个维度不过滤」。

                    **日期用 from / to 表示闭区间**，比如查某一天就传 from = to = 那天。
                    默认排序是「计划日期倒序」，同一天内按 id 升序保证翻页稳定。
                    """)
    public ApiResponse<PageResult<TaskDtos.TaskResponse>> list(
            @AuthenticationPrincipal UserPrincipal principal,

            @Parameter(description = "计划日期起（含）", example = "2026-09-01")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,

            @Parameter(description = "计划日期止（含）", example = "2026-09-30")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,

            @Parameter(description = "科目 id") @RequestParam(required = false) Long subjectId,

            @Parameter(description = "状态：TODO / DONE / SKIPPED")
            @RequestParam(required = false) TaskStatus status,

            @Parameter(description = "优先级：HIGH / MEDIUM / LOW")
            @RequestParam(required = false) Priority priority,

            @Parameter(description = "标题或备注里的关键词") @RequestParam(required = false) String keyword,

            @Parameter(description = "页码，从 1 开始") @RequestParam(defaultValue = "1")
            @Min(value = 1, message = "页码从 1 开始") int page,

            @Parameter(description = "每页条数，最大 200") @RequestParam(defaultValue = "20")
            @Min(value = 1, message = "每页至少 1 条")
            @Max(value = 200, message = "每页最多 200 条") int size,

            @Parameter(description = "排序字段，可选：planDate / planMinutes / priority / status / createdAt / updatedAt / id")
            @RequestParam(required = false) String sortBy,

            @Parameter(description = "排序方向：asc / desc") @RequestParam(required = false) String direction) {

        TaskFilter filter = new TaskFilter(from, to, subjectId, status, priority, keyword);
        return ApiResponse.ok(taskService.list(principal.getUserId(), filter, page, size, sortBy, direction));
    }

    @GetMapping("/{id}")
    @Operation(summary = "任务详情")
    public ApiResponse<TaskDtos.TaskResponse> get(@AuthenticationPrincipal UserPrincipal principal,
                                                  @PathVariable Long id) {
        return ApiResponse.ok(taskService.get(principal.getUserId(), id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "新建任务", description = "科目必须属于当前用户，否则返回 404。")
    public ApiResponse<TaskDtos.TaskResponse> create(@AuthenticationPrincipal UserPrincipal principal,
                                                     @Valid @RequestBody TaskDtos.CreateRequest request) {
        return ApiResponse.ok(taskService.create(principal.getUserId(), request));
    }

    @PutMapping("/{id}")
    @Operation(summary = "修改任务", description = "全量覆盖，不含状态 —— 状态请用 PATCH /tasks/{id}/status。")
    public ApiResponse<TaskDtos.TaskResponse> update(@AuthenticationPrincipal UserPrincipal principal,
                                                     @PathVariable Long id,
                                                     @Valid @RequestBody TaskDtos.UpdateRequest request) {
        return ApiResponse.ok(taskService.update(principal.getUserId(), id, request));
    }

    @PatchMapping("/{id}/status")
    @Operation(summary = "变更任务状态",
            description = "标为 DONE 时自动记录完成时间；改回 TODO 或改成 SKIPPED 时会清空完成时间，"
                    + "保证「完成时间有值」与「状态是 DONE」永远一致。")
    public ApiResponse<TaskDtos.TaskResponse> changeStatus(@AuthenticationPrincipal UserPrincipal principal,
                                                           @PathVariable Long id,
                                                           @Valid @RequestBody TaskDtos.StatusRequest request) {
        return ApiResponse.ok(taskService.changeStatus(principal.getUserId(), id, request));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除任务",
            description = "**不会删除打卡记录** —— 任务没了，但「那天学了多久」是既成事实。"
                    + "数据库外键是 ON DELETE SET NULL，打卡会保留但不再关联该任务。")
    public ApiResponse<Void> delete(@AuthenticationPrincipal UserPrincipal principal,
                                    @PathVariable Long id) {
        taskService.delete(principal.getUserId(), id);
        return ApiResponse.ok();
    }
}
