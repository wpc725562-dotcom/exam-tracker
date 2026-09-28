package com.wpc725562.examtracker.ai;

import com.wpc725562.examtracker.common.BusinessException;
import com.wpc725562.examtracker.common.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.time.LocalDate;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 问答编排。
 *
 * <p>这里<b>不</b>真的调模型：{@code ChatClient} 是接口，用 Mockito 把
 * 那条链式调用（{@code prompt().system().user().advisors().options().call().content()}）
 * 换成可控的桩。
 *
 * <p>这样做的意义正是「LLM 调用本身不进 CI」这条规则的落地方式：
 * 把不可确定的那一小块（模型的输出）隔离掉，剩下的编排逻辑
 * —— 会话 id 怎么流转、失败怎么报、降级怎么标记 —— 全部可以确定性验证。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ExamTrackerAiAssistant")
class ExamTrackerAiAssistantTest {

    private static final Long USER = 7L;

    @Mock
    private ChatClient chatClient;
    @Mock
    private ExamTrackerTools tools;

    private ChatClient.ChatClientRequestSpec spec;
    private ChatClient.CallResponseSpec callSpec;
    private AiProperties props;
    private ExamTrackerAiAssistant assistant;

    @BeforeEach
    void setUp() {
        spec = mock(ChatClient.ChatClientRequestSpec.class);
        callSpec = mock(ChatClient.CallResponseSpec.class);

        when(chatClient.prompt()).thenReturn(spec);
        when(spec.system(anyString())).thenReturn(spec);
        when(spec.user(anyString())).thenReturn(spec);
        when(spec.advisors(any(Consumer.class))).thenReturn(spec);
        when(spec.options(any(ChatOptions.class))).thenReturn(spec);
        when(spec.call()).thenReturn(callSpec);

        props = new AiProperties(true, "https://api.deepseek.com", "sk-test",
                "deepseek-flash", java.time.Duration.ofSeconds(20), 0.0, 20,
                java.time.Duration.ofMinutes(30));
        assistant = new ExamTrackerAiAssistant(chatClient, tools, props);
    }

    // ================================================================ 正常路径 ===

    @Test
    @DisplayName("返回模型给出的答案，并带上耗时与会话 id")
    void returnsAnswer() {
        when(callSpec.content()).thenReturn("你今天有 3 项任务。");

        AiDtos.AnswerResponse res = assistant.ask(USER, "今天怎么样？", null);

        assertThat(res.answer()).isEqualTo("你今天有 3 项任务。");
        assertThat(res.conversationId()).isNotBlank();
        assertThat(res.degraded()).isFalse();
        assertThat(res.elapsedMs()).isGreaterThanOrEqualTo(0L);
    }

    @Test
    @DisplayName("★ 不传会话 id 时服务端生成一个（前端不需要自己造 id）")
    void generatesConversationIdWhenAbsent() {
        when(callSpec.content()).thenReturn("好");

        AiDtos.AnswerResponse res = assistant.ask(USER, "问题", null);

        assertThat(res.conversationId())
                .as("应该是一个 UUID 形式的字符串")
                .matches("[0-9a-fA-F-]{36}");
    }

    @Test
    @DisplayName("★ 传了会话 id 就原样用回去（这是多轮对话能续上的前提）")
    void reusesGivenConversationId() {
        when(callSpec.content()).thenReturn("好");

        AiDtos.AnswerResponse res = assistant.ask(USER, "问题", "my-conversation-42");

        assertThat(res.conversationId()).isEqualTo("my-conversation-42");
    }

    @Test
    @DisplayName("空串/空白的会话 id 当成「没传」，生成新的")
    void blankConversationIdTreatedAsAbsent() {
        when(callSpec.content()).thenReturn("好");

        assertThat(assistant.ask(USER, "问题", "   ").conversationId())
                .matches("[0-9a-fA-F-]{36}");
    }

    @Test
    @DisplayName("★ 会话 id 被传给了记忆 advisor —— 多轮对话真正接通的地方")
    void conversationIdReachesMemoryAdvisor() {
        when(callSpec.content()).thenReturn("好");

        assistant.ask(USER, "问题", "cid-1");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Consumer<ChatClient.AdvisorSpec>> captor =
                ArgumentCaptor.forClass(Consumer.class);
        verify(spec).advisors(captor.capture());

        ChatClient.AdvisorSpec advisorSpec = mock(ChatClient.AdvisorSpec.class);
        captor.getValue().accept(advisorSpec);

        verify(advisorSpec).param(ChatMemory.CONVERSATION_ID, "cid-1");
    }

    // ================================================================ 提示词 ===

    @Test
    @DisplayName("系统提示词带上今天日期，且明确限制 Markdown 格式")
    void systemPromptCarriesDateAndFormatLimits() {
        when(callSpec.content()).thenReturn("好");

        assistant.ask(USER, "问题", null);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(spec).system(captor.capture());
        String prompt = captor.getValue();

        assertThat(prompt)
                .as("模型必须知道「今天」是哪天，否则会把相对时间算错")
                .contains(LocalDate.now().toString());

        // ★ 这条不是形式主义：前端的 formatAnswer() 只实现了 **加粗** 和行首列表两项，
        //   其它 Markdown 会**原样显示**。实测中模型确实会输出表格，用户看到的
        //   是一堆裸竖线。所以「禁止表格」是提示词的一部分契约，不能被悄悄删掉。
        assertThat(prompt)
                .as("必须禁止表格 —— 前端会把 | a | b | 原样显示成竖线")
                .contains("表格");
        assertThat(prompt)
                .as("要明确告诉模型前端认哪种加粗写法")
                .contains("**加粗**");
    }

