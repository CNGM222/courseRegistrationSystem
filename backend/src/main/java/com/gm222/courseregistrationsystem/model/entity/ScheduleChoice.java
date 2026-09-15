package com.gm222.courseregistrationsystem.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Immutable;

@Entity
@Immutable
@Table(name = "schedule_choice", uniqueConstraints = {
        @UniqueConstraint(name = "uk_choice_revision_offering", columnNames = {"schedule_id", "revision", "offering_id"}),
        @UniqueConstraint(name = "uk_choice_revision_priority", columnNames = {"schedule_id", "revision", "kind", "priority"})
}, indexes = {
        @Index(name = "idx_choice_offering", columnList = "offering_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@ToString(onlyExplicitlyIncluded = true)
public class ScheduleChoice {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @ToString.Include
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "schedule_id", nullable = false)
    private Schedule schedule;

    @Column(nullable = false)
    private Long revision;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "offering_id", nullable = false)
    private CourseOffering offering;

    @Column(length = 16, nullable = false)
    private String kind;

    @Column(nullable = false)
    private Short priority;
}
