package com.gm222.courseregistrationsystem.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Entity
@Table(name = "accounts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@ToString
@EqualsAndHashCode
@EntityListeners(AuditingEntityListener.class) // 启用审计字段自动填充
public class Account {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false, length = 64)
    private String username;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash; // BCrypt密码摘要

    @Column(length = 16, nullable = false)
    private String role; // STUDENT / PROFESSOR / REGISTRAR

    private Boolean enabled; // 是否允许登录

    @Column(name = "failed_attempts")
    private Integer failedAttempts; // 登录失败次数

    @Column(name = "locked_until")
    private LocalDateTime lockedUntil; // 账号锁定到期时间

    @Version
    private Long version; // 并发版本号

    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt; // 创建时间

    @LastModifiedDate
    @Column(name = "updated_at")
    private LocalDateTime updatedAt; // 更新时间
}
