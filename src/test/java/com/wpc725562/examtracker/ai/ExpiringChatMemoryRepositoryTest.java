package com.wpc725562.examtracker.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 带过期的内存会话存储。
 *
 * <p>这个类存在的理由就是「Spring AI 自带的实现永不清理」，所以
 * <b>过期行为</b>是这里最该钉死的东西。用可推进的 {@link MutableClock}
 * 而不是 {@code Thread.sleep}：睡觉的测试又慢又飘，而且永远测不了
 * 「刚好在边界上过期」这种情况。
 */
@DisplayName("ExpiringChatMemoryRepository —— 带过期的会话存储")
class ExpiringChatMemoryRepositoryTest {

    private static final Duration TTL = Duration.ofMinutes(30);

    /** 可手工推进的时钟。 */
    private static final class MutableClock extends Clock {

        private Instant now = Instant.parse("2026-09-29T10:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }
    }

    private final MutableClock clock = new MutableClock();
    private final ExpiringChatMemoryRepository repo = new ExpiringChatMemoryRepository(TTL, clock);

    private static Message msg(String text) {
        return new UserMessage(text);
    }

    @Test
    @DisplayName("存进去能原样读出来")
    void saveThenFind() {
        repo.saveAll("c1", List.of(msg("你好"), msg("第二句")));

        List<Message> got = repo.findByConversationId("c1");
        assertThat(got).hasSize(2);
        assertThat(got.get(0).getText()).isEqualTo("你好");
    }

    @Test
    @DisplayName("不存在的会话返回空列表，不是 null")
    void unknownConversationReturnsEmpty() {
        assertThat(repo.findByConversationId("nope")).isEmpty();
    }

    @Test
    @DisplayName("★ 空闲超过 TTL 后过期，读出来是空的")
    void expiresAfterTtl() {
        repo.saveAll("c1", List.of(msg("你好")));

        clock.advance(TTL.minusSeconds(1));
        assertThat(repo.findByConversationId("c1"))
                .as("TTL 之内还没过期")
                .hasSize(1);

        clock.advance(Duration.ofSeconds(2));
        assertThat(repo.findByConversationId("c1"))
                .as("超过 TTL 后应该读到空")
                .isEmpty();
    }

    @Test
    @DisplayName("★ 每次写入都会刷新过期时间（滑动过期）—— 活跃会话不会被清掉")
    void ttlSlidesOnWrite() {
        repo.saveAll("c1", List.of(msg("第一句")));

        // 每次快到 30 分钟就再写一次，模拟用户一直在聊
        for (int i = 0; i < 5; i++) {
            clock.advance(TTL.minusMinutes(1));
            repo.saveAll("c1", List.of(msg("第 " + (i + 2) + " 句")));
        }

        assertThat(repo.findByConversationId("c1"))
                .as("总经过时间 145 分钟，但每次都刷新了，所以不该过期")
                .hasSize(1);
    }

    @Test
    @DisplayName("★ 过期边界：正好等于 TTL 时就算过期（否则 ttl=0 会永不过期）")
    void expiresExactlyAtTtl() {
        repo.saveAll("c1", List.of(msg("你好")));
        clock.advance(TTL);
        assertThat(repo.findByConversationId("c1")).isEmpty();
    }

    @Test
    @DisplayName("过期后惰性清理会把它从 store 里真正删掉")
    void expiredEntryIsEvicted() {
        repo.saveAll("c1", List.of(msg("你好")));
        assertThat(repo.liveConversationCount()).isEqualTo(1);

        clock.advance(TTL);
        assertThat(repo.liveConversationCount()).as("计数时顺带清理").isZero();
        assertThat(repo.findConversationIds()).isEmpty();
    }

    @Test
    @DisplayName("会话之间互相隔离")
    void conversationsAreIsolated() {
        repo.saveAll("c1", List.of(msg("甲的对话")));
        repo.saveAll("c2", List.of(msg("乙的对话")));

        assertThat(repo.findByConversationId("c1")).hasSize(1);
        assertThat(repo.findByConversationId("c2")).hasSize(1);
        assertThat(repo.findByConversationId("c1").get(0).getText()).isEqualTo("甲的对话");
        assertThat(repo.findConversationIds()).containsExactlyInAnyOrder("c1", "c2");
    }

    @Test
    @DisplayName("deleteByConversationId 立即删除")
    void delete() {
        repo.saveAll("c1", List.of(msg("你好")));
        repo.deleteByConversationId("c1");
        assertThat(repo.findByConversationId("c1")).isEmpty();
        assertThat(repo.liveConversationCount()).isZero();
    }

    @Test
    @DisplayName("★ 存入的是不可变副本：调用方之后改列表不会污染历史")
    void storedListIsDefensiveCopy() {
        List<Message> mutable = new ArrayList<>();
        mutable.add(msg("第一句"));
        repo.saveAll("c1", mutable);

        // 模拟 MessageWindowChatMemory 之后复用同一个列表对象
        mutable.add(msg("偷偷加进去的"));
        mutable.clear();

        assertThat(repo.findByConversationId("c1"))
                .as("历史记录不应受外部列表变动影响")
                .hasSize(1);
        assertThat(repo.findByConversationId("c1").get(0).getText()).isEqualTo("第一句");
    }

    @Test
    @DisplayName("存空列表是允许的（会话存在但内容为空）")
    void emptyListAllowed() {
        repo.saveAll("c1", List.of());
        assertThat(repo.findByConversationId("c1")).isEmpty();
        assertThat(repo.liveConversationCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("TTL 为 0 时立刻过期（这条是上面「边界用 <=」的依据）")
    void zeroTtlExpiresImmediately() {
        ExpiringChatMemoryRepository zero = new ExpiringChatMemoryRepository(Duration.ZERO, clock);
        zero.saveAll("c1", List.of(msg("你好")));
        assertThat(zero.findByConversationId("c1")).isEmpty();
    }

    @Test
    @DisplayName("evictExpired 返回清掉的条数")
    void evictExpiredReturnsCount() {
        repo.saveAll("c1", List.of(msg("甲")));
        repo.saveAll("c2", List.of(msg("乙")));
        clock.advance(TTL.minusSeconds(1));
        repo.saveAll("c3", List.of(msg("丙")));

        clock.advance(Duration.ofSeconds(2));
        assertThat(repo.evictExpired()).as("c1 和 c2 过期，c3 还活着").isEqualTo(2);
        assertThat(repo.findConversationIds()).containsExactly("c3");
    }

    @Test
    @DisplayName("构造参数为 null 时应该快速失败")
    void nullArgumentsRejected() {
        assertThatThrownBy(() -> new ExpiringChatMemoryRepository(null, clock))
                .isInstanceOf(NullPointerException.class);
    }
}
