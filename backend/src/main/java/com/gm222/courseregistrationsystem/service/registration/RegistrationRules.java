package com.gm222.courseregistrationsystem.service.registration;

import com.gm222.courseregistrationsystem.model.dto.ApiDtos.Issue;
import com.gm222.courseregistrationsystem.model.entity.*;
import com.gm222.courseregistrationsystem.repository.BusinessStore;
import com.gm222.courseregistrationsystem.service.BusinessTime;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class RegistrationRules {
  private final BusinessStore db;
  private final ConflictPolicy conflicts;
  private final PrerequisitePolicy prerequisites;
  private final BusinessTime time;

  public RegistrationRules(
      BusinessStore db,
      ConflictPolicy conflicts,
      PrerequisitePolicy prerequisites,
      BusinessTime time) {
    this.db = db;
    this.conflicts = conflicts;
    this.prerequisites = prerequisites;
    this.time = time;
  }

  public Set<String> passed(Long studentId) {
    Set<String> passed = new HashSet<>();
    for (var g :
        db.list(
            Grade.class,
            "select g from Grade g where g.enrollment.schedule.student.id=:s and"
                + " g.enrollment.state='ENROLLED'",
            Map.of("s", studentId)))
      if (prerequisites.passed(
          g.getValue(), time.ended(g.getEnrollment().getSchedule().getSemester())))
        passed.add(g.getEnrollment().getOffering().getCourse().getLegacyCourseId());
    return passed;
  }

  public List<Issue> check(
      List<CourseOffering> offerings,
      Set<String> passed,
      Set<Long> retained,
      boolean requireTeacher) {
    List<Issue> issues = new ArrayList<>();
    Set<String> courses = new HashSet<>();
    for (var o : offerings) {
      if (!courses.add(o.getCourse().getLegacyCourseId()))
        issues.add(
            Issue.offering("DUPLICATE_COURSE", o.getCourse().getName() + "不能重复选择", o.getId()));
      if (!"OPEN".equals(o.getStatus()))
        issues.add(Issue.offering("OFFERING_CLOSED", o.getCourse().getName() + "停止注册", o.getId()));
      if (requireTeacher && o.getProfessor() == null)
        issues.add(Issue.offering("NO_PROFESSOR", o.getCourse().getName() + "没有授课教师", o.getId()));
      if (!retained.contains(o.getId()) && o.getEnrolledCount() >= o.getMaxStudents())
        issues.add(Issue.offering("CAPACITY_FULL", o.getCourse().getName() + "已满员", o.getId()));
      for (var p : db.prerequisites(o.getCourse().getId()))
        if (!passed.contains(p.getPrerequisiteCourse().getLegacyCourseId()))
          issues.add(
              Issue.offering(
                  "PREREQUISITE_NOT_MET",
                  o.getCourse().getName() + "要求通过先修课 " + p.getPrerequisiteCourse().getCode(),
                  o.getId()));
    }
    issues.addAll(conflicts(offerings));
    return issues;
  }

  public List<Issue> conflicts(List<CourseOffering> offerings) {
    return conflicts.check(
        offerings.stream()
            .flatMap(
                o ->
                    db.meetings(o.getId()).stream()
                        .map(
                            m ->
                                new ConflictPolicy.Slot(
                                    o.getId(),
                                    m.getWeekNo(),
                                    m.getWeekday(),
                                    m.getStartPeriod(),
                                    m.getEndPeriodExclusive())))
            .toList());
  }
}
