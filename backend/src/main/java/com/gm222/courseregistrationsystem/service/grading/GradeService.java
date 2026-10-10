package com.gm222.courseregistrationsystem.service.grading;

import com.gm222.courseregistrationsystem.exception.BusinessException;
import com.gm222.courseregistrationsystem.model.dto.ApiDtos.*;
import com.gm222.courseregistrationsystem.model.entity.*;
import com.gm222.courseregistrationsystem.repository.BusinessStore;
import com.gm222.courseregistrationsystem.service.BusinessTime;
import com.gm222.courseregistrationsystem.service.audit.AuditService;
import com.gm222.courseregistrationsystem.service.identity.*;
import com.gm222.courseregistrationsystem.service.teaching.RosterService;
import com.gm222.courseregistrationsystem.web.WebValues;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GradeService {
  private final BusinessStore db;
  private final BusinessTime time;
  private final RosterService roster;
  private final AuditService audit;

  public GradeService(
      BusinessStore db, BusinessTime time, RosterService roster, AuditService audit) {
    this.db = db;
    this.time = time;
    this.roster = roster;
    this.audit = audit;
  }

  @Transactional
  public Roster update(CurrentActor actor, Long offeringId, Grades command) {
    actor.requireRole("PROFESSOR");
    var initial = db.get(CourseOffering.class, offeringId);
    db.gate(initial.getSemester().getId());
    var professor = db.lock(Professor.class, actor.professorId());
    PeopleGuard.professor(professor, actor);
    var o = db.lock(CourseOffering.class, offeringId);
    if (o.getProfessor() == null || !o.getProfessor().getId().equals(professor.getId()))
      throw BusinessException.forbidden();
    if (!time.ended(o.getSemester()) || !"CLOSED".equals(o.getStatus()))
      throw BusinessException.conflict("GRADE_WINDOW_CLOSED", "学期或教学班尚未结束，暂不能录入成绩");
    var entries = new ArrayList<>(command.entries());
    entries.sort(Comparator.comparing(e -> WebValues.id(e.enrollmentId())));
    Set<Long> seen = new HashSet<>();
    for (var input : entries) {
      if (input.grade() == null || !Set.of("A", "B", "C", "D", "F", "I").contains(input.grade()))
        throw BusinessException.invalid("成绩只允许 A、B、C、D、F、I");
      Long eid = WebValues.id(input.enrollmentId());
      if (!seen.add(eid)) throw BusinessException.invalid("同一注册记录不能重复提交");
      var e = db.lock(Enrollment.class, eid);
      if (!e.getOffering().getId().equals(o.getId()) || !"ENROLLED".equals(e.getState()))
        throw BusinessException.forbidden();
      Grade g =
          db.first(Grade.class, "select g from Grade g where g.enrollmentId=:e", Map.of("e", eid))
              .orElse(null);
      Long expected = input.expectedVersion();
      if (g == null && expected != null || g != null && expected == null)
        throw new BusinessException(412, "PRECONDITION_FAILED", "成绩记录已变化，请重新读取名单");
      if (g != null && !g.getVersion().equals(expected))
        throw new BusinessException(412, "PRECONDITION_FAILED", "成绩记录已变化，请重新读取名单");
      if (g == null) {
        g = new Grade();
        g.setEnrollment(e);
        g.setValue(input.grade());
        g.setRecordedBy(professor);
        db.persist(g);
        e.setGrade(g);
      } else {
        g.setValue(input.grade());
        g.setRecordedBy(professor);
      }
    }
    audit.record(actor, "UPDATE_GRADES", "OFFERING", offeringId);
    db.flush();
    return roster.get(actor, offeringId);
  }
}
