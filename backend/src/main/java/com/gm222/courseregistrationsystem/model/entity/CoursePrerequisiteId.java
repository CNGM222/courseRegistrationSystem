package com.gm222.courseregistrationsystem.model.entity;

import lombok.*;

import java.io.Serializable;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class CoursePrerequisiteId implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long courseId;
    private Long prerequisiteCourseId;
}
