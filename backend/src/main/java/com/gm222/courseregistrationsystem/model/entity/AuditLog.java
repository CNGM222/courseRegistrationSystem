package com.gm222.courseregistrationsystem.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;
import java.util.Map;

@Entity
@Immutable
@Table(name = "audit_log", indexes = {
        @Index(name = "idx_audit_target_time", columnList = "target_type,target_id,occurred_at"),
        @Index(name = "idx_audit_actor_time", columnList = "actor_account_id,occurred_at"),
        @Index(name = "idx_audit_request", columnList = "request_id")
})
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@ToString(onlyExplicitlyIncluded = true)
public class AuditLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @ToString.Include
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "actor_account_id")
    private Account actorAccount;

    @Column(length = 64, nullable = false)
    private String action;

    @Column(name = "target_type", length = 32, nullable = false)
    private String targetType;

    @Column(name = "target_id", length = 64, nullable = false)
    private String targetId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "request_id", length = 36, nullable = false, columnDefinition = "CHAR(36)")
    private String requestId;

    @CreatedDate
    @Column(name = "occurred_at", nullable = false, updatable = false, columnDefinition = "DATETIME(3)")
    private LocalDateTime occurredAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "JSON")
    private Map<String, Object> detail;
}
