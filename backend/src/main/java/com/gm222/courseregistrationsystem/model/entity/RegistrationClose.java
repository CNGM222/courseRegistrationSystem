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
import java.util.UUID;

@Entity
@Immutable
@Table(name = "registration_close", indexes = {
        @Index(name = "idx_close_registrar", columnList = "registrar_account_id")
})
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@ToString(onlyExplicitlyIncluded = true)
public class RegistrationClose {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @ToString.Include
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "semester_id", nullable = false, unique = true)
    private Semester semester;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "run_id", length = 36, nullable = false, unique = true, columnDefinition = "CHAR(36)")
    private String runId = UUID.randomUUID().toString();

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "registrar_account_id", nullable = false)
    private Account registrarAccount;

    @CreatedDate
    @Column(name = "closed_at", nullable = false, updatable = false, columnDefinition = "DATETIME(3)")
    private LocalDateTime closedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "JSON")
    private Map<String, Object> summary;
}
