package com.wpc725562.examtracker.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * 所有实体的公共时间戳。
 *
 * <p>用 {@code @CreatedDate} / {@code @LastModifiedDate} 而不是在每个 Service 里手写
 * {@code setCreatedAt(now())}：手写总会有人漏，而且时区、精度各写各的。
 * 交给框架填，全库只有一处实现。
 *
 * <p>{@code updatable = false} 是给创建时间的硬保证 —— 任何 update 语句都不会碰到它。
 */
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
public abstract class BaseTimeEntity {

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
