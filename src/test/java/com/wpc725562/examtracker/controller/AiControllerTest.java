package com.wpc725562.examtracker.controller;

import com.wpc725562.examtracker.ai.AiAssistant;
import com.wpc725562.examtracker.ai.AiDtos;
import com.wpc725562.examtracker.ai.AiProperties;
import com.wpc725562.examtracker.common.ApiResponse;
import com.wpc725562.examtracker.common.BusinessException;
import com.wpc725562.examtracker.common.ErrorCode;
import com.wpc725562.examtracker.security.UserPrincipal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * AI 控制器。
 *
 * <p>这里刻意<b>不用</b> {@code @SpringBootTest}：本类要验的核心行为之一是
 * 「AI 未启用时返回 503」，而这个状态取决于运行环境里有没有 API key。
 * 放在集成测试里，这个断言就会「在 CI 通过、在本机配了 key 时失败」——
 * 那种随环境飘的测试比没有测试更糟。用构造器直接注入 {@code Optional.empty()}
 * 就能把这条路径钉死，与运行环境无关。
 */
@DisplayName("AiController")
class AiControllerTest {

    private static final Long USER_ID = 42L;
    private static final UserPrincipal PRINCIPAL = new UserPrincipal(USER_ID, "demo");

    private static AiProperties props() {
        return new AiProperties(true, "https://api.deepseek.com", "sk-test",
                "deepseek-flash", Duration.ofSeconds(20), 0.0, 20, Duration.ofMinutes(30));
    }

    @Nested
    @DisplayName("AI 未启用（容器里没有 AiAssistant Bean）")
    class Disabled {

        private final AiController controller = new AiController(Optional.empty(), props());

        @Test
        @DisplayName("★ 提问返回 503 + 说明要配什么，而不是 404 或静默的空答案")
        void askReturns503WithActionableMessage() {
            AiDtos.AskRequest request = new AiDtos.AskRequest("今天怎么样？", null);

            assertThatThrownBy(() -> controller.ask(PRINCIPAL, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .as("必须是 503 而不是 500：我们自己的代码没问题，是功能没开")
                            .isEqualTo(ErrorCode.UNAVAILABLE))
                    .hasMessageContaining("DEEPSEEK_API_KEY");
        }

        @Test
        @DisplayName("UNAVAILABLE 对应的 HTTP 状态码确实是 503")
        void errorCodeMapsTo503() {
            assertThat(ErrorCode.UNAVAILABLE.httpStatus()).isEqualTo(503);
        }

        @Test
        @DisplayName("/ai/status 说 enabled=false，且不返回模型名")
        void statusSaysDisabled() {
            ApiResponse<AiController.StatusResponse> res = controller.status();

            assertThat(res.data().enabled()).isFalse();
            assertThat(res.data().model())
                    .as("不可用时不该暴露用的是哪个模型")
                    .isNull();
        }
    }

    @Nested
    @DisplayName("AI 已启用")
    class Enabled {

        private final AiAssistant assistant = mock(AiAssistant.class);
        private final AiController controller = new AiController(Optional.of(assistant), props());

        @Test
        @DisplayName("★ 用户身份只来自 SecurityContext，请求体里没有这个字段")
        void userIdComesFromPrincipalOnly() {
            when(assistant.ask(eq(USER_ID), any(), any())).thenReturn(
                    new AiDtos.AnswerResponse("答", List.of(), "cid", false, 12L));

            controller.ask(PRINCIPAL, new AiDtos.AskRequest("问题", null));

            verify(assistant).ask(eq(USER_ID), eq("问题"), isNull());
            // AskRequest 上根本没有 userId 字段，所以「换个 id 查别人数据」这个入口不存在。
            // 这条断言的价值在于：一旦有人给 AskRequest 加了 userId 并拿它当参数传，
            // 这里就会红。
            assertThat(AiDtos.AskRequest.class.getRecordComponents())
                    .extracting(java.lang.reflect.RecordComponent::getName)
                    .containsExactly("question", "conversationId");
        }

        @Test
        @DisplayName("会话 id 原样透传（续聊的关键）")
        void conversationIdPassedThrough() {
            when(assistant.ask(eq(USER_ID), any(), eq("cid-9"))).thenReturn(
                    new AiDtos.AnswerResponse("答", List.of(), "cid-9", false, 12L));

            controller.ask(PRINCIPAL, new AiDtos.AskRequest("追问", "cid-9"));

            verify(assistant).ask(USER_ID, "追问", "cid-9");
        }

        @Test
        @DisplayName("回答包在统一响应体里返回")
        void wrapsInApiResponse() {
            AiDtos.AnswerResponse inner =
                    new AiDtos.AnswerResponse("你今天有 3 项任务。", List.of("getOverview"), "cid", false, 1234L);
            when(assistant.ask(any(), any(), any())).thenReturn(inner);

            ApiResponse<AiDtos.AnswerResponse> res =
                    controller.ask(PRINCIPAL, new AiDtos.AskRequest("问题", null));

            assertThat(res.code()).isZero();
            assertThat(res.data().answer()).isEqualTo("你今天有 3 项任务。");
            assertThat(res.data().toolsUsed()).containsExactly("getOverview");
        }

        @Test
        @DisplayName("/ai/status 说 enabled=true 并给出模型名")
        void statusSaysEnabled() {
            ApiResponse<AiController.StatusResponse> res = controller.status();

            assertThat(res.data().enabled()).isTrue();
            assertThat(res.data().model()).isEqualTo("deepseek-flash");
        }
    }

    @Test
    @DisplayName("AI 未启用时不会去碰 AiAssistant（它根本不存在）")
    void disabledNeverTouchesAssistant() {
        AiAssistant assistant = mock(AiAssistant.class);
        AiController controller = new AiController(Optional.empty(), props());

        assertThatThrownBy(() -> controller.ask(PRINCIPAL, new AiDtos.AskRequest("问题", null)))
                .isInstanceOf(BusinessException.class);

        verifyNoInteractions(assistant);
    }
}
