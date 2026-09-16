package com.gm222.courseregistrationsystem.repository;

import com.gm222.courseregistrationsystem.model.entity.CourseOffering;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CourseOfferingRepository extends JpaRepository<CourseOffering, Long> {

}
