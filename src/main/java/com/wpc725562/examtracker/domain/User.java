package com.wpc725562.examtracker.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

/**
 * 用户（备考的人）。
 *
 * <p>表名用 {@code app_user} 而不是 {@code user}：{@code user} 在多个数据库里都是
 * 保留字或系统表名，写 SQL 时到处要加反引号，换个数据库还可能直接报错。
 * 加个前缀一次性解决。
 *
 * <p>{@code passwordHash} 存的是 BCrypt 的结果（60 字符），永远不存明文，
 * 也永远不出现在任何响应 DTO 里。
 */
@Entity
@Table(
        name = "app_user",
        uniqueConstraints = @UniqueConstraint(name = "uk_app_user_username", columnNames = "username")
)
@Getter
@Setter
@NoArgsConstructor
public class User extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "username", nullable = false, length = 50)
    private String username;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(name = "nickname", length = 50)
    private String nickname;

    /** 考试日期。用来算「距考试还有 N 天」，也是「备考紧迫度」的唯一依据。 */
    @Column(name = "exam_date")
    private LocalDate examDate;

    public User(String username, String passwordHash, String nickname, LocalDate examDate) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.nickname = nickname;
        this.examDate = examDate;
    }
}
