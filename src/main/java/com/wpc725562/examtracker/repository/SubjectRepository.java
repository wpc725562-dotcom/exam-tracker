package com.wpc725562.examtracker.repository;

import com.wpc725562.examtracker.domain.Subject;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SubjectRepository extends JpaRepository<Subject, Long> {

    /**
     * 列出某用户的全部科目。
     *
     * <p>排序里加了 {@code id} 作为兜底：{@code sort_order} 允许重复
     * （用户拖动排序时很可能拖出两个相同的值），只按它排序的话
     * 每次查询顺序可能不一样，分页会漏数据或重复数据。
     * 带上唯一列做 tie-breaker 才能得到稳定顺序。
     */
    List<Subject> findByUserIdOrderBySortOrderAscIdAsc(Long userId);

    /**
     * 按 id + userId 查 —— 注意**永远带 userId**。
     *
     * <p>这是本项目所有「按 id 查」的统一写法。只写 {@code findById(id)} 的话，
     * A 用户就能通过猜 id 读到 B 用户的数据（越权，OWASP 里叫 IDOR）。
     * 把归属条件放进查询本身，比在 Service 里事后判断更不容易漏。
     */
    Optional<Subject> findByIdAndUserId(Long id, Long userId);

    boolean existsByUserIdAndName(Long userId, String name);

    long countByUserId(Long userId);
}
