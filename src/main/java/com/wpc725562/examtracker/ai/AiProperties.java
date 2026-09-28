package com.wpc725562.examtracker.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * AI 层配置，绑定 {@code app.ai.*}。
 *
 * <p>沿用项目里 {@code app.jwt} / {@code app.cors} 的写法：record + 构造器绑定，
 * 注入后不可变。这样「模型名被某个 Service 顺手改掉」这种事在类型上就不可能发生。
 *
 * <p><b>为什么连接信息放在 {@code app.ai.*} 而不是 Spring AI 自带的
 * {@code spring.ai.openai.*}：</b>因为 {@code spring.ai.openai.*} 一旦被自动配置读到，
 * 它会去实例化 <b>六种</b>模型（chat / embedding / image / moderation / 语音合成 / 语音转写），
 * 每一个都要求 API key 非空，缺一个就 {@code IllegalArgumentException} 让整个应用起不来。
 * 本项目只用得到 chat，而且**没有 key 时也必须能正常启动**（CI 里就没有 key）。
 * 所以那六个自动配置全部显式关掉（见 {@code application.yml} 的 {@code spring.ai.model.*}），
 * 由 {@link AiConfig} 手工构建唯一需要的那一个。
 *
 * @param enabled             是否启用 AI 层。默认 <b>false</b> —— 没有 key 的环境（CI、别人 clone 下来）
 *                            必须能正常启动，AI 接口返回 503 而不是把应用拖挂
 * @param baseUrl             OpenAI 兼容端点。DeepSeek 是 {@code https://api.deepseek.com}
 * @param apiKey              API key。**只能来自环境变量**，绝不写进仓库
 * @param model               模型名。DeepSeek 当前为 {@code deepseek-flash}
 * @param timeout             单次 HTTP 请求超时。注意一次问答可能包含「工具调用 → 再问一次」
 *                            两轮请求，所以最坏耗时约为此值的两倍。
 *                            默认 {@code 45s}：这个值是<b>实测调出来的</b> ——
 *                            最早设的是 20s，结果在一次连续验证里真的超时了一次
 *                            （见 README「AI 层」一节的记录）。带工具调用的 LLM 请求
 *                            偶尔会明显慢于平均值，20s 的余量不够。
 * @param temperature         采样温度。查数据场景要的是稳定复现，默认 0
 * @param maxHistoryMessages  多轮对话保留的最大消息条数（含工具消息）。超出按窗口裁剪
 * @param sessionTtl          会话空闲多久后过期。内存态实现，进程重启即丢
 */
@ConfigurationProperties(prefix = "app.ai")
public record AiProperties(

        @DefaultValue("false") boolean enabled,

        @DefaultValue("https://api.deepseek.com") String baseUrl,

        @DefaultValue("") String apiKey,

        @DefaultValue("deepseek-flash") String model,

        @DefaultValue("45s") Duration timeout,

        @DefaultValue("0.0") double temperature,

        @DefaultValue("20") int maxHistoryMessages,

        @DefaultValue("30m") Duration sessionTtl
) {

    /**
     * 是否具备真正调用模型的条件：开关打开 <b>且</b> key 非空。
     *
     * <p>刻意和 {@link #enabled} 分开：{@code enabled=true} 但忘了给 key 是**配置错误**，
     * 应该在启动日志里明确警告，而不是等用户点了按钮才收到一个看不懂的 401。
     */
    public boolean usable() {
        return enabled && apiKey != null && !apiKey.isBlank();
    }
}
