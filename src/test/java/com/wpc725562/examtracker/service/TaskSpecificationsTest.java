package com.wpc725562.examtracker.service;

import com.wpc725562.examtracker.domain.Task;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyChar;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 查询条件组装的测试。
 *
 * <p>Criteria API 的 mock 写起来啰嗦，但这里值得：{@code titleContains} 里的
 * **通配符转义**是一个「写错了也看不出来」的地方 ——
 * 用户搜 {@code %} 会匹配到全部数据，界面上看起来只是「搜索结果有点多」，
 * 不会报任何错。真实接口的行为由端到端脚本覆盖，这里补的是纯逻辑的精确断言。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TaskSpecifications —— 查询条件组装")
class TaskSpecificationsTest {

    @Mock
    private CriteriaBuilder cb;
    @Mock
    private CriteriaQuery<?> query;
    @Mock
    private Root<Task> root;
    @Mock
    private Path<String> titlePath;
    @Mock
    private Path<String> notePath;
    @Mock
    private Predicate predicate;

    private static final String ESCAPE = "\\";

    @Nested
    @DisplayName("数据隔离")
    class DataIsolation {

        @Test
        @DisplayName("★ ownedBy 会把 userId 写进查询条件（防越权第一道闸）")
        void ownedByConstrainsUserId() {
            @SuppressWarnings("unchecked")
            Path<Object> userIdPath = org.mockito.Mockito.mock(Path.class);
            when(root.<Object>get("userId")).thenReturn(userIdPath);
            when(cb.equal(userIdPath, 42L)).thenReturn(predicate);

            Predicate result = TaskSpecifications.ownedBy(42L).toPredicate(root, query, cb);

            assertThat(result).isSameAs(predicate);
            verify(cb).equal(userIdPath, 42L);
        }
    }

    @Nested
    @DisplayName("关键词模糊匹配的通配符转义")
    class TitleEscaping {

        /**
         * 执行 titleContains，并返回传给 cb.like 的两个 pattern（title / note 各一个）。
         *
         * <p><b>关于 {@code cb.or} 的 stub 写法：</b>{@code CriteriaBuilder.or(Predicate...)}
         * 是**可变参数**方法，而生产代码传的是一个 {@code Predicate[]} 数组。
         * 如果按两个独立参数去 stub（{@code cb.or(any(Predicate.class), any(Predicate.class))}），
         * Mockito 会看到「期望 2 个参数、实际 1 个数组参数」而判定不匹配，
         * 直接抛 PotentialStubbingProblem —— 报错信息里显示的是 {@code cb.or(null, null)}，
         * 很容易被误读成「传进去的是 null」。正确写法是按**一个数组参数**匹配。
         */
        private List<String> patternsFor(String keyword) {
            when(root.<String>get("title")).thenReturn(titlePath);
            when(root.<String>get("note")).thenReturn(notePath);
            when(cb.like(any(), anyString(), anyChar())).thenReturn(predicate);
            when(cb.or(any(Predicate[].class))).thenReturn(predicate);

            Specification<Task> spec = TaskSpecifications.titleContains(keyword);
            assertThat(spec).isNotNull();
            assertThat(spec.toPredicate(root, query, cb)).isSameAs(predicate);

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(cb, times(2)).like(any(), captor.capture(), eq('\\'));
            return captor.getAllValues();
        }

        @Test
        @DisplayName("★ 关键词里的 % 被转义 —— 否则「搜 % 」等于「搜全部」")
        void percentIsEscaped() {
            List<String> patterns = patternsFor("100%");

            assertThat(patterns).hasSize(2);
            assertThat(patterns).allSatisfy(p -> {
                assertThat(p).startsWith("%").endsWith("%");           // 外层通配符保留
                assertThat(p).contains(ESCAPE + "%");                  // 用户输入的 % 被转义
                assertThat(p).isEqualTo("%100" + ESCAPE + "%%");
            });
        }

        @Test
        @DisplayName("★ 关键词里的 _ 被转义 —— 否则一个下划线匹配任意字符")
        void underscoreIsEscaped() {
            List<String> patterns = patternsFor("a_b");

            assertThat(patterns).allSatisfy(p ->
                    assertThat(p).isEqualTo("%a" + ESCAPE + "_b%"));
        }

        @Test
        @DisplayName("★ 反斜杠本身也要转义，且必须在 % / _ 之前处理")
        void backslashIsEscapedFirst() {
            // 顺序错了会出问题：如果先替换 % 再替换 \，那 \ 会把刚插入的转义符再转义一次。
            List<String> patterns = patternsFor("a\\b");

            assertThat(patterns).allSatisfy(p ->
                    assertThat(p).isEqualTo("%a" + ESCAPE + ESCAPE + "b%"));
        }

        @Test
        @DisplayName("普通关键词原样透传，只加外层通配符")
        void plainKeyword() {
            assertThat(patternsFor("真题")).allSatisfy(p ->
                    assertThat(p).isEqualTo("%真题%"));
        }

        @Test
        @DisplayName("关键词首尾空格会被去掉")
        void keywordIsTrimmed() {
            assertThat(patternsFor("  真题  ")).allSatisfy(p ->
                    assertThat(p).isEqualTo("%真题%"));
        }

        @Test
        @DisplayName("同时匹配 title 和 note 两个字段（OR 里恰好两个条件）")
        void matchesTitleOrNote() {
            patternsFor("真题");

            ArgumentCaptor<Predicate[]> orArgs = ArgumentCaptor.forClass(Predicate[].class);
            verify(cb).or(orArgs.capture());
            assertThat(orArgs.getValue()).hasSize(2);
        }

        @Test
        @DisplayName("关键词为空/null -> 返回 null（Specification.and 会安全忽略）")
        void blankKeywordYieldsNull() {
            assertThat(TaskSpecifications.titleContains(null)).isNull();
            assertThat(TaskSpecifications.titleContains("")).isNull();
            assertThat(TaskSpecifications.titleContains("   ")).isNull();
        }
    }

    @Nested
    @DisplayName("可选条件为空时返回 null")
    class OptionalConditions {

        @Test
        @DisplayName("不传的条件一律返回 null，让调用处可以无脑串联")
        void nullInputsYieldNull() {
            assertThat(TaskSpecifications.planDateFrom(null)).isNull();
            assertThat(TaskSpecifications.planDateTo(null)).isNull();
            assertThat(TaskSpecifications.subjectId(null)).isNull();
            assertThat(TaskSpecifications.status(null)).isNull();
            assertThat(TaskSpecifications.priority(null)).isNull();
        }

        @Test
        @DisplayName("传了值的条件会返回可用的 Specification")
        void nonNullInputsYieldSpecification() {
            assertThat(TaskSpecifications.planDateFrom(LocalDate.of(2026, 9, 1))).isNotNull();
            assertThat(TaskSpecifications.planDateTo(LocalDate.of(2026, 9, 30))).isNotNull();
            assertThat(TaskSpecifications.subjectId(1L)).isNotNull();
            assertThat(TaskSpecifications.status(
                    com.wpc725562.examtracker.domain.TaskStatus.DONE)).isNotNull();
            assertThat(TaskSpecifications.priority(
                    com.wpc725562.examtracker.domain.Priority.HIGH)).isNotNull();
        }
    }
}
