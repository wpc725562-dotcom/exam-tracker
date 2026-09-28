package com.wpc725562.examtracker.common;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/**
 * 分页结果。
 *
 * <p>不直接把 Spring Data 的 {@code Page} 序列化出去，有两个原因：
 * <ol>
 *   <li>{@code Page} 的 JSON 结构随 Spring Data 版本变过（{@code content} / {@code pageable}
 *       这些字段名不是稳定契约），直接暴露等于把框架的内部结构当成对外 API；</li>
 *   <li>它带着一大堆前端用不到的字段（{@code pageable}、{@code sort}、{@code first}…）。</li>
 * </ol>
 *
 * @param items      当前页数据
 * @param page       当前页码，**从 1 开始**（Spring Data 内部从 0 开始，在这里转换）
 * @param size       每页条数
 * @param total      总条数
 * @param totalPages 总页数
 */
@Schema(description = "分页结果")
public record PageResult<T>(
        @Schema(description = "当前页数据") List<T> items,
        @Schema(description = "当前页码（从 1 开始）", example = "1") int page,
        @Schema(description = "每页条数", example = "20") int size,
        @Schema(description = "总条数", example = "137") long total,
        @Schema(description = "总页数", example = "7") int totalPages
) {

    /**
     * 把 Spring Data 的 {@code Page<E>} 转成本结构，并顺带完成实体 → DTO 的映射。
     *
     * <p>把映射函数作为参数传进来（而不是先 map 再 of），是为了让「分页元信息」
     * 和「数据映射」在一处完成 —— 否则每个调用点都要写两遍同样的样板。
     */
    public static <E, T> PageResult<T> of(Page<E> page, Function<E, T> mapper) {
        return new PageResult<>(
                page.getContent().stream().map(mapper).toList(),
                page.getNumber() + 1,
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages()
        );
    }

    /** 不分页但需要同样外壳时用（比如「返回全部科目」）。 */
    public static <T> PageResult<T> ofAll(List<T> items) {
        return new PageResult<>(items, 1, items.size(), items.size(), 1);
    }
}
