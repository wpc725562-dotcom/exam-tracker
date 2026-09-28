package com.wpc725562.examtracker.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 排序参数白名单的测试。
 *
 * <p>这个类是**安全边界**：如果白名单失效，调用方就能传任意属性名去探测实体结构。
 * 所以除了「正常路径」，重点测「非白名单必须被拒绝，而且是 400 不是 500」。
 */
@DisplayName("SortResolver —— 排序字段白名单")
class SortResolverTest {

    private static final Sort FALLBACK = Sort.by(Sort.Direction.DESC, "planDate");

    @Test
    @DisplayName("sortBy 为 null -> 原样返回兜底排序")
    void nullReturnsFallback() {
        assertThat(SortResolver.resolve(null, "desc", FALLBACK)).isEqualTo(FALLBACK);
    }

    @Test
    @DisplayName("sortBy 为空白串 -> 原样返回兜底排序")
    void blankReturnsFallback() {
        assertThat(SortResolver.resolve("", "desc", FALLBACK)).isEqualTo(FALLBACK);
        assertThat(SortResolver.resolve("   ", "desc", FALLBACK)).isEqualTo(FALLBACK);
    }

    @Test
    @DisplayName("desc -> 指定字段降序，并追加 id 升序作为 tie-breaker")
    void descWithTieBreaker() {
        Sort sort = SortResolver.resolve("planMinutes", "desc", FALLBACK);

        assertThat(sort.getOrderFor("planMinutes")).isNotNull();
        assertThat(sort.getOrderFor("planMinutes").getDirection()).isEqualTo(Sort.Direction.DESC);

        // ★ tie-breaker 是「翻页不漏数据」的前提：排序字段有重复值时，
        //   没有唯一列兜底，同一条记录可能出现在两页里、或一次都不出现。
        assertThat(sort.getOrderFor("id")).isNotNull();
        assertThat(sort.getOrderFor("id").getDirection()).isEqualTo(Sort.Direction.ASC);
        assertThat(sort.stream()).hasSize(2);
    }

    @Test
    @DisplayName("方向大小写不敏感，且容忍首尾空格")
    void directionIsCaseInsensitiveAndTrimmed() {
        assertThat(SortResolver.resolve("planMinutes", "DESC", FALLBACK)
                .getOrderFor("planMinutes").getDirection()).isEqualTo(Sort.Direction.DESC);
        assertThat(SortResolver.resolve("planMinutes", "DeSc", FALLBACK)
                .getOrderFor("planMinutes").getDirection()).isEqualTo(Sort.Direction.DESC);
        assertThat(SortResolver.resolve("planMinutes", " desc ", FALLBACK)
                .getOrderFor("planMinutes").getDirection()).isEqualTo(Sort.Direction.DESC);
    }

    @Test
    @DisplayName("方向为空或不认识的值 -> 退化成 asc（而不是报错）")
    void unknownDirectionFallsBackToAsc() {
        assertThat(SortResolver.resolve("planMinutes", null, FALLBACK)
                .getOrderFor("planMinutes").getDirection()).isEqualTo(Sort.Direction.ASC);
        assertThat(SortResolver.resolve("planMinutes", "", FALLBACK)
                .getOrderFor("planMinutes").getDirection()).isEqualTo(Sort.Direction.ASC);
        assertThat(SortResolver.resolve("planMinutes", "ascending", FALLBACK)
                .getOrderFor("planMinutes").getDirection()).isEqualTo(Sort.Direction.ASC);
    }

    @Test
    @DisplayName("字段名首尾空格会被去掉")
    void fieldNameIsTrimmed() {
        Sort sort = SortResolver.resolve("  planDate  ", "asc", FALLBACK);
        assertThat(sort.getOrderFor("planDate")).isNotNull();
    }

    @Test
    @DisplayName("★ 非白名单字段 -> BusinessException(INVALID_PARAM)，对应 HTTP 400")
    void nonWhitelistedFieldIsRejected() {
        // 曾经这里抛的是 IllegalArgumentException，被全局兜底处理器当成未预期异常，
        // 于是客户端传错参数却收到 500 —— 前端会以为是服务端挂了，监控里还多一条假故障。
        assertThatThrownBy(() -> SortResolver.resolve("passwordHash", "asc", FALLBACK))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("passwordHash")
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_PARAM));
    }

    @Test
    @DisplayName("错误信息里会列出可用字段，便于调用方自查")
    void errorMessageListsAllowedFields() {
        assertThatThrownBy(() -> SortResolver.resolve("nope", "asc", FALLBACK))
                .hasMessageContaining("可用字段")
                .hasMessageContaining("planDate")
                .hasMessageContaining("planMinutes");
    }

    @Test
    @DisplayName("白名单里的每个字段都能用")
    void allDocumentedFieldsAreAllowed() {
        for (String field : new String[]{
                "planDate", "planMinutes", "priority", "status", "createdAt", "updatedAt", "id"}) {
            assertThat(SortResolver.resolve(field, "asc", FALLBACK).getOrderFor(field))
                    .as("字段 %s 应该在白名单里", field)
                    .isNotNull();
        }
    }

    @Test
    @DisplayName("白名单是精确匹配，不做前缀/大小写放行")
    void whitelistIsExactMatch() {
        assertThatThrownBy(() -> SortResolver.resolve("plan", "asc", FALLBACK))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> SortResolver.resolve("PLANDATE", "asc", FALLBACK))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> SortResolver.resolve("planDate,passwordHash", "asc", FALLBACK))
                .isInstanceOf(BusinessException.class);
    }
}
