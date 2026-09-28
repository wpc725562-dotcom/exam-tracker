package com.wpc725562.examtracker.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 每日任务 —— 本项目的核心实体。
 *
 * <p>「计划」和「实际」是分开的两件事：{@link #planMinutes} 是打算花多久，
 * 实际花了多久记在 {@link Checkin#getActualMinutes()}。把两者塞进同一个字段
 * 会导致「计划 60 分钟但只学了 20 分钟」这种情况无法表达，
 * 而备考里这恰恰是最需要看见的信息。
 */
@Entity
@Table(
        name = "task",
        indexes = {
                @Index(name = "idx_task_user_date", columnList = "user_id,plan_date"),
                @Index(name = "idx_task_user_status", columnList = "user_id,status"),
                @Index(name = "idx_task_subject", columnList = "subject_id")
        }
)
@Getter
@Setter
@NoArgsConstructor
public class Task extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "subject_id", nullable = false)
    private Long subjectId;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    /** 计划完成日期。用 {@code LocalDate} 而不是时间戳 —— 任务是按「天」规划的，带上时分秒只会带来时区歧义。 */
    @Column(name = "plan_date", nullable = false)
    private LocalDate planDate;

    @Column(name = "plan_minutes", nullable = false)
    private Integer planMinutes;

    @Enumerated(EnumType.STRING)
    @Column(name = "priority", nullable = false, length = 16)
    private Priority priority = Priority.MEDIUM;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private TaskStatus status = TaskStatus.TODO;

    @Column(name = "note", length = 500)
    private String note;

    /**
     * 完成时间。只有状态变成 {@code DONE} 时才写，
     * 之后如果再改回 {@code TODO} 会被清空 —— 保证「完成时间有值」与「状态是 DONE」永远一致。
     */
    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    public Task(Long userId, Long subjectId, String title, LocalDate planDate, Integer planMinutes,
                Priority priority, String note) {
        this.userId = userId;
        this.subjectId = subjectId;
        this.title = title;
        this.planDate = planDate;
        this.planMinutes = planMinutes;
        this.priority = priority == null ? Priority.MEDIUM : priority;
        this.note = note;
        this.status = TaskStatus.TODO;
    }

    /**
     * 变更状态。把「状态」和「完成时间」的一致性收在一个方法里，
     * 而不是让 Service 分别 set 两个字段 —— 那样迟早有人只改一个。
     */
    public void changeStatus(TaskStatus next, LocalDateTime now) {
        this.status = next;
        this.completedAt = (next == TaskStatus.DONE) ? now : null;
    }
}
