package com.wpc725562.examtracker.controller;

import com.wpc725562.examtracker.ai.AiAssistant;
import com.wpc725562.examtracker.ai.AiDtos;
import com.wpc725562.examtracker.ai.AiProperties;
import com.wpc725562.examtracker.common.ApiResponse;
import com.wpc725562.examtracker.common.BusinessException;
import com.wpc725562.examtracker.common.ErrorCode;
import com.wpc725562.examtracker.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

/**
 * AI 助手：用自然语言查自己的学习数据。
 *
 * <p><b>为什么注入 {@code Optional<AiAssistant>} 而不是直接注入 {@link AiAssistant}：</b>
 * AI 层是<b>可选</b>的（没有 API key 的环境不启用）。如果直接注入，
 * 未启用时容器里没有这个 Bean，整个应用都起不来 ——
 * 「一个可选功能没配好」不该让「记账、打卡」这些核心功能一起挂掉。
 *
 * <p>所以这里始终注册这个控制器，未启用时返回一个<b>说明清楚原因</b>的 503，
 * 而不是 404。区别在于：404 会让调用方以为「路径写错了」，
 * 而 503 加上具体原因能让人立刻知道「要配什么」。
 *
 * <p>本接口天然需要登录（不在 {@code SecurityConfig.PUBLIC_PATHS} 里），
 * 且用户身份<b>只</b>来自 {@code SecurityContext} —— 请求体里没有、也不接受任何用户标识，
 * 所以不存在「换个 id 就能查别人数据」的入口。
 */
@RestController
@RequestMapping("/ai")
@Tag(name = "06. AI 助手", description = "用自然语言查询自己的学习数据")
@SecurityRequirement(name = "bearerAuth")
@Validated
public class AiController {

    private final Optional<AiAssistant> assistant;
    private final AiProperties aiProperties;

    public AiController(Optional<AiAssistant> assistant, AiProperties aiProperties) {
        this.assistant = assistant;
        this.aiProperties = aiProperties;
    }

    /**
     * AI 是否可用。
     *
     * <p>存在的唯一目的是让前端能<b>诚实地</b>决定要不要显示问答面板。
     * 没有它的话，clone 下来直接打开页面的人会看到一个输入框，
     * 问一句才被告知「未启用」—— 那是个很糟的第一印象。
     *
     * <p><b>刻意不返回任何 key 相关信息</b>（连掩码后的都不返回）：
     * 这个接口对任何登录用户开放，没必要让调用方知道服务端配没配 key、
     * 用的是哪家的 key。只回答「能不能用」。
     */
    @GetMapping("/status")
    @Operation(summary = "AI 功能是否可用",
            description = "前端用它决定是否显示问答面板。不返回任何凭据信息。")
    public ApiResponse<StatusResponse> status() {
        return ApiResponse.ok(new StatusResponse(
                assistant.isPresent(),
                assistant.isPresent() ? aiProperties.model() : null));
    }

    @Schema(description = "AI 功能状态")
    public record StatusResponse(
            @Schema(description = "是否可用") boolean enabled,
            @Schema(description = "可用时的模型名，不可用时为 null") String model) {
    }

    @PostMapping("/ask")
    @Operation(summary = "自然语言提问",
            description = """
                    把一个自然语言问题翻译成对已有统计接口的调用，再用自然语言回答。

                    **能问什么**（只读，不会修改任何数据）：
                    整体进度、今日完成情况、各科看板、任务检索、某科目某段时间的投入时长、
                    考试倒计时、连续打卡天数。

                    **多轮对话**：第一次不传 `conversationId`，服务端会生成一个并在响应里返回；
                    继续追问时把它原样带回来即可。会话保存在服务端内存里，
                    默认空闲 30 分钟过期，**服务重启即丢**。

                    **失败时**：模型不可用（超时 / 配额 / 网络）返回 **503**，
                    不会静默返回空答案 —— 空答案会被误读成「我没有数据」。

                    **数据范围**：永远只查当前登录用户自己的数据。用户身份来自 token，
                    请求体里没有、也不接受任何用户标识。
                    """)
    public ApiResponse<AiDtos.AnswerResponse> ask(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody AiDtos.AskRequest request) {

        AiAssistant ai = assistant.orElseThrow(() -> new BusinessException(
                ErrorCode.UNAVAILABLE,
                "AI 功能未启用。需要设置 app.ai.enabled=true，并通过环境变量提供 DEEPSEEK_API_KEY 后重启。"));

        return ApiResponse.ok(
                ai.ask(principal.getUserId(), request.question(), request.conversationId()));
    }
}
