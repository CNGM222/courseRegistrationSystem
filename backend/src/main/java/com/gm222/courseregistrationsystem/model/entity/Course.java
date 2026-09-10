package com.gm222.courseregistrationsystem.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "courses")
@Setter
@Getter
@NoArgsConstructor
@AllArgsConstructor
@ToString
@EqualsAndHashCode
public class Course {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "snapshot_id")
    private CatalogSnapshot snapshot;

    @Column(length = 64,name = "legacy_course_id")
    private String legacyCourseId;

    @Column(length = 64)
    private String code;

    @Column(length = 200)
    private String name; // 课程名称

    @Column(length = 100)
    private String department; // 开课院系

    @Column(precision = 4, scale = 1)
    private BigDecimal credits; // 课程学分，总精度4位，小数位1位
}
