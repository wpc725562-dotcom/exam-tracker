package com.wpc725562.examtracker.repository;

import com.wpc725562.examtracker.domain.Task;
import com.wpc725562.examtracker.domain.TaskStatus;
import com.wpc725562.examtracker.repository.projection.SubjectCount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 任务仓储。
 *
 * <p>继承了 {@link JpaSpecificationExecutor}，用于「按日期 / 科目 / 状态 / 关键词」
 * 这几个**可选**条件的组合查询。用 Specification 而不是写 8 个
 * {@code findByXxxAndYyyAndZzz}：可选条件一多，派生方法名会爆炸，
 * 而且每加一个筛选维度都要新增一批方法。
 */
public interface TaskRepository extends JpaRepository<Task, Long>, JpaSpecificationExecutor<Task> {

    /** 同样带 userId，防越权（见 {@link SubjectRepository#findByIdAndUserId} 的说明）。 */
    Optional<Task> findByIdAndUserId(Long id, Long userId);

    long countByUserId(Long userId);

    long countByUserIdAndStatus(Long userId, TaskStatus status);

    long countByUserIdAndPlanDate(Long userId, LocalDate planDate);

    long countByUserIdAndPlanDateAndStatus(Long userId, LocalDate planDate, TaskStatus status);

    long countByUserIdAndSubjectId(Long userId, Long subjectId);

    /** 某科目下是否还有未完成的任务 —— 删科目前的检查。 */
    long countByUserIdAndSubjectIdAndStatus(Long userId, Long subjectId, TaskStatus status);

    /** 某一天的计划投入总分钟数（仪表盘「今日计划」用）。 */
    @Query("""
            select coalesce(sum(t.planMinutes), 0)
            from Task t
            where t.userId = :userId and t.planDate = :planDate
            """)
    long sumPlanMinutesByDate(@Param("userId") Long userId, @Param("planDate") LocalDate planDate);

    @Query("""
            select new com.wpc725562.examtracker.repository.projection.SubjectCount(t.subjectId, count(t))
            from Task t
            where t.userId = :userId
            group by t.subjectId
            """)
    List<SubjectCount> countGroupBySubject(@Param("userId") Long userId);

    @Query("""
            select new com.wpc725562.examtracker.repository.projection.SubjectCount(t.subjectId, count(t))
            from Task t
            where t.userId = :userId and t.status = :status
            group by t.subjectId
            """)
    List<SubjectCount> countGroupBySubjectAndStatus(@Param("userId") Long userId,
                                                    @Param("status") TaskStatus status);

    /**
     * 批量删除某科目下的全部任务。
     *
     * <p>用 JPQL 批量删除而不是派生方法 {@code deleteByUserIdAndSubjectId}：
     * 派生方法是「先查出所有实体、再逐个 remove」，1000 个任务就是 1001 条 SQL。
     * 批量删除只发一条。
     *
     * <p>注意批量删除**绕过持久化上下文**，不会触发实体的生命周期回调，
     * 也不会更新已经在内存里的实体状态。本项目在删除后不再使用这些实体，所以安全。
     */
    @Modifying
    @Query("delete from Task t where t.userId = :userId and t.subjectId = :subjectId")
    int deleteByUserIdAndSubjectId(@Param("userId") Long userId, @Param("subjectId") Long subjectId);
}
