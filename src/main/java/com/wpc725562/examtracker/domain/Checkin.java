package com.wpc725562.examtracker.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

/**
 * 打卡记录 —— 「今天在这个科目上实际投入了多久」。
 *
 * <p>为什么需要它，而不只是把 {@link Task} 标成完成：
 * <ul>
 *   <li>任务可以是「做一套真题」，实际可能花了 150 分钟 —— 计划时长表达不了；</li>
 *   <li>会有不挂在任何任务上的学习时间（随手翻笔记、听听力）；</li>
 *   <li>「连续打卡天数」这个指标必须以**独立于任务**的事实为准，
 *       否则删掉一个任务就会让连续天数断掉，那是错的。</li>
 * </ul>
 *
 * <p>所以 {@code task_id} 可以为空：有任务就关联，没有就是纯计时。
 */
@Entity
@Table(
        name = "checkin",
        indexes = {
                @Index(name = "idx_checkin_user_date", columnList = "user_id,checkin_date"),
                @Index(name = "idx_checkin_subject", columnList = "subject_id")
        }
)
@Getter
@Setter
@NoArgsConstructor
public class Checkin extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "subject_id", nullable = false)
    private Long subjectId;

    /** 可空：允许记录「不挂在任何具体任务上」的学习时间。 */
    @Column(name = "task_id")
    private Long taskId;

    @Column(name = "checkin_date", nullable = false)
    private LocalDate checkinDate;

    @Column(name = "actual_minutes", nullable = false)
    private Integer actualMinutes;

    @Column(name = "note", length = 500)
    private String note;

    public Checkin(Long userId, Long subjectId, Long taskId, LocalDate checkinDate,
                   Integer actualMinutes, String note) {
        this.userId = userId;
        this.subjectId = subjectId;
        this.taskId = taskId;
        this.checkinDate = checkinDate;
        this.actualMinutes = actualMinutes;
        this.note = note;
    }
}
