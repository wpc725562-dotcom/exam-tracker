package com.wpc725562.examtracker.controller;

import com.wpc725562.examtracker.common.ApiResponse;
import com.wpc725562.examtracker.dto.SubjectDtos;
import com.wpc725562.examtracker.security.UserPrincipal;
import com.wpc725562.examtracker.service.SubjectService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/subjects")
@Tag(name = "02. 科目", description = "四科（或多科）的增删改查")
@SecurityRequirement(name = "bearerAuth")
public class SubjectController {

    private final SubjectService subjectService;

    public SubjectController(SubjectService subjectService) {
        this.subjectService = subjectService;
    }

    @GetMapping
    @Operation(summary = "科目列表", description = "按 sortOrder 升序返回当前用户的全部科目。")
    public ApiResponse<List<SubjectDtos.SubjectResponse>> list(@AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.ok(subjectService.list(principal.getUserId()));
    }

    @GetMapping("/{id}")
    @Operation(summary = "科目详情")
    public ApiResponse<SubjectDtos.SubjectResponse> get(@AuthenticationPrincipal UserPrincipal principal,
                                                        @PathVariable Long id) {
        return ApiResponse.ok(subjectService.get(principal.getUserId(), id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "新建科目", description = "同一用户名下科目不可重名。不传 sortOrder 时排到最后。")
    public ApiResponse<SubjectDtos.SubjectResponse> create(@AuthenticationPrincipal UserPrincipal principal,
                                                           @Valid @RequestBody SubjectDtos.CreateRequest request) {
        return ApiResponse.ok(subjectService.create(principal.getUserId(), request));
    }

    @PutMapping("/{id}")
    @Operation(summary = "修改科目")
    public ApiResponse<SubjectDtos.SubjectResponse> update(@AuthenticationPrincipal UserPrincipal principal,
                                                           @PathVariable Long id,
                                                           @Valid @RequestBody SubjectDtos.UpdateRequest request) {
        return ApiResponse.ok(subjectService.update(principal.getUserId(), id, request));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除科目",
            description = "科目下还有任务或打卡记录时返回 409 并给出数量。"
                    + "确认要连同数据一起删除时，加 `force=true`。")
    public ApiResponse<Void> delete(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id,
            @Parameter(description = "是否连同该科目下的任务和打卡记录一起删除")
            @RequestParam(defaultValue = "false") boolean force) {
        subjectService.delete(principal.getUserId(), id, force);
        return ApiResponse.ok();
    }
}
