package com.wpc725562.examtracker.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 科目（语文 / 数学 / 英语 / 专业课……）。
 *
 * <p>{@code user_id} 是普通的 {@code Long} 列，没有用 {@code @ManyToOne} 关联到 {@link User}。
 * 这是刻意的选择，理由写在 README 的「设计取舍」一节：
 * <ul>
 *   <li>本项目的**每一次**查询都以 {@code user_id} 开头（数据隔离），
 *       关联对象在这里只会引入无谓的 join 和 N+1 风险；</li>
 *   <li>引用完整性靠数据库的**外键约束**保证（见 {@code sql/schema.sql}），
 *       不靠 ORM 的对象图；</li>
 *   <li>查询结果一律走 **DTO 投影**，不把实体暴露出去，
 *       所以也不存在「懒加载在事务外抛 LazyInitializationException」的问题
 *       （本项目 {@code open-in-view} 是关的）。</li>
 * </ul>
 */
@Entity
@Table(
        name = "subject",
        uniqueConstraints = @UniqueConstraint(name = "uk_subject_user_name", columnNames = {"user_id", "name"}),
        indexes = @Index(name = "idx_subject_user", columnList = "user_id")
)
@Getter
@Setter
@NoArgsConstructor
public class Subject extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "name", nullable = false, length = 50)
    private String name;

    /** 前端用来区分科目颜色，存十六进制串如 {@code #4F46E5}。 */
    @Column(name = "color", length = 16)
    private String color;

    /** 每周计划投入分钟数，用于「四科看板」里算达成率。 */
    @Column(name = "target_minutes_per_week", nullable = false)
    private Integer targetMinutesPerWeek;

    /** 展示顺序。用整数而不是依赖 id 顺序，用户可以自己拖动排序。 */
    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;

    public Subject(Long userId, String name, String color, Integer targetMinutesPerWeek, Integer sortOrder) {
        this.userId = userId;
        this.name = name;
        this.color = color;
        this.targetMinutesPerWeek = targetMinutesPerWeek;
        this.sortOrder = sortOrder;
    }
}
