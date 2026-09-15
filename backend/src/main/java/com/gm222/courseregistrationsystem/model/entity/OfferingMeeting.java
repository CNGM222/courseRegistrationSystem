package com.gm222.courseregistrationsystem.model.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "offering_meeting", uniqueConstraints = {
        @UniqueConstraint(name = "uk_meeting_slot", columnNames = {
                "offering_id", "week_no", "weekday", "start_period", "end_period_exclusive"
        })
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@ToString(onlyExplicitlyIncluded = true)
public class OfferingMeeting {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @ToString.Include
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "offering_id", nullable = false)
    private CourseOffering offering;

    @Column(name = "week_no", nullable = false)
    private Short weekNo;

    @Column(nullable = false)
    private Short weekday;

    @Column(name = "start_period", nullable = false)
    private Short startPeriod;

    @Column(name = "end_period_exclusive", nullable = false)
    private Short endPeriodExclusive;

    @Column(length = 100)
    private String room;
}
