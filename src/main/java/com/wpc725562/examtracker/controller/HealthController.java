package com.wpc725562.examtracker.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 存活探针。
 *
 * <p>Actuator 已经提供了 {@code /actuator/health}，功能更全（能带数据库、磁盘状态）。
 * 这里额外加一个极简的 {@code /health} 是因为它**不查任何依赖** ——
 * 只回答「这个进程还在处理 HTTP 请求吗」。
 *
 * <p>两者用途不同：负载均衡器/容器编排要的是「能不能把流量打过来」，
 * 这时候数据库短暂不可用不应该导致实例被摘掉；而运维排查要的是「依赖都正常吗」。
 * 混用同一个探针会导致一次数据库抖动把所有实例同时判死。
 */
@RestController
@Tag(name = "00. 健康检查")
public class HealthController {

    @GetMapping("/health")
    @SecurityRequirements
    @Operation(summary = "存活探针", description = "不查任何外部依赖，只表示进程存活。")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }
}
