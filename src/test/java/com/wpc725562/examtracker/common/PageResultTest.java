package com.wpc725562.examtracker.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 分页结果外壳的测试。
 *
 * <p>重点是**页码的 0 基 / 1 基转换**：Spring Data 内部从 0 开始，
 * 对外 API 从 1 开始。转换只在一处做，所以这一处必须是对的 ——
 * 错一位的表现是「第 1 页的数据在传 page=2 时返回」，非常隐蔽。
 */
@DisplayName("PageResult —— 分页外壳")
class PageResultTest {

    @Test
    @DisplayName("★ Spring Data 的 0 基页码会转换成对外的 1 基页码")
    void pageNumberIsConvertedTo1Based() {
        // 第 3 页（Spring Data 内部 number=2），每页 2 条，共 6 条。
        //
        // 注意 PageImpl 有个不直观的行为：如果 offset + pageSize > total，
        // 它会把 total **向上修正**成 offset + content.size()。
        // 用 total=5 配第 3 页（offset=4）时，5 会被改成 6 —— 断言就永远对不上。
        // 这里用自洽的参数，避免把 PageImpl 的怪癖当成我们的 bug。
        Page<String> springPage = new PageImpl<>(
                List.of("e", "f"), PageRequest.of(2, 2), 6);

        PageResult<String> result = PageResult.of(springPage, s -> s.toUpperCase());

        assertThat(result.page()).isEqualTo(3);
        assertThat(result.size()).isEqualTo(2);
        assertThat(result.total()).isEqualTo(6);
        assertThat(result.totalPages()).isEqualTo(3);
    }

    @Test
    @DisplayName("映射函数会被应用到当前页的每一条")
    void mapperIsAppliedToContent() {
        Page<Integer> springPage = new PageImpl<>(List.of(1, 2, 3), PageRequest.of(0, 10), 3);

        PageResult<String> result = PageResult.of(springPage, i -> "第" + i + "条");

        assertThat(result.items()).containsExactly("第1条", "第2条", "第3条");
        assertThat(result.page()).isEqualTo(1);
    }

    @Test
    @DisplayName("空页不会抛异常，元信息仍然正确")
    void emptyPage() {
        Page<String> springPage = new PageImpl<>(List.of(), PageRequest.of(0, 20), 0);

        PageResult<String> result = PageResult.of(springPage, s -> s);

        assertThat(result.items()).isEmpty();
        assertThat(result.total()).isZero();
        assertThat(result.totalPages()).isZero();
        assertThat(result.page()).isEqualTo(1);
    }

    @Test
    @DisplayName("ofAll：不分页但需要同样外壳时，page/size/totalPages 自洽")
    void ofAll() {
        PageResult<String> result = PageResult.ofAll(List.of("a", "b", "c"));

        assertThat(result.items()).hasSize(3);
        assertThat(result.page()).isEqualTo(1);
        assertThat(result.size()).isEqualTo(3);
        assertThat(result.total()).isEqualTo(3);
        assertThat(result.totalPages()).isEqualTo(1);
    }

    @Test
    @DisplayName("ofAll：空列表也不会出现 size=0 以外的怪值")
    void ofAllEmpty() {
        PageResult<String> result = PageResult.ofAll(List.of());

        assertThat(result.items()).isEmpty();
        assertThat(result.size()).isZero();
        assertThat(result.totalPages()).isEqualTo(1);
    }
}
