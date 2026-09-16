package com.gm222.courseregistrationsystem.repository;

import com.gm222.courseregistrationsystem.model.entity.CoursePrerequisite;
import com.gm222.courseregistrationsystem.model.entity.CoursePrerequisiteId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CoursePrerequisiteRepository extends JpaRepository<CoursePrerequisite, CoursePrerequisiteId> {
}
