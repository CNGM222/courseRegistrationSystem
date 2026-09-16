package com.gm222.courseregistrationsystem.repository;

import com.gm222.courseregistrationsystem.model.entity.OfferingMeeting;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface OfferingMeetingRepository extends JpaRepository<OfferingMeeting, Long> {
}
