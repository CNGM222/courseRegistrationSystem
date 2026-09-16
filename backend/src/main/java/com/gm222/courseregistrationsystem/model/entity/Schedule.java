package com.gm222.courseregistrationsystem.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.jspecify.annotations.NonNull;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Set;

@Entity
@Table(name = "schedule", uniqueConstraints = {
        @UniqueConstraint(name = "uk_schedule_student_semester", columnNames = {"student_id", "semester_id"})
}, indexes = {
        @Index(name = "idx_schedule_semester_submitted_student", columnList = "semester_id,first_submitted_at,student_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@ToString(onlyExplicitlyIncluded = true)
public class Schedule extends AuditedEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @ToString.Include
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "student_id", nullable = false)
    private Student student;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "semester_id", nullable = false)
    private Semester semester;

    @Column(length = 16, nullable = false)
    private String status = "DRAFT";

    // Logical revisions can describe empty choices and therefore are not foreign keys.
    @Column(name = "draft_revision")
    private Long draftRevision;

    @Column(name = "submitted_revision")
    private Long submittedRevision;

    @Column(name = "latest_revision", nullable = false)
    private Long latestRevision = 0L;

    @Column(name = "first_submitted_at", columnDefinition = "DATETIME(3)")
    private LocalDateTime firstSubmittedAt;

    @Version
    @Column(nullable = false)
    private Long version;

    @OneToOne(mappedBy = "schedule")
    private BillingInvoice billingInvoice;

    @NonNull
    @OneToMany(mappedBy = "schedule")
    private Set<Enrollment> enrollments = new LinkedHashSet<>();

    @NonNull
    @OneToMany(mappedBy = "schedule")
    private Set<ScheduleChoice> scheduleChoices = new LinkedHashSet<>();
}
