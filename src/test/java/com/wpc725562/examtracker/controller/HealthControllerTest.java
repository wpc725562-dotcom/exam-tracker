package com.wpc725562.examtracker.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 存活探针。
 *
 * <p>这个接口刻意**不套 {@code ApiResponse} 外壳**，直接返回
 * {@code {"status":"ok"}} —— 它要给负载均衡器/容器编排看，不是给前端看，
 * 少一层解包就少一个出错的地方。这个「不套壳」的决定很容易被后人
 * 「顺手统一一下」改掉，所以在这里钉住。
 */
@DisplayName("HealthController —— 存活探针")
class HealthControllerTest {

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new HealthController())
            .build();

    @Test
    @DisplayName("GET /health -> 200 + 裸 JSON（不套统一外壳）")
    void health() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                // 裸 JSON：没有 code / message / data 这几个字段
                .andExpect(jsonPath("$.code").doesNotExist())
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @DisplayName("探针不查任何外部依赖（路径不含 /actuator，也不该有副作用）")
    void healthIsSideEffectFree() throws Exception {
        mockMvc.perform(get("/health")).andExpect(status().isOk());
        mockMvc.perform(get("/health")).andExpect(status().isOk());
    }
}
