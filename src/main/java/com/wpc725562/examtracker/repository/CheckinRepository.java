package com.wpc725562.examtracker.repository;

import com.wpc725562.examtracker.domain.Checkin;
import com.wpc725562.examtracker.repository.projection.DateMinutes;
import com.wpc725562.examtracker.repository.projection.SubjectLastCheckin;
import com.wpc725562.examtracker.repository.projection.SubjectMinutes;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface CheckinRepository extends JpaRepository<Checkin, Long> {

    Optional<Checkin> findByIdAndUserId(Long id, Long userId);

    List<Checkin> findByUserIdAndCheckinDateBetweenOrderByCheckinDateAscIdAsc(
            Long userId, LocalDate from, LocalDate to);

    /**
     * 分页查询（不带科目过滤）。
     *
     * <p>注意这里返回 {@code Page} 而不是 {@code List} —— {@code Page} 会额外发一条
     * {@code count} 查询，前端要显示「共 N 条」就必须要它。
     * 用 {@code List} 的话得自己再数一遍，既慢又不准。
     */
    Page<Checkin> findByUserIdAndCheckinDateBetween(
            Long userId, LocalDate from, LocalDate to, Pageable pageable);

    Page<Checkin> findByUserIdAndSubjectIdAndCheckinDateBetween(
            Long userId, Long subjectId, LocalDate from, LocalDate to, Pageable pageable);

    boolean existsByUserIdAndTaskId(Long userId, Long taskId);

    long countByUserId(Long userId);

    long countByUserIdAndSubjectId(Long userId, Long subjectId);

    /**
     * 某一天的投入分钟数。
     *
     * <p>用 {@code coalesce} 兜住「当天没有任何打卡」的情况 ——
     * 否则 {@code sum()} 返回 null，调用方要到处判空。
     */
    @Query("""
            select coalesce(sum(c.actualMinutes), 0)
            from Checkin c
            where c.userId = :userId and c.checkinDate = :date
            """)
    long sumMinutesByDate(@Param("userId") Long userId, @Param("date") LocalDate date);

    /** 累计投入分钟数（全量）。 */
    @Query("""
            select coalesce(sum(c.actualMinutes), 0)
            from Checkin c
            where c.userId = :userId
            """)
    long sumMinutesTotal(@Param("userId") Long userId);

    /** 各科目最近一次打卡日期。 */
    @Query("""
            select new com.wpc725562.examtracker.repository.projection.SubjectLastCheckin(
                       c.subjectId, max(c.checkinDate))
            from Checkin c
            where c.userId = :userId
            group by c.subjectId
            """)
    List<SubjectLastCheckin> findLastCheckinDateGroupBySubject(@Param("userId") Long userId);

    /** 按日期聚合，用于「日历打卡」视图。 */
    @Query("""
            select new com.wpc725562.examtracker.repository.projection.DateMinutes(
                       c.checkinDate, sum(c.actualMinutes))
            from Checkin c
            where c.userId = :userId and c.checkinDate between :from and :to
            group by c.checkinDate
            order by c.checkinDate
            """)
    List<DateMinutes> sumMinutesGroupByDate(@Param("userId") Long userId,
                                            @Param("from") LocalDate from,
                                            @Param("to") LocalDate to);

    /** 按科目聚合，用于「四科看板」。 */
    @Query("""
            select new com.wpc725562.examtracker.repository.projection.SubjectMinutes(
                       c.subjectId, sum(c.actualMinutes))
            from Checkin c
            where c.userId = :userId and c.checkinDate between :from and :to
            group by c.subjectId
            """)
    List<SubjectMinutes> sumMinutesGroupBySubject(@Param("userId") Long userId,
                                                  @Param("from") LocalDate from,
                                                  @Param("to") LocalDate to);

    /**
     * 所有打过卡的日期，**倒序**。
     *
     * <p>「连续打卡天数」需要从最近一天往回数，所以顺序由数据库给定，
     * 不要让调用方自己再排一次 —— 排序规则有两处实现时，迟早会不一致。
     */
    @Query("""
            select distinct c.checkinDate
            from Checkin c
            where c.userId = :userId
            order by c.checkinDate desc
            """)
    List<LocalDate> findDistinctCheckinDatesDesc(@Param("userId") Long userId);

    /** 批量删除某科目下的全部打卡记录（见 {@code TaskRepository#deleteByUserIdAndSubjectId} 的说明）。 */
    @Modifying
    @Query("delete from Checkin c where c.userId = :userId and c.subjectId = :subjectId")
    int deleteByUserIdAndSubjectId(@Param("userId") Long userId, @Param("subjectId") Long subjectId);
}
