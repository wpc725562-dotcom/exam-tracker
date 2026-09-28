package com.wpc725562.examtracker.ai;

import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 带过期时间的内存会话存储。
 *
 * <p><b>为什么不直接用 Spring AI 自带的 {@code InMemoryChatMemoryRepository}：</b>
 * 它是一个裸的 {@code ConcurrentHashMap}，<b>永不清理</b>。在一个长期运行的服务里，
 * 每个访客、每段对话都会在里面留下一份记录，直到进程重启 —— 这是典型的内存泄漏，
 * 而且它长得完全不像 bug：功能一切正常，只是 RSS 曲线一路向上。
 *
 * <p>这里在存储层补上「空闲过期」：每次写入刷新过期时间，读到时若已过期就丢弃。
 * 过期策略是 <b>滑动</b>的（活跃会话不会被清掉），清理是<b>惰性</b>的
 * （读/写时顺带清，外加一个定时任务兜底），不引入额外线程去扫全表。
 *
 * <p><b>为什么只做存储层、不自己实现整个 {@code ChatMemory}：</b>
 * 「保留最近 N 条消息」的窗口裁剪逻辑由 Spring AI 的
 * {@code MessageWindowChatMemory} 负责，那部分逻辑有边界情况（工具调用消息必须成对保留），
 * 自己重写容易出错。这里只替换「存到哪、什么时候扔」，各管一件事。
 *
 * <p>实现 {@link ChatMemoryRepository} 要求的线程安全：底层是 {@code ConcurrentHashMap}，
 * 存入的列表都是不可变副本，不存在调用方拿到列表后再改动它的情况。
 */
public class ExpiringChatMemoryRepository implements ChatMemoryRepository {

    /**
     * 一条会话记录：消息列表 + 过期时刻。
     *
     * <p>{@code expiresAt} 用 {@code volatile} 而不是把整个 Entry 换成不可变对象：
     * 刷新过期时间时没必要重新分配一个列表副本。
     */
    private static final class Entry {

        private final List<Message> messages;
        private volatile Instant expiresAt;

        private Entry(List<Message> messages, Instant expiresAt) {
            this.messages = messages;
            this.expiresAt = expiresAt;
        }
    }

    private final Map<String, Entry> store = new ConcurrentHashMap<>();

    private final Duration ttl;
    private final Clock clock;

    public ExpiringChatMemoryRepository(Duration ttl, Clock clock) {
        // 显式校验而不是让它在第一次写入时才 NPE：
        // 「过期策略没配」是启动期的配置错误，应该当场说清楚，
        // 而不是等到某个用户发了第一句话才炸在一个看不懂的堆栈里。
        this.ttl = Objects.requireNonNull(ttl, "ttl 不能为空");
        this.clock = Objects.requireNonNull(clock, "clock 不能为空");
    }

    @Override
    public List<String> findConversationIds() {
        evictExpired();
        return List.copyOf(store.keySet());
    }

    @Override
    public List<Message> findByConversationId(String conversationId) {
        Entry entry = store.get(conversationId);
        if (entry == null) {
            return List.of();
        }
        if (isExpired(entry)) {
            // 带值删除：万一这一瞬间有人刚写了新内容，就不会误删那一份
            store.remove(conversationId, entry);
            return List.of();
        }
        return entry.messages;
    }

    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        // List.copyOf 而不是直接存：调用方（MessageWindowChatMemory）之后可能复用/改动那个列表，
        // 直接存引用会让「历史记录」跟着一起变。
        store.put(conversationId, new Entry(List.copyOf(messages), clock.instant().plus(ttl)));
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        store.remove(conversationId);
    }

    /** 主动清掉已过期的会话。定时任务和监控用；读路径上的惰性清理不依赖它。 */
    public int evictExpired() {
        int before = store.size();
        store.entrySet().removeIf(e -> isExpired(e.getValue()));
        return before - store.size();
    }

    /** 当前存活的会话数。仅用于监控与测试断言。 */
    public int liveConversationCount() {
        evictExpired();
        return store.size();
    }

    private boolean isExpired(Entry entry) {
        // 到期时刻「已经到达或已过」即视为过期（<= 而不是 <）：
        // 用 < 的话，ttl=0 的配置会让记录永远不过期。
        return !clock.instant().isBefore(entry.expiresAt);
    }
}
