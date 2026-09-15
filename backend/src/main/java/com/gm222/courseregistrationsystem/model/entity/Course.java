package com.gm222.courseregistrationsystem.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "courses", uniqueConstraints = {
        @UniqueConstraint(name = "uk_courses_snapshot_legacy", columnNames = {"snapshot_id", "legacy_course_id"})
}, indexes = {
        @Index(name = "idx_courses_snapshot_department_code", columnList = "snapshot_id,department,code")
})
@Setter
@Getter
@NoArgsConstructor
@AllArgsConstructor
@ToString(onlyExplicitlyIncluded = true)
public class Course {
    @ToString.Include
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "snapshot_id", nullable = false)
    private CatalogSnapshot snapshot;

    @Column(length = 64, name = "legacy_course_id", nullable = false)
    private String legacyCourseId;

    @Column(length = 64, nullable = false)
    private String code;

    @Column(length = 200, nullable = false)
    private String name; // 课程名称

    @Column(length = 100, nullable = false)
    private String department; // 开课院系

    @Column(precision = 4, scale = 1, nullable = false)
    private BigDecimal credits; // 课程学分，总精度4位，小数位1位
}
