package com.gm222.courseregistrationsystem.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Entity
@Table(name = "enrollment", uniqueConstraints = {
        @UniqueConstraint(name = "uk_enrollment_schedule_offering", columnNames = {"schedule_id", "offering_id"})
}, indexes = {
        @Index(name = "idx_enrollment_offering_state", columnList = "offering_id,state"),
        @Index(name = "idx_enrollment_schedule_state", columnList = "schedule_id,state")
})
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@ToString(onlyExplicitlyIncluded = true)
public class Enrollment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @ToString.Include
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "schedule_id", nullable = false)
    private Schedule schedule;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "offering_id", nullable = false)
    private CourseOffering offering;

    @Column(length = 16, nullable = false)
    private String state = "ENROLLED";

    @Column(length = 16, nullable = false)
    private String source;

    @Column(length = 32)
    private String reason;

    @CreatedDate
    @Column(name = "enrolled_at", nullable = false, columnDefinition = "DATETIME(3)")
    private LocalDateTime enrolledAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false, columnDefinition = "DATETIME(3)")
    private LocalDateTime updatedAt;

    @OneToOne(mappedBy = "enrollment")
    private Grade grade;
}
