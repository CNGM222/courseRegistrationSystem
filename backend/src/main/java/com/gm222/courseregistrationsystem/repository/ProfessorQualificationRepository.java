package com.gm222.courseregistrationsystem.repository;

import com.gm222.courseregistrationsystem.model.entity.ProfessorQualification;
import com.gm222.courseregistrationsystem.model.entity.ProfessorQualificationId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ProfessorQualificationRepository extends JpaRepository<ProfessorQualification, ProfessorQualificationId> {
}
