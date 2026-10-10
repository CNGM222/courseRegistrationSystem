package com.gm222.courseregistrationsystem.service.grading;

import com.gm222.courseregistrationsystem.exception.BusinessException;
import com.gm222.courseregistrationsystem.model.dto.ApiDtos.*;
import com.gm222.courseregistrationsystem.model.entity.*;
import com.gm222.courseregistrationsystem.repository.BusinessStore;
import com.gm222.courseregistrationsystem.service.BusinessTime;
import com.gm222.courseregistrationsystem.service.identity.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReportCardService {
  private final BusinessStore db;
  private final BusinessTime time;

  public ReportCardService(BusinessStore db, BusinessTime time) {
    this.db = db;
    this.time = time;
  }

  @Transactional(readOnly = true)
  public ReportCard mine(CurrentActor actor, Long semesterId) {
    var s = db.get(Semester.class, semesterId);
    if (!time.ended(s)) throw new BusinessException(404, "SEMESTER_NOT_CLOSED", "只能查看已结束学期的成绩单");
    var student = db.get(Student.class, actor.studentId());
    PeopleGuard.student(student, actor);
    var schedule = db.schedule(student.getId(), semesterId).orElse(null);
    if (schedule == null) return new ReportCard(semesterId.toString(), s.getName(), List.of());
    var items =
        db.enrollments(schedule).stream()
            .filter(e -> "ENROLLED".equals(e.getState()))
            .map(
                e -> {
                  var o = e.getOffering();
                  return new ReportItem(
                      e.getId().toString(),
                      o.getId().toString(),
                      o.getCourse().getCode(),
                      o.getCourse().getName(),
                      o.getSectionCode(),
                      o.getProfessor() == null
                          ? o.getSourceTeacherName()
                          : o.getProfessor().getName(),
                      e.getGrade() == null ? null : e.getGrade().getValue(),
                      e.getGrade() == null ? null : e.getGrade().getVersion());
                })
            .toList();
    return new ReportCard(semesterId.toString(), s.getName(), items);
  }
}