    // ============================================================ 工具与上下文 ===

    @Test
    @DisplayName("★ userId 通过 toolContext 注入，且模型看不到它")
    void userIdInjectedThroughToolContext() {
        when(callSpec.content()).thenReturn("好");

        assistant.ask(USER, "问题", null);

        ArgumentCaptor<ChatOptions> captor = ArgumentCaptor.forClass(ChatOptions.class);
        verify(spec).options(captor.capture());

        OpenAiChatOptions options = (OpenAiChatOptions) captor.getValue();
        assertThat(options.getToolContext())
                .containsEntry(ExamTrackerTools.USER_ID_KEY, USER);
    }

    @Test
    @DisplayName("★ 工具以 toolCallbacks 注册（5 个都在）")
    void toolsRegisteredAsCallbacks() {
        when(callSpec.content()).thenReturn("好");

        assistant.ask(USER, "问题", null);

        ArgumentCaptor<ChatOptions> captor = ArgumentCaptor.forClass(ChatOptions.class);
        verify(spec).options(captor.capture());

        OpenAiChatOptions options = (OpenAiChatOptions) captor.getValue();
        assertThat(options.getToolCallbacks()).hasSize(5);
    }

    @Test
    @DisplayName("工具名清单可用于启动日志与排查")
    void exposesRegisteredToolNames() {
        // 先真的走一次问答，这样断言的是「问答时确实注册了这些工具」，
        // 而不只是读了一个方法返回值
        when(callSpec.content()).thenReturn("好");
        assistant.ask(USER, "问题", null);

        assertThat(assistant.registeredToolNames())
                .containsExactlyInAnyOrder(
                        "getOverview", "getSubjectBoard", "listSubjects", "searchTasks", "getDailyMinutes");
    }

    // ================================================================ 失败路径 ===

    @Nested
    @DisplayName("★ 失败时必须明确报错，绝不静默返回空答案")
    class FailurePaths {

        @Test
        @DisplayName("模型调用抛异常 → BusinessException(UNAVAILABLE)，HTTP 503")
        void modelFailureBecomes503() {
            when(callSpec.content()).thenThrow(new RuntimeException("Connection timed out"));

            assertThatThrownBy(() -> assistant.ask(USER, "问题", null))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(ErrorCode.UNAVAILABLE))
                    .hasMessageContaining("超时");
        }

        @Test
        @DisplayName("★ 超时被翻译成可操作的话，而不是把 JDK 原话「Request cancelled」丢给用户")
        void timeoutMessageIsActionable() {
            // 这是**实测复现出来的**：把 AI_TIMEOUT 设成 5 秒、上游 sleep 30 秒，
            // 请求正好在第 5 秒返回 503，底层异常的原话就是这句。
            // 直接展示原文等于什么都没说 —— 用户既不知道发生了什么，也不知道该调什么。
            when(callSpec.content())
                    .thenThrow(new RuntimeException(new java.io.IOException("Request cancelled")));

            assertThatThrownBy(() -> assistant.ask(USER, "问题", null))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("超时")
                    .hasMessageContaining("AI_TIMEOUT")
                    .hasMessageNotContaining("Request cancelled");
        }

        @Test
        @DisplayName("★ 连不上模型服务时，提示指向网络 / AI_BASE_URL，而不是含糊的「失败」")
        void connectFailurePointsAtConfiguration() {
            when(callSpec.content())
                    .thenThrow(new RuntimeException(new java.net.ConnectException("Connection refused")));

            assertThatThrownBy(() -> assistant.ask(USER, "问题", null))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("无法连接到模型服务")
                    .hasMessageContaining("AI_BASE_URL");
        }

        @Test
        @DisplayName("认不出的异常保留原话（不编一个「大概是因为……」）")
        void unknownFailureKeepsRawMessage() {
            when(callSpec.content())
                    .thenThrow(new RuntimeException(new IllegalStateException("配额已用尽 quota_exceeded")));

            assertThatThrownBy(() -> assistant.ask(USER, "问题", null))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("quota_exceeded");
        }

        @Test
        @DisplayName("★ 模型返回 null → 也报 503，而不是把 null 当答案返回")
        void nullAnswerBecomes503() {
            when(callSpec.content()).thenReturn(null);

            assertThatThrownBy(() -> assistant.ask(USER, "问题", null))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("空回答");
        }

        @Test
        @DisplayName("★ 模型返回空串/纯空白 → 同样报 503")
        void blankAnswerBecomes503() {
            when(callSpec.content()).thenReturn("   \n  ");

            assertThatThrownBy(() -> assistant.ask(USER, "问题", null))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("空回答");
        }

        @Test
        @DisplayName("错误消息取到最根因，并截断（不要把整段上游返回体丢给用户）")
        void errorMessageUsesRootCauseAndIsTruncated() {
            String longMessage = "上游返回了很长的一段东西".repeat(40);
            when(callSpec.content())
                    .thenThrow(new RuntimeException("外层包装", new IllegalStateException(longMessage)));

            assertThatThrownBy(() -> assistant.ask(USER, "问题", null))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("上游返回了很长的一段东西")
                    .satisfies(e -> assertThat(e.getMessage().length())
                            .as("不能把上游那一大坨原样塞给用户")
                            .isLessThan(400));
        }

        @Test
        @DisplayName("最根因没有 message 时退化成异常类名，而不是拼出 \"null\"")
        void errorWithoutMessageFallsBackToClassName() {
            when(callSpec.content())
                    .thenThrow(new RuntimeException(new IllegalStateException()));

            assertThatThrownBy(() -> assistant.ask(USER, "问题", null))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("IllegalStateException")
                    .hasMessageNotContaining("null");
        }
    }
}
