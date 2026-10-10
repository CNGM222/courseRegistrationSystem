package com.gm222.courseregistrationsystem.service.catalog;

import com.gm222.courseregistrationsystem.model.dto.ApiDtos.*;
import com.gm222.courseregistrationsystem.model.entity.*;
import com.gm222.courseregistrationsystem.repository.BusinessStore;
import com.gm222.courseregistrationsystem.service.BusinessTime;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class Views {
  private final BusinessStore db;

  public Views(BusinessStore db) {
    this.db = db;
  }

  public Offering offering(CourseOffering o) {
    var c = o.getCourse();
    var p = o.getProfessor();
    return new Offering(
        o.getId().toString(),
        o.getSemester().getId().toString(),
        c.getId().toString(),
        c.getCode(),
        c.getName(),
        c.getDepartment(),
        c.getCredits(),
        o.getSectionCode(),
        p == null ? null : p.getId().toString(),
        p == null ? null : p.getName(),
        db.prerequisites(c.getId()).stream()
            .map(
                x ->
                    x.getPrerequisiteCourse().getCode() + " " + x.getPrerequisiteCourse().getName())
            .toList(),
        db.meetings(o.getId()).stream()
            .map(
                m ->
                    new Meeting(
                        m.getWeekNo(),
                        m.getWeekday(),
                        m.getStartPeriod(),
                        m.getEndPeriodExclusive(),
                        m.getRoom()))
            .toList(),
        o.getEnrolledCount(),
        o.getMinStudents(),
        o.getMaxStudents(),
        o.getStatus(),
        o.getCancelReason(),
        o.getTuition().toPlainString(),
        o.getCurrency(),
        o.getVersion());
  }

  public ScheduleView schedule(Schedule s) {
    return new ScheduleView(
        s.getId().toString(),
        s.getSemester().getId().toString(),
        s.getStatus(),
        s.getVersion(),
        s.getDraftRevision(),
        s.getSubmittedRevision(),
        s.getLatestRevision(),
        BusinessTime.iso(s.getFirstSubmittedAt()),
        choices(s, s.getDraftRevision()),
        choices(s, s.getSubmittedRevision()),
        db.enrollments(s).stream()
            .map(
                e ->
                    new EnrollmentView(
                        e.getId().toString(),
                        e.getOffering().getId().toString(),
                        e.getState(),
                        e.getSource(),
                        e.getReason(),
                        offering(e.getOffering())))
            .toList());
  }

  private List<Choice> choices(Schedule s, Long revision) {
    return db.choices(s, revision).stream()
        .map(c -> new Choice(c.getOffering().getId().toString(), c.getKind(), c.getPriority()))
        .toList();
  }

  public SemesterView semester(Semester s) {
    return new SemesterView(
        s.getId().toString(),
        s.getCode(),
        s.getName(),
        s.getStartDate(),
        s.getEndDate(),
        BusinessTime.iso(s.getRegistrationStart()),
        BusinessTime.iso(s.getRegistrationEnd()),
        BusinessTime.iso(s.getAddDropEnd()),
        s.getStatus(),
        s.getCurrency(),
        s.getVersion());
  }
}
