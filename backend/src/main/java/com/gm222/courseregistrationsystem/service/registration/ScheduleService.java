package com.gm222.courseregistrationsystem.service.registration;

import com.gm222.courseregistrationsystem.exception.BusinessException;
import com.gm222.courseregistrationsystem.model.dto.ApiDtos.*;
import com.gm222.courseregistrationsystem.model.entity.*;
import com.gm222.courseregistrationsystem.repository.BusinessStore;
import com.gm222.courseregistrationsystem.service.BusinessTime;
import com.gm222.courseregistrationsystem.service.audit.AuditService;
import com.gm222.courseregistrationsystem.service.catalog.*;
import com.gm222.courseregistrationsystem.service.identity.*;
import com.gm222.courseregistrationsystem.web.WebValues;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ScheduleService {
  private final BusinessStore db;
  private final CatalogQueryService catalog;
  private final Views views;
  private final BusinessTime time;
  private final RegistrationRules rules;
  private final AuditService audit;

  public ScheduleService(
      BusinessStore db,
      CatalogQueryService catalog,
      Views views,
      BusinessTime time,
      RegistrationRules rules,
      AuditService audit) {
    this.db = db;
    this.catalog = catalog;
    this.views = views;
    this.time = time;
    this.rules = rules;
    this.audit = audit;
  }

  @Transactional(readOnly = true)
  public ScheduleView get(CurrentActor actor, Long semester) {
    PeopleGuard.student(db.get(Student.class, actor.studentId()), actor);
    return views.schedule(requireSchedule(actor, semester));
  }

  private Schedule requireSchedule(CurrentActor actor, Long semester) {
    db.get(Semester.class, semester);
    return db.schedule(actor.studentId(), semester)
        .orElseThrow(() -> new BusinessException(404, "SCHEDULE_NOT_FOUND", "本人尚未建立本学期课表"));
  }

  private Semester gate(CurrentActor actor, Long id) {
    actor.requireRole("STUDENT");
    var s = db.gate(id);
    time.requireOpen(s);
    PeopleGuard.student(db.lock(Student.class, actor.studentId()), actor);
    return s;
  }

  @Transactional
  public ScheduleView saveDraft(CurrentActor actor, Long semesterId, Draft command, Long expected) {
    var semester = gate(actor, semesterId);
    catalog.requireSnapshot(semester);
    if (command.primaryOfferingIds() == null
        || command.alternateOfferingIds() == null
        || command.primaryOfferingIds().size() > 4
        || command.alternateOfferingIds().size() > 2)
      throw BusinessException.invalid("草稿最多包含四门主选和两门备选");
    List<Long> primary = command.primaryOfferingIds().stream().map(WebValues::id).toList();
    List<Long> alternate = command.alternateOfferingIds().stream().map(WebValues::id).toList();
    List<Long> ids = new ArrayList<>(primary);
    ids.addAll(alternate);
    if (new HashSet<>(ids).size() != ids.size()) throw BusinessException.invalid("主选和备选不能重复");
    Set<String> courses = new HashSet<>();
    Map<Long, CourseOffering> offerings = new HashMap<>();
    for (Long id : ids) {
      var o = db.get(CourseOffering.class, id);
      catalog.requireOffering(semester, o);
      if (!courses.add(o.getCourse().getLegacyCourseId()))
        throw BusinessException.invalid(o.getCourse().getName() + "的不同教学班不能重复选择");
      offerings.put(id, o);
    }
    var found = db.schedule(actor.studentId(), semesterId);
    Schedule schedule;
    if (found.isPresent()) {
      schedule = db.lock(Schedule.class, found.get().getId());
      WebValues.match(expected, schedule.getVersion());
    } else {
      if (expected != null) throw new BusinessException(412, "PRECONDITION_FAILED", "课表尚不存在，请重新读取");
      schedule = new Schedule();
      schedule.setStudent(db.get(Student.class, actor.studentId()));
      schedule.setSemester(semester);
      db.persist(schedule);
    }
    long revision = schedule.getLatestRevision() + 1;
    schedule.setLatestRevision(revision);
    schedule.setDraftRevision(revision);
    if (schedule.getFirstSubmittedAt() == null) schedule.setStatus("DRAFT");
    append(schedule, revision, primary, "PRIMARY", offerings);
    append(schedule, revision, alternate, "ALTERNATE", offerings);
    audit.record(actor, "SAVE_DRAFT", "SCHEDULE", schedule.getId());
    db.flush();
    return views.schedule(schedule);
  }

  private void append(
      Schedule s, long revision, List<Long> ids, String kind, Map<Long, CourseOffering> offerings) {
    for (int i = 0; i < ids.size(); i++) {
      var c = new ScheduleChoice();
      c.setSchedule(s);
      c.setRevision(revision);
      c.setOffering(offerings.get(ids.get(i)));
      c.setKind(kind);
      c.setPriority((short) (i + 1));
      db.persist(c);
    }
  }

  private List<ScheduleChoice> currentDraft(Schedule s, Long revision) {
    if (revision == null || !revision.equals(s.getDraftRevision()))
      throw new BusinessException(412, "PRECONDITION_FAILED", "草稿版本已变化，请读取最新课表");
    return db.choices(s, revision);
  }

  private List<Issue> issues(Schedule s, List<ScheduleChoice> choices) {
    List<Issue> issues = new ArrayList<>();
    var primary =
        choices.stream()
            .filter(c -> "PRIMARY".equals(c.getKind()))
            .map(ScheduleChoice::getOffering)
            .toList();
    long alternate = choices.size() - primary.size();
    if (primary.size() > 4
        || alternate > 2
        || s.getFirstSubmittedAt() == null && (primary.size() != 4 || alternate != 2))
      issues.add(Issue.offering("CHOICE_COUNT", "首次提交必须四门主选、两门备选；后续最多四门主选、两门备选", null));
    Set<String> courseIds = new HashSet<>();
    for (var c : choices) {
      catalog.requireOffering(s.getSemester(), c.getOffering());
      if (!courseIds.add(c.getOffering().getCourse().getLegacyCourseId()))
        issues.add(Issue.offering("DUPLICATE_COURSE", "同门课程不能重复选择", c.getOffering().getId()));
    }
    Set<Long> retained =
        db.enrollments(s).stream()
            .filter(e -> "ENROLLED".equals(e.getState()))
            .map(e -> e.getOffering().getId())
            .collect(Collectors.toSet());
    issues.addAll(rules.check(primary, rules.passed(s.getStudent().getId()), retained, false));
    return issues;
  }

  @Transactional(readOnly = true)
  public Validation validate(CurrentActor actor, Long semesterId, Long revision) {
    var s = requireSchedule(actor, semesterId);
    PeopleGuard.student(s.getStudent(), actor);
    time.requireOpen(s.getSemester());
    catalog.requireSnapshot(s.getSemester());
    var choices = currentDraft(s, revision);
    var issues = issues(s, choices);
    return new Validation(
        issues.isEmpty(),
        issues,
        choices.stream().map(c -> views.offering(c.getOffering())).toList());
  }

  @Transactional
  public ScheduleView submit(CurrentActor actor, Long semesterId, Long revision, Long expected) {
    var semester = gate(actor, semesterId);
    catalog.requireSnapshot(semester);
    var s = db.lock(Schedule.class, requireSchedule(actor, semesterId).getId());
    WebValues.match(expected, s.getVersion());
    var choices = currentDraft(s, revision);
    var existing = db.enrollments(s);
    Set<Long> lockIds =
        choices.stream().map(c -> c.getOffering().getId()).collect(Collectors.toSet());
    existing.forEach(e -> lockIds.add(e.getOffering().getId()));
    db.lockOfferings(lockIds);
    var issues = issues(s, choices);
    if (!issues.isEmpty())
      throw new BusinessException(409, issues.getFirst().code(), "选课方案未通过校验", issues);
    Set<Long> target =
        choices.stream()
            .filter(c -> "PRIMARY".equals(c.getKind()))
            .map(c -> c.getOffering().getId())
            .collect(Collectors.toSet());
    for (var e : existing)
      if ("ENROLLED".equals(e.getState()) && !target.contains(e.getOffering().getId())) {
        db.release(e.getOffering());
        e.setState("DROPPED");
        e.setReason("STUDENT_DROPPED");
      }
    for (var c : choices)
      if ("PRIMARY".equals(c.getKind())) {
        var e =
            existing.stream()
                .filter(x -> x.getOffering().getId().equals(c.getOffering().getId()))
                .findFirst()
                .orElse(null);
        if (e == null || !"ENROLLED".equals(e.getState())) {
          db.reserve(c.getOffering());
          if (e == null) {
            e = new Enrollment();
            e.setSchedule(s);
            e.setOffering(c.getOffering());
            e.setSource("PRIMARY");
            db.persist(e);
          }
          e.setState("ENROLLED");
          e.setSource("PRIMARY");
          e.setReason(null);
        }
      }
    s.setSubmittedRevision(revision);
    s.setStatus("REGISTERED");
    if (s.getFirstSubmittedAt() == null) s.setFirstSubmittedAt(time.now());
    audit.record(actor, "SUBMIT_SCHEDULE", "SCHEDULE", s.getId());
    db.flush();
    return views.schedule(s);
  }

  @Transactional
  public void withdraw(CurrentActor actor, Long semesterId, Long expected) {
    gate(actor, semesterId);
    var s = db.lock(Schedule.class, requireSchedule(actor, semesterId).getId());
    WebValues.match(expected, s.getVersion());
    var enrolled = db.enrollments(s).stream().filter(e -> "ENROLLED".equals(e.getState())).toList();
    db.lockOfferings(enrolled.stream().map(e -> e.getOffering().getId()).toList());
    for (var e : enrolled) {
      db.release(e.getOffering());
      e.setState("DROPPED");
      e.setReason("SCHEDULE_WITHDRAWN");
    }
    s.setDraftRevision(null);
    s.setSubmittedRevision(null);
    s.setStatus("WITHDRAWN");
    audit.record(actor, "WITHDRAW_SCHEDULE", "SCHEDULE", s.getId());
  }
}
