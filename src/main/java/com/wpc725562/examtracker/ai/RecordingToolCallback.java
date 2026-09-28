package com.wpc725562.examtracker.ai;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.List;

/**
 * 记录「这次问答到底调了哪些工具」的装饰器。
 *
 * <p><b>为什么需要它：</b>「模型为什么这么答」是排查 AI 功能时唯一有用的问题，
 * 而回答这个问题的唯一手段就是看它调了什么工具、拿到了什么数据。
 * 只记日志不够 —— 前端也要能看到（用户对「答得不对」的直觉往往来自
 * 「它根本没查数据」）。
 *
 * <p><b>为什么用装饰器而不是在工具方法里插一行记录：</b>
 * 工具类应该只表达业务语义。让它自己维护「调用轨迹」会把两件事缠在一起，
 * 而且五个方法各写一遍，早晚漏一个。
 *
 * <p><b>为什么按请求新建而不是用单例 + 全局状态：</b>工具回调是并发执行的，
 * 用一个全局的轨迹列表会把并发的多个用户的调用记录混在一起 ——
 * 那既泄露信息，也让日志失去意义。这里的 {@code recorder} 是每次请求新建的。
 */
final class RecordingToolCallback implements ToolCallback {

    /** 一次工具调用的记录。 */
    record Call(String tool, boolean succeeded) {
    }

    private final ToolCallback delegate;

    /** 本次请求共享的记录列表，由 {@link ExamTrackerAiAssistant} 创建并读取。 */
    private final List<Call> recorder;

    RecordingToolCallback(ToolCallback delegate, List<Call> recorder) {
        this.delegate = delegate;
        this.recorder = recorder;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        // 必须转发：ToolMetadata 里带着 returnDirect 之类的语义，
        // 吞掉它会让工具行为悄悄改变。
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        return call(toolInput, null);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        String name = delegate.getToolDefinition().name();
        try {
            String result = delegate.call(toolInput, toolContext);
            recorder.add(new Call(name, true));
            return result;
        } catch (RuntimeException e) {
            // 记下失败但**必须原样抛出**：Spring AI 会把异常消息作为工具结果回传给模型，
            // 模型据此纠错重试（比如科目名拼错了，我们会把候选列表给它）。
            // 在这里吞掉异常等于把这个纠错通道掐断。
            recorder.add(new Call(name, false));
            throw e;
        }
    }
}
