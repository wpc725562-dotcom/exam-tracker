package com.wpc725562.examtracker.service;

import com.wpc725562.examtracker.domain.Priority;
import com.wpc725562.examtracker.domain.Task;
import com.wpc725562.examtracker.domain.TaskStatus;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 任务查询条件的组装。
 *
 * <p>每个方法在参数为空时返回 {@code null}，Spring Data 的
 * {@code Specification.and(null)} 会安全地忽略它 —— 这样调用处可以无脑串联，
 * 不用为每个可选条件写一层 if。
 *
 * <p><b>每个 Specification 都用 lambda 现构造，而不是静态常量。</b>
 * Criteria 的 {@code Predicate} 是绑定到当次 {@code CriteriaBuilder} 的，
 * 把 Specification 缓存成静态字段会在并发查询时串味。
 */
public final class TaskSpecifications {

    private TaskSpecifications() {
    }

    /**
     * 数据隔离 —— **所有查询都必须带上它**。
     *
     * <p>这是本项目防越权的第一道闸：即使某个接口忘了校验，只要查询走了这里，
     * 也只会查到自己的数据。
     */
    public static Specification<Task> ownedBy(Long userId) {
        return (root, query, cb) -> cb.equal(root.get("userId"), userId);
    }

    public static Specification<Task> planDateFrom(LocalDate from) {
        return from == null ? null : (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("planDate"), from);
    }

    public static Specification<Task> planDateTo(LocalDate to) {
        return to == null ? null : (root, query, cb) -> cb.lessThanOrEqualTo(root.get("planDate"), to);
    }

    public static Specification<Task> subjectId(Long subjectId) {
        return subjectId == null ? null : (root, query, cb) -> cb.equal(root.get("subjectId"), subjectId);
    }

    public static Specification<Task> status(TaskStatus status) {
        return status == null ? null : (root, query, cb) -> cb.equal(root.get("status"), status);
    }

    public static Specification<Task> priority(Priority priority) {
        return priority == null ? null : (root, query, cb) -> cb.equal(root.get("priority"), priority);
    }

    /**
     * 标题模糊匹配。
     *
     * <p>关键词里的 {@code %} 和 {@code _} 会被转义掉 —— 不转义的话，
     * 用户搜一个 {@code %} 就等于搜「全部」，看起来像功能坏了。
     * 这里用 Criteria 的 {@code like} 配合手工转义字符实现。
     */
    public static Specification<Task> titleContains(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return null;
        }
        String escaped = keyword.trim()
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return (root, query, cb) -> {
            List<Predicate> or = new ArrayList<>();
            or.add(cb.like(root.get("title"), "%" + escaped + "%", '\\'));
            or.add(cb.like(root.get("note"), "%" + escaped + "%", '\\'));
            return cb.or(or.toArray(new Predicate[0]));
        };
    }
}
