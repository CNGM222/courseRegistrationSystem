package com.gm222.courseregistrationsystem.model.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "course_prerequisite", indexes = {
        @Index(name = "idx_prerequisite_reverse", columnList = "prerequisite_course_id")
})
@Setter
@Getter
@NoArgsConstructor
@AllArgsConstructor
@ToString(onlyExplicitlyIncluded = true)
@IdClass(CoursePrerequisiteId.class)
public class CoursePrerequisite {
    @EmbeddedId
    private CoursePrerequisiteId id;

    @Id
    @ToString.Include
    @Column(name = "course_id", nullable = false)
    private Long courseId;

    @Id
    @ToString.Include
    @Column(name = "prerequisite_course_id", nullable = false)
    private Long prerequisiteCourseId;

    // 外键关联：当前课程
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "course_id", nullable = false, insertable = false, updatable = false)
    private Course course;

    // 外键关联：对应的先修课程
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "prerequisite_course_id", nullable = false, insertable = false, updatable = false)
    private Course prerequisiteCourse;
}
