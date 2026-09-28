package com.wpc725562.examtracker.common;

import org.springframework.data.domain.Sort;

import java.util.Set;

/**
 * 把外部传进来的排序参数转成 Spring Data 的 {@link Sort}。
 *
 * <p><b>为什么必须做白名单。</b>如果直接把 {@code sortBy} 拼进
 * {@code Sort.by(sortBy)}，调用方就能传任意属性名 —— 轻则报 500，
 * 重则能借此探测实体结构（比如传一个不存在的字段名，从错误信息里确认字段存在与否）。
 * 更糟的是有些项目会把 {@code sortBy} 直接拼进原生 SQL，那就是注入口子。
 *
 * <p>另一个理由是**稳定**：不认识的字段应该明确报错，而不是静默忽略 ——
 * 静默忽略会让前端以为自己传对了，排序为什么没生效要查很久。
 */
public final class SortResolver {

    private SortResolver() {
    }

    /** 允许排序的字段白名单。要和前端约定好，新增字段时同步加进来。 */
    private static final Set<String> ALLOWED_FIELDS = Set.of(
            "planDate", "planMinutes", "priority", "status", "createdAt", "updatedAt", "id"
    );

    public static final String ASC = "asc";
    public static final String DESC = "desc";

    /**
     * @param sortBy    排序字段，必须在白名单里；为空则用默认排序
     * @param direction {@code asc} / {@code desc}，不区分大小写；为空则按 {@code asc}
     * @param fallback  没有指定排序时使用的默认排序
     * @throws BusinessException 字段不在白名单里（转成 400，而不是 500）
     */
    public static Sort resolve(String sortBy, String direction, Sort fallback) {
        if (sortBy == null || sortBy.isBlank()) {
            return fallback;
        }

        String field = sortBy.trim();
        if (!ALLOWED_FIELDS.contains(field)) {
            // 用 BusinessException 而不是 IllegalArgumentException：
            // 「传了不支持的排序字段」是**客户端错误**，应该返回 400。
            // 抛 IllegalArgumentException 会被全局兜底处理器当成未预期异常，返回 500 ——
            // 那会让前端以为是自己没传对，而监控里又多出一条假的服务器错误。
            throw BusinessException.invalidParam(
                    "不支持的排序字段：" + field + "。可用字段：" + ALLOWED_FIELDS.stream().sorted().toList());
        }

        Sort.Direction dir = DESC.equalsIgnoreCase(direction == null ? "" : direction.trim())
                ? Sort.Direction.DESC
                : Sort.Direction.ASC;

        // 追加 id 作为 tie-breaker：排序字段有重复值时（比如同一天有很多任务），
        // 不追加会导致翻页时同一条数据出现在两页里，或者某条数据一次都不出现。
        return Sort.by(dir, field).and(Sort.by(Sort.Direction.ASC, "id"));
    }
}
