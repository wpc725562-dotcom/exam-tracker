package com.wpc725562.examtracker.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 工具调用记录装饰器。
 *
 * <p>这里最要紧的一条是<b>「失败要记下来，但必须原样抛出去」</b>：
 * Spring AI 会把工具抛出的异常消息当作工具结果回传给模型，
 * 模型据此纠正参数重试。如果在装饰器里把异常吞掉，这条纠错通道就断了 ——
 * 而且断得很安静：模型会拿到一个空结果，然后编一个答案出来。
 */
@DisplayName("RecordingToolCallback")
class RecordingToolCallbackTest {

    private static ToolCallback fake(String name, String result, RuntimeException failure) {
        return new ToolCallback() {

            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                        .name(name)
                        .description("测试用工具")
                        .inputSchema("{}")
                        .build();
            }

            @Override
            public ToolMetadata getToolMetadata() {
                return ToolMetadata.builder().returnDirect(true).build();
            }

            @Override
            public String call(String toolInput) {
                return call(toolInput, null);
            }

            @Override
            public String call(String toolInput, ToolContext toolContext) {
                if (failure != null) {
                    throw failure;
                }
                return result;
            }
        };
    }

    @Test
    @DisplayName("成功调用会被记下来，结果原样返回")
    void recordsSuccess() {
        List<RecordingToolCallback.Call> recorder = new ArrayList<>();
        ToolCallback cb = new RecordingToolCallback(fake("getOverview", "{\"ok\":1}", null), recorder);

        String out = cb.call("{}", new ToolContext(Map.of("userId", 1L)));

        assertThat(out).isEqualTo("{\"ok\":1}");
        assertThat(recorder).containsExactly(new RecordingToolCallback.Call("getOverview", true));
    }

    @Test
    @DisplayName("★ 失败会记下来，并且异常必须原样抛出（不能吞）")
    void recordsFailureAndRethrows() {
        List<RecordingToolCallback.Call> recorder = new ArrayList<>();
        IllegalArgumentException boom =
                new IllegalArgumentException("找不到科目「物理」。当前可选科目：数学、英语");
        ToolCallback cb = new RecordingToolCallback(fake("getDailyMinutes", null, boom), recorder);

        assertThatThrownBy(() -> cb.call("{}", new ToolContext(Map.of("userId", 1L))))
                .as("异常必须原样抛出去 —— Spring AI 靠它把提示回传给模型")
                .isSameAs(boom);

        assertThat(recorder).containsExactly(new RecordingToolCallback.Call("getDailyMinutes", false));
    }

    @Test
    @DisplayName("失败时也记录了工具名（前端要能看出是哪一步失败）")
    void failureKeepsToolName() {
        List<RecordingToolCallback.Call> recorder = new ArrayList<>();
        ToolCallback cb = new RecordingToolCallback(
                fake("searchTasks", null, new IllegalStateException("炸了")), recorder);

        assertThatThrownBy(() -> cb.call("{}", null)).isInstanceOf(IllegalStateException.class);
        assertThat(recorder).singleElement()
                .extracting(RecordingToolCallback.Call::tool)
                .isEqualTo("searchTasks");
    }

    @Test
    @DisplayName("ToolDefinition 与 ToolMetadata 都要转发（吞掉 metadata 会改变工具行为）")
    void delegatesDefinitionAndMetadata() {
        ToolCallback cb = new RecordingToolCallback(
                fake("getSubjectBoard", "x", null), new ArrayList<>());

        assertThat(cb.getToolDefinition().name()).isEqualTo("getSubjectBoard");
        assertThat(cb.getToolDefinition().inputSchema()).isEqualTo("{}");
        assertThat(cb.getToolMetadata().returnDirect())
                .as("returnDirect 之类的语义必须保留")
                .isTrue();
    }

    @Test
    @DisplayName("多次调用按顺序记录")
    void recordsInOrder() {
        List<RecordingToolCallback.Call> recorder = new ArrayList<>();
        ToolCallback ok = new RecordingToolCallback(fake("a", "1", null), recorder);
        ToolCallback bad = new RecordingToolCallback(
                fake("b", null, new IllegalStateException("x")), recorder);

        ok.call("{}", null);
        assertThatThrownBy(() -> bad.call("{}", null)).isInstanceOf(IllegalStateException.class);
        ok.call("{}", null);

        assertThat(recorder).containsExactly(
                new RecordingToolCallback.Call("a", true),
                new RecordingToolCallback.Call("b", false),
                new RecordingToolCallback.Call("a", true));
    }
}
