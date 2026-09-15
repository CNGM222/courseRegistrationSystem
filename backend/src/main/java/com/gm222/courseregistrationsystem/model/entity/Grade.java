package com.gm222.courseregistrationsystem.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Entity
@Table(name = "grade", indexes = {
        @Index(name = "idx_grade_recorded_by", columnList = "recorded_by")
})
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@ToString(onlyExplicitlyIncluded = true)
public class Grade {
    @Id
    @ToString.Include
    @Column(name = "enrollment_id", nullable = false)
    private Long enrollmentId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "enrollment_id", nullable = false)
    private Enrollment enrollment;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "`value`", length = 1, nullable = false, columnDefinition = "CHAR(1)")
    private String value;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recorded_by", nullable = false)
    private Professor recordedBy;

    @CreatedDate
    @Column(name = "recorded_at", nullable = false, updatable = false, columnDefinition = "DATETIME(3)")
    private LocalDateTime recordedAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false, columnDefinition = "DATETIME(3)")
    private LocalDateTime updatedAt;

    @Version
    @Column(nullable = false)
    private Long version;
}
