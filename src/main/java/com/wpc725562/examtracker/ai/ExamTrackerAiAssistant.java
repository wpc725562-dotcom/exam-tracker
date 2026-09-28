package com.wpc725562.examtracker.ai;

import com.wpc725562.examtracker.common.BusinessException;
import com.wpc725562.examtracker.common.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 基于 Spring AI + DeepSeek 的问答实现。
 *
 * <p>一次问答的完整链路：
 * <pre>
 *   用户问题
 *     → 系统提示词（含今天的日期）+ 历史消息（若续聊）
 *     → LLM 决定调用哪些工具
 *     → 工具在服务端执行（userId 由 ToolContext 注入，模型碰不到）
 *     → 工具结果回填给 LLM
 *     → LLM 组织成人话
 *     → 返回答案 + 本次用到的工具 + 会话 id
 * </pre>
 */
public class ExamTrackerAiAssistant implements AiAssistant {

    private static final Logger log = LoggerFactory.getLogger(ExamTrackerAiAssistant.class);

    private final ChatClient chatClient;
    private final AiProperties props;

    /**
     * 工具定义解析一次就复用。
     *
     * <p>工具集合是固定的（一个单例 Bean 上的 5 个 {@code @Tool} 方法），
     * 每来一个请求就反射扫一遍注解纯属浪费。真正按请求变化的只有
     * 「调用轨迹记录器」，那在外层包一层即可。
     */
    private final ToolCallback[] baseCallbacks;

    public ExamTrackerAiAssistant(ChatClient chatClient, ExamTrackerTools tools, AiProperties props) {
        this.chatClient = chatClient;
        this.props = props;
        this.baseCallbacks = ToolCallbacks.from(tools);
    }

