package com.gm222.courseregistrationsystem.model.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "course_prerequisite")
@Setter
@Getter
@NoArgsConstructor
@AllArgsConstructor
@ToString
@EqualsAndHashCode
@IdClass(CoursePrerequisite.class)
public class CoursePrerequisite {
    @Id
    @Column(name = "course_id")
    private Long courseId;

    @Id
    @Column(name = "prerequisite_course_id")
    private Long prerequisiteCourseId;

    // 外键关联：当前课程
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "course_id", insertable = false, updatable = false)
    private Course course;

    // 外键关联：对应的先修课程
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "prerequisite_course_id", insertable = false, updatable = false)
    private Course prerequisiteCourse;
}
