package com.gm222.courseregistrationsystem.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;

@Entity
@Table(name = "course_offering",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_offering_snapshot_legacy", columnNames = {"snapshot_id", "legacy_offering_id"})
        }, indexes = {
                @Index(name = "idx_offering_professor_semester_status", columnList = "professor_id,semester_id,status"),
                @Index(name = "idx_offering_semester_status_course", columnList = "semester_id,status,course_id"),
                @Index(name = "idx_offering_course", columnList = "course_id")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@ToString(onlyExplicitlyIncluded = true)
public class CourseOffering extends AuditedEntity {

    @ToString.Include
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 学期外键关联
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "semester_id", nullable = false)
    private Semester semester;

    // 目录快照外键关联
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "snapshot_id", nullable = false)
    private CatalogSnapshot snapshot;

    // 课程外键关联
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "course_id", nullable = false)
    private Course course;

    @Column(name = "legacy_offering_id", length = 64, nullable = false)
    private String legacyOfferingId;

    @Column(name = "section_code", length = 32, nullable = false)
    private String sectionCode;

    // 授课教师，允许为空
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "professor_id")
    private Professor professor;

    @Column(name = "source_teacher_name", length = 100)
    private String sourceTeacherName;

    @Column(name = "min_students", nullable = false)
    private Short minStudents = 3;

    @Column(name = "max_students", nullable = false)
    private Short maxStudents = 10;

    @Column(name = "enrolled_count", nullable = false)
    private Short enrolledCount = 0;

    @Column(length = 16, nullable = false)
    private String status = "OPEN";

    @Column(name = "cancel_reason", length = 32)
    private String cancelReason;

    @Column(precision = 10, scale = 2, nullable = false)
    private BigDecimal tuition;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(length = 3, columnDefinition = "CHAR(3)", nullable = false)
    private String currency = "CNY";

    // 乐观锁版本号，数据变更时自动递增
    @Version
    @Column(nullable = false)
    private Long version;
}