    @Override
    public AiDtos.AnswerResponse ask(Long userId, String question, String conversationId) {

        long startedAt = System.nanoTime();

        // 会话 id 由服务端生成，前端只负责原样回传 —— 不让前端自己造，
        // 否则「两个用户用了同一个 id」就变成了串话，而这是最难查的一类问题。
        String conversationId2 = (conversationId == null || conversationId.isBlank())
                ? UUID.randomUUID().toString()
                : conversationId.trim();

        // 每个请求一份记录器。不能用单例：工具是并发执行的，
        // 共享一份记录会把不同用户、不同请求的调用混在一起。
        List<RecordingToolCallback.Call> calls =
                Collections.synchronizedList(new ArrayList<>());

        List<ToolCallback> callbacks = Arrays.stream(baseCallbacks)
                .map(cb -> (ToolCallback) new RecordingToolCallback(cb, calls))
                .toList();

        // ★ 工具与 toolContext 都放在 options 里，而不是用 .tools() / .toolContext()：
        //   我们要传的是**包装过的** ToolCallback，而 .tools(Object...) 走的是
        //   「扫描 @Tool 注解」那条路（MethodToolCallbackProvider），它不认 ToolCallback 实例。
        //   放在 options 里同时保证了 toolCallbacks 与 toolContext 是同一次请求的一对。
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .toolCallbacks(callbacks)
                .toolContext(Map.of(ExamTrackerTools.USER_ID_KEY, userId))
                .build();

        String answer;
        try {
            answer = chatClient.prompt()
                    .system(systemPrompt())
                    .user(question)
                    // 续聊：把会话 id 交给记忆 advisor，它会自动带上/写回这段对话的历史
                    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId2))
                    .options(options)
                    .call()
                    .content();
        } catch (RuntimeException e) {
            // 走到这里说明模型这一侧出了问题（超时 / 配额 / 网络 / 鉴权）。
            // 明确报 503，**绝不静默返回空答案** —— 空答案会被用户读成
            // 「我确实没有数据」，而真相是「我们没能查到」。
            log.error("[AI] 调用模型失败 userId={} conversationId={}", userId, conversationId2, e);
            throw new BusinessException(ErrorCode.UNAVAILABLE,
                    "AI 服务暂时不可用（" + describeFailure(e) + "），请稍后重试");
        }

        if (answer == null || answer.isBlank()) {
            log.error("[AI] 模型返回了空回答 userId={} conversationId={}", userId, conversationId2);
            throw new BusinessException(ErrorCode.UNAVAILABLE,
                    "AI 服务返回了空回答，请稍后重试");
        }

        List<String> toolsUsed = calls.stream()
                .filter(RecordingToolCallback.Call::succeeded)
                .map(RecordingToolCallback.Call::tool)
                .toList();
        List<String> toolsFailed = calls.stream()
                .filter(c -> !c.succeeded())
                .map(RecordingToolCallback.Call::tool)
                .toList();

        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;

        // 有工具失败 ⇒ 模型是在「某个数据没拿到」的前提下作答的，答案可能不完整。
        // 如实标记出来，而不是假装一切正常。
        boolean degraded = !toolsFailed.isEmpty();

        log.info("[AI] 问答完成 userId={} conversationId={} 耗时={}ms 工具={} 失败={} 降级={}",
                userId, conversationId2, elapsedMs,
                toolsUsed.isEmpty() ? "（未调用工具）" : toolsUsed, toolsFailed, degraded);

        return new AiDtos.AnswerResponse(answer, toolsUsed, conversationId2, degraded, elapsedMs);
    }

    /**
     * 系统提示词。
     *
     * <p>几个刻意的设计：
     * <ul>
     *   <li><b>把今天的日期写进去</b>：模型需要参照物才能理解「这周」「最近」，
     *       否则它会用自己的训练截止时间去猜。</li>
     *   <li><b>明确禁止索要 ID</b>：用户看到「请提供你的用户 ID」会觉得这个产品很怪，
     *       而且那暗示着「数据范围是可指定的」—— 我们要的是「你只能看自己的」。</li>
     *   <li><b>要求转达 note</b>：工具返回的 note 是「数据被截断」这类警告，
     *       模型如果略过它，就会拿不完整的列表下一个完整的结论。</li>
     *   <li><b>要求承认查不到</b>：不写这条，模型会用常识补一个数字出来，
     *       而用户没法分辨。</li>
     * </ul>
     */
    private String systemPrompt() {
        LocalDate today = LocalDate.now();
        String weekday = today.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.SIMPLIFIED_CHINESE);

        return """
                你是「备考任务追踪」应用里的学习数据助手。今天是 %s（%s）。

                你的职责只有一件：回答与**当前登录用户自己的**学习数据有关的问题。

                规则：
                1. 凡是涉及数据的问题，**必须先调用工具查询**。你对这个用户的数据没有任何先验知识，
                   凭印象回答一定是错的。
                2. 绝对不要向用户索要用户 ID、账号、数据库字段名这类信息。系统已经在服务端确定了
                   「你是谁」，你拿到的数据永远是这个用户自己的。
                3. 查不到数据就直说「查不到」或「还没有记录」，**不要编造数字**，
                   也不要用常识去补一个「大概是」的值。
                4. 工具返回值里的 note 字段是重要提示（例如「共 N 条，只返回了前 M 条」），
                   必须如实转达给用户，不要忽略。
                5. 工具执行失败时，错误消息里会写明原因（例如科目名不对并给出候选列表）。
                   据此纠正后重试，或者向用户澄清 —— 不要放弃，也不要自己猜一个。
                6. 与学习数据无关的问题（写代码、闲聊、通用常识），礼貌说明你只能回答
                   学习数据相关的问题。
                7. 用简体中文回答，简洁直接。数字带单位（分钟 / 小时 / 天 / 项）；
                   超过 60 分钟的时长换算成「X 小时 Y 分钟」更好读。
                8. **格式限制**：前端只认两种排版 —— `**加粗**` 和行首的 `- ` 列表项，
                   其它 Markdown 一律按纯文本原样显示。所以：
                   · 不要输出表格（`| a | b |` 会显示成一堆竖线），要罗列多项就拆成列表；
                   · 不要用标题（`#`）、代码块（```）、图片、链接；
                   · 不要嵌套列表，不要用 `---` 分隔线。
                """.formatted(today, weekday);
    }

    /** 取最根因的说明，用于给用户看的错误消息（不要把整个堆栈抛给前端）。 */
    private static String rootCause(Throwable e) {
        Throwable cur = rootCauseOf(e);
        String message = cur.getMessage();
        if (message == null || message.isBlank()) {
            return cur.getClass().getSimpleName();
        }
        // 截断：上游返回体可能很长，直接塞进用户可见的消息里不合适
        return message.length() > 120 ? message.substring(0, 120) + "…" : message;
    }

    private static Throwable rootCauseOf(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur;
    }

    /**
     * 把底层异常翻译成「用户看完知道该做什么」的一句话。
     *
     * <p><b>为什么需要它（这是实测逼出来的，不是想象出来的）：</b>
     * 读超时的时候，JDK HttpClient 抛出来的原话是
     * {@code IOException: Request cancelled} —— 把这句话原样丢给用户等于什么都没说。
     *
     * <p>实测复现方式：把 {@code AI_TIMEOUT} 设成 5 秒、让上游故意 sleep 30 秒，
     * 请求正好在第 5 秒返回 503，消息就是「AI 服务暂时不可用（Request cancelled）」。
     * 也就是说这条路径<b>在生产里真的会走到</b>（我们第一次跑端到端时就撞上过一次，
     * 见 README），而它给出的提示毫无可操作性。
     *
     * <p><b>只翻译「已知且有明确动作」的几类</b>，其余原样保留。
     * 猜错原因比说「原因不明」更糟：后者至少不会把人引到错误的方向去。
     */
    private String describeFailure(Throwable e) {
        Throwable root = rootCauseOf(e);
        String raw = root.getMessage() == null ? "" : root.getMessage();
        String lower = raw.toLowerCase(Locale.ROOT);

        boolean isTimeout = root instanceof HttpTimeoutException
                || root instanceof SocketTimeoutException
                // 「Request cancelled」是 JDK HttpClient 在读超时时抛的原话，
                // 见上面注释里的复现步骤
                || lower.contains("request cancelled")
                || lower.contains("timed out")
                || lower.contains("timeout");
        if (isTimeout) {
            // 注意不要在这里用括号：外层会再包一层「（…）」，套起来读着很别扭。
            return "请求超时 —— 超过 " + props.timeout().toSeconds() + " 秒未收到模型响应。"
                    + "可稍后重试，或调大环境变量 AI_TIMEOUT";
        }

        if (root instanceof UnknownHostException) {
            return "无法解析模型服务地址：" + root.getClass().getSimpleName()
                    + "。请检查网络或 AI_BASE_URL 配置";
        }
        if (root instanceof ConnectException) {
            return "无法连接到模型服务：连接被拒绝。请检查网络或 AI_BASE_URL 配置";
        }

        // 其余情况保留原话（截断）+ 异常类名：说不清原因时，如实给出原始信息，
        // 让人能自己判断，比编一个「大概是因为……」有用。
        return rootCause(e);
    }

    /** 供日志与测试：本次注册了哪些工具。 */
    List<String> registeredToolNames() {
        return Arrays.stream(baseCallbacks)
                .map(cb -> cb.getToolDefinition().name())
                .collect(Collectors.toList());
    }
}
