package com.gm222.courseregistrationsystem.service.teaching;

import com.gm222.courseregistrationsystem.exception.BusinessException;
import com.gm222.courseregistrationsystem.model.dto.ApiDtos.*;
import com.gm222.courseregistrationsystem.model.entity.*;
import com.gm222.courseregistrationsystem.repository.BusinessStore;
import com.gm222.courseregistrationsystem.service.*;
import com.gm222.courseregistrationsystem.service.audit.AuditService;
import com.gm222.courseregistrationsystem.service.catalog.*;
import com.gm222.courseregistrationsystem.service.identity.*;
import com.gm222.courseregistrationsystem.service.registration.RegistrationRules;
import com.gm222.courseregistrationsystem.web.WebValues;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TeachingAssignmentService {
  private final BusinessStore db;
  private final Views views;
  private final CatalogQueryService catalog;
  private final BusinessTime time;
  private final RegistrationRules rules;
  private final AuditService audit;

  public TeachingAssignmentService(
      BusinessStore db,
      Views views,
      CatalogQueryService catalog,
      BusinessTime time,
      RegistrationRules rules,
      AuditService audit) {
    this.db = db;
    this.views = views;
    this.catalog = catalog;
    this.time = time;
    this.rules = rules;
    this.audit = audit;
  }

  @Transactional(readOnly = true)
  public TeachingPlan get(CurrentActor actor, Long semesterId) {
    var p = db.get(Professor.class, actor.professorId());
    PeopleGuard.professor(p, actor);
    var s = db.get(Semester.class, semesterId);
    var snap = "CLOSED".equals(s.getStatus()) ? s.getActiveSnapshot() : catalog.requireSnapshot(s);
    if (snap == null) throw new BusinessException(503, "CATALOG_UNAVAILABLE", "课程目录暂不可用");
    var eligible =
        db.list(
            CourseOffering.class,
            "select o from CourseOffering o where o.semester.id=:s and o.snapshot.id=:snap and"
                + " exists (select q from ProfessorQualification q where q.professor.id=:p and"
                + " q.course.id=o.course.id) order by o.course.code,o.sectionCode,o.id",
            Map.of("s", semesterId, "snap", snap.getId(), "p", p.getId()));
    var selected = db.list(CourseOffering.class,
        "select o from CourseOffering o where o.semester.id=:s and o.professor.id=:p order by o.course.code,o.sectionCode,o.id",
        Map.of("s",semesterId,"p",p.getId()));
    return new TeachingPlan(
        p.getVersion(),
        eligible.stream().map(views::offering).toList(),
        selected.stream().map(o -> o.getId().toString()).toList(),
        selected.stream().map(views::offering).toList());
  }

  @Transactional
  public TeachingPlan replace(
      CurrentActor actor, Long semesterId, TeachingCommand command, Long expected) {
    actor.requireRole("PROFESSOR");
    var s = db.gate(semesterId);
    time.requireOpen(s);
    catalog.requireSnapshot(s);
    var p = db.lock(Professor.class, actor.professorId());
    PeopleGuard.professor(p, actor);
    WebValues.match(expected, p.getVersion());
    List<Long> ids = command.offeringIds().stream().map(WebValues::id).toList();
    if (new HashSet<>(ids).size() != ids.size()) throw BusinessException.invalid("授课教学班不能重复");
    var eligible =
        db.list(
            CourseOffering.class,
            "select o from CourseOffering o where o.semester.id=:s and o.snapshot.id=:snap and"
                + " exists (select q from ProfessorQualification q where q.professor.id=:p and"
                + " q.course.id=o.course.id)",
            Map.of("s", semesterId, "snap", s.getActiveSnapshot().getId(), "p", p.getId()));
    var byId = new HashMap<Long, CourseOffering>();
    eligible.forEach(o -> byId.put(o.getId(), o));
    Set<Long> selected = new HashSet<>(ids);
    List<CourseOffering> targets = new ArrayList<>();
    for (Long id : ids) {
      var o = byId.get(id);
      if (o == null) throw BusinessException.forbidden();
      if (!"OPEN".equals(o.getStatus()))
        throw BusinessException.conflict("OFFERING_CLOSED", "该教学班已停止授课选择");
      if (o.getProfessor() != null && !o.getProfessor().getId().equals(p.getId()))
        throw BusinessException.conflict("OFFERING_TAKEN", "该教学班已由其他教师选择");
      targets.add(o);
    }
    var current = db.list(CourseOffering.class,
        "select o from CourseOffering o where o.semester.id=:s and o.professor.id=:p order by o.id",
        Map.of("s",semesterId,"p",p.getId()));
    Set<Long> lockIds = new HashSet<>(selected);
    current.forEach(o -> lockIds.add(o.getId()));
    db.lockOfferings(lockIds);
    for (var o : targets) {
      if (!"OPEN".equals(o.getStatus()))
        throw BusinessException.conflict("OFFERING_CLOSED", "教学班已关闭");
      if (o.getProfessor() != null && !o.getProfessor().getId().equals(p.getId()))
        throw BusinessException.conflict("OFFERING_TAKEN", "该教学班已由其他教师选择");
    }
    var issues = rules.conflicts(targets);
    if (!issues.isEmpty()) throw new BusinessException(409, "TIME_CONFLICT", "授课时间冲突", issues);
    for (var o : current) if (!selected.contains(o.getId())) o.setProfessor(null);
    for (var o : targets) o.setProfessor(p);
    db.bumpProfessor(p);
    audit.record(actor, "REPLACE_TEACHING", "PROFESSOR", p.getId());
    db.flush();
    return get(actor, semesterId);
  }
}
