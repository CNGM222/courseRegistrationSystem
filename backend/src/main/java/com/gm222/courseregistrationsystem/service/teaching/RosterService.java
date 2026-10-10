package com.gm222.courseregistrationsystem.service.teaching;

import com.gm222.courseregistrationsystem.exception.BusinessException;
import com.gm222.courseregistrationsystem.model.dto.ApiDtos.*;
import com.gm222.courseregistrationsystem.model.entity.*;
import com.gm222.courseregistrationsystem.repository.BusinessStore;
import com.gm222.courseregistrationsystem.service.*;
import com.gm222.courseregistrationsystem.service.catalog.Views;
import com.gm222.courseregistrationsystem.service.identity.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RosterService {
  private final BusinessStore db;
  private final Views views;
  private final BusinessTime time;

  public RosterService(BusinessStore db, Views views, BusinessTime time) {
    this.db = db;
    this.views = views;
    this.time = time;
  }

  @Transactional(readOnly = true)
  public Roster get(CurrentActor actor, Long offeringId) {
    var p = db.get(Professor.class, actor.professorId());
    PeopleGuard.professor(p, actor);
    var o = db.get(CourseOffering.class, offeringId);
    if (o.getProfessor() == null || !o.getProfessor().getId().equals(p.getId()))
      throw BusinessException.forbidden();
    if (!time.ended(o.getSemester()))
      throw BusinessException.conflict("ROSTER_NOT_FINAL", "学期尚未结束，最终名单暂不可查询");
    var students =
        db.list(
            Enrollment.class,
            "select e from Enrollment e where e.offering.id=:o and e.state='ENROLLED' order by"
                + " e.id",
            Map.of("o", offeringId));
    boolean editable = "CLOSED".equals(o.getStatus()) && time.ended(o.getSemester());
    return new Roster(
        views.offering(o),
        editable,
        students.stream()
            .map(
                e ->
                    new RosterStudent(
                        e.getId().toString(),
                        e.getSchedule().getStudent().getId().toString(),
                        e.getSchedule().getStudent().getName(),
                        e.getGrade() == null ? null : e.getGrade().getValue(),
                        e.getGrade() == null ? null : e.getGrade().getVersion()))
            .toList());
  }
}
