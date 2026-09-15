package com.gm222.courseregistrationsystem.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "semester")
@Setter
@Getter
@NoArgsConstructor
@AllArgsConstructor
@ToString(onlyExplicitlyIncluded = true)
public class Semester extends AuditedEntity {
    @ToString.Include
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 32, unique = true, nullable = false)
    private String code;

    @Column(length = 100, nullable = false)
    private String name;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Column(name = "registration_start", nullable = false, columnDefinition = "DATETIME(3)")
    private LocalDateTime registrationStart;

    @Column(name = "registration_end", nullable = false, columnDefinition = "DATETIME(3)")
    private LocalDateTime registrationEnd;

    @Column(name = "add_drop_end", nullable = false, columnDefinition = "DATETIME(3)")
    private LocalDateTime addDropEnd;

    @Column(length = 16, nullable = false)
    private String status = "PREPARATION";          // PREPARATION / OPEN / CLOSED

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "active_snapshot_id", unique = true)
    private CatalogSnapshot activeSnapshot;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(length = 3, columnDefinition = "CHAR(3)", nullable = false)
    private String currency = "CNY";

    @Column(name = "closed_at", columnDefinition = "DATETIME(3)")
    private LocalDateTime closedAt;

    @Version
    @Column(nullable = false)
    private Long version;
}
