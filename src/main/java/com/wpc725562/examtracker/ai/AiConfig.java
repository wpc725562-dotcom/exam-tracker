package com.wpc725562.examtracker.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Clock;

/**
 * AI 层的装配。
 *
 * <h3>为什么手工构建模型，而不是用 Spring AI 的自动配置</h3>
 *
 * <p>实测（不是推测）：只要 {@code spring-ai-starter-model-openai} 在 classpath 上，
 * 且没有配置 {@code spring.ai.openai.api-key}，应用就会在启动阶段抛
 * {@code IllegalArgumentException: OpenAI API key must be set}。
 * 而且它会对 <b>六种</b>模型分别检查（chat / embedding / image / moderation /
 * 语音合成 / 语音转写）—— 只要有一个没 key 就整个起不来。
 *
 * <p>本项目只用得到 chat，而且 <b>没有 key 的环境（CI、别人 clone 下来）必须能正常启动</b>。
 * 所以那六个自动配置全部在 {@code application.yml} 里显式关掉
 * （{@code spring.ai.model.*: none}），这里手工构建唯一需要的那一个。
 *
 * <p>副作用是好的：连接信息全部落在 {@code app.ai.*}，和 {@code app.jwt} /
 * {@code app.cors} 一致，不需要在两套前缀之间来回看。
 */
@Configuration
public class AiConfig {

    private static final Logger log = LoggerFactory.getLogger(AiConfig.class);

    /**
     * 会话记忆。
     *
     * <p>拆成两半：<b>窗口裁剪</b>交给 Spring AI 的 {@link MessageWindowChatMemory}
     * （它有工具消息必须成对保留这类边界处理，自己重写容易出错），
     * <b>过期清理</b>由 {@link ExpiringChatMemoryRepository} 负责。
     * 各管一件事，也好分别测试。
     *
     * <p>这个 Bean <b>不</b>依赖模型，所以即使 AI 未启用也会创建 ——
     * 它只是个内存容器，没有外部依赖，不会影响启动。
     */
    @Bean
    public ChatMemory chatMemory(AiProperties props) {
        var repository = new ExpiringChatMemoryRepository(props.sessionTtl(), Clock.systemDefaultZone());
        log.info("[AI] 会话记忆：内存态，保留最近 {} 条消息，空闲 {} 后过期",
                props.maxHistoryMessages(), props.sessionTtl());
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(repository)
                .maxMessages(props.maxHistoryMessages())
                .build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "app.ai", name = "enabled", havingValue = "true")
    public OpenAiApi openAiApi(AiProperties props) {
        requireApiKey(props);

        // 显式设置超时。不设的话用的是 JDK HttpClient 的默认值（连接/读取都没有上限），
        // 上游挂住时这个请求会一直占着 Tomcat 的工作线程。
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(props.timeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(props.timeout());

        log.info("[AI] 已启用。端点={} 模型={} 单次请求超时={} key={}",
                props.baseUrl(), props.model(), props.timeout(), maskKey(props.apiKey()));

        // 注意：base-url 只写到域名，路径由 OpenAiApi 自己拼成 /v1/chat/completions。
        // DeepSeek 明确支持 https://api.deepseek.com/v1 这个前缀（官方说明：/v1 与模型版本无关，
        // 只是为了兼容 OpenAI SDK）。
        return OpenAiApi.builder()
                .baseUrl(props.baseUrl())
                .apiKey(props.apiKey())
                .restClientBuilder(RestClient.builder().requestFactory(requestFactory))
                .build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "app.ai", name = "enabled", havingValue = "true")
    public OpenAiChatModel openAiChatModel(OpenAiApi openAiApi, AiProperties props) {
        OpenAiChatOptions defaults = OpenAiChatOptions.builder()
                .model(props.model())
                .temperature(props.temperature())
                .build();

        return OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(defaults)
                // ★ 必须显式覆盖重试策略。
                //
                // Spring AI 的默认值是 RetryTemplate.maxAttempts(10)，指数退避
                // 起始 2 秒、倍率 5、上限 180 秒 —— 也就是说上游持续报错时，
                // 用户要等好几分钟才看到失败。对交互式问答接口完全不可接受。
                //
                // 这里直接关掉重试（maxAttempts=1）。理由：
                //   1. 一次问答本身就可能包含两轮 LLM 请求（工具调用 + 最终回答），
                //      再叠加重试，最坏耗时和费用都会翻倍；
                //   2. 重试一次「生成回答」的请求语义上很可疑 —— 钱已经花了，
                //      而且用户宁可立刻知道失败、自己再问一次。
                // 失败路径由上层转成明确的 503，见 ExamTrackerAiAssistant。
                .retryTemplate(RetryTemplate.builder().maxAttempts(1).build())
                .build();
    }

    /**
     * 把 {@link ChatClient} 和工具、记忆装配起来。
     *
     * <p>注意这里<b>不</b>注册任何工具：工具是<b>每次请求</b>注册的
     * （见 {@code ExamTrackerAiAssistant}），因为需要为每次请求包一层调用记录器。
     */
    @Bean
    @ConditionalOnProperty(prefix = "app.ai", name = "enabled", havingValue = "true")
    public ChatClient chatClient(OpenAiChatModel openAiChatModel, ChatMemory chatMemory) {
        return ChatClient.builder(openAiChatModel)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "app.ai", name = "enabled", havingValue = "true")
    public AiAssistant aiAssistant(ChatClient chatClient, ExamTrackerTools tools, AiProperties props) {
        ExamTrackerAiAssistant assistant = new ExamTrackerAiAssistant(chatClient, tools, props);
        log.info("[AI] 已注册工具：{}", assistant.registeredToolNames());
        return assistant;
    }

    /**
     * {@code enabled=true} 却没给 key 属于配置错误，<b>当场失败</b>比事后报 401 好。
     *
     * <p>只在这个分支里抛：默认 {@code enabled=false}，所以 CI、别人 clone 下来
     * 都不会走到这里，不会影响启动。
     */
    private static void requireApiKey(AiProperties props) {
        if (!props.usable()) {
            throw new IllegalStateException("""
                    app.ai.enabled=true 但 app.ai.api-key 为空，无法启用 AI 层。
                    请通过环境变量提供 key，例如：
                        export DEEPSEEK_API_KEY=sk-xxxxxxxx
                    （Windows cmd: set DEEPSEEK_API_KEY=sk-xxxxxxxx）
                    或者把 app.ai.enabled 改回 false，AI 接口会返回明确的 503 而不是让应用起不来。""");
        }
    }

    /** 日志里只留前后各 4 位。key 绝不能整串进日志 —— 日志会被收集、转发、贴进 issue。 */
    private static String maskKey(String apiKey) {
        if (apiKey == null || apiKey.length() <= 8) {
            return "****";
        }
        return apiKey.substring(0, 4) + "…" + apiKey.substring(apiKey.length() - 4);
    }
}
