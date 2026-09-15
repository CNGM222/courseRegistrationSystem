package com.gm222.courseregistrationsystem.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "accounts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@ToString(onlyExplicitlyIncluded = true)
public class Account extends AuditedEntity {
    @ToString.Include
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false, length = 64)
    private String username;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash; // BCrypt密码摘要

    @Column(length = 16, nullable = false)
    private String role; // STUDENT / PROFESSOR / REGISTRAR

    @Column(nullable = false)
    private Boolean enabled = true; // 是否允许登录

    @Column(name = "failed_attempts", nullable = false)
    private Integer failedAttempts = 0; // 登录失败次数

    @Column(name = "locked_until", columnDefinition = "DATETIME(3)")
    private LocalDateTime lockedUntil; // 账号锁定到期时间

    @Version
    @Column(nullable = false)
    private Long version; // 并发版本号
}
