package com.gm222.courseregistrationsystem.service.closing;

import com.gm222.courseregistrationsystem.exception.BusinessException;
import com.gm222.courseregistrationsystem.model.dto.ApiDtos.*;
import com.gm222.courseregistrationsystem.model.entity.*;
import com.gm222.courseregistrationsystem.repository.BusinessStore;
import com.gm222.courseregistrationsystem.service.*;
import com.gm222.courseregistrationsystem.service.audit.AuditService;
import com.gm222.courseregistrationsystem.service.catalog.Views;
import com.gm222.courseregistrationsystem.service.identity.CurrentActor;
import com.gm222.courseregistrationsystem.service.registration.RegistrationRules;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RegistrationCloseService {
  private final BusinessStore db;
  private final BusinessTime time;
  private final RegistrationRules rules;
  private final Views views;
  private final AuditService audit;

  public RegistrationCloseService(
      BusinessStore db,
      BusinessTime time,
      RegistrationRules rules,
      Views views,
      AuditService audit) {
    this.db = db;
    this.time = time;
    this.rules = rules;
    this.views = views;
    this.audit = audit;
  }

  private void registrar(CurrentActor a) {
    a.requireRole("REGISTRAR");
  }

  @Transactional(timeout = 90)
  public CloseResult close(CurrentActor actor, Long semesterId) {
    registrar(actor);
    var s = db.closeGate(semesterId);
    if ("CLOSED".equals(s.getStatus())) return result(s);
    if (!"OPEN".equals(s.getStatus()))
      throw BusinessException.conflict("REGISTRATION_NOT_OPEN", "学期尚未开放或正在准备中");
    if (time.now().isBefore(s.getAddDropEnd()))
      throw BusinessException.conflict("REGISTRATION_NOT_ENDED", "尚未到加退课截止时间");
    if (s.getActiveSnapshot() == null)
      throw BusinessException.conflict("CLOSE_INVARIANT_FAILED", "学期缺少发布目录，关闭已取消");
    var offerings =
        db.offerings(semesterId).stream()
            .filter(o -> o.getSnapshot().getId().equals(s.getActiveSnapshot().getId()))
            .toList();
    db.lockOfferings(offerings.stream().map(CourseOffering::getId).toList());
    for (var o : offerings) {
      long actual =
          db.count(
              "select count(e) from Enrollment e where e.offering.id=:o and e.state='ENROLLED'",
              Map.of("o", o.getId()));
      if (actual != o.getEnrolledCount())
        throw BusinessException.conflict("COUNT_MISMATCH", "教学班人数与注册记录不一致，关闭已取消");
    }
    int cancelled = 0, promoted = 0;
    // First remove classes without a teacher, releasing every effective registration.
    for (var o : offerings)
      if (o.getProfessor() == null && "OPEN".equals(o.getStatus())) {
        cancelOffering(o, "NO_PROFESSOR");
        cancelled++;
      }
    var schedules =
        db.list(
            Schedule.class,
            "select s from Schedule s where s.semester.id=:s and s.firstSubmittedAt is not null"
                + " order by s.firstSubmittedAt,s.student.id",
            Map.of("s", semesterId));
    Set<Long> promotedStudents = new HashSet<>();
    for (var schedule : schedules) {
      var current =
          db.enrollments(schedule).stream().filter(e -> "ENROLLED".equals(e.getState())).toList();
      if (current.size() >= 4) continue;
      var already =
          current.stream()
              .map(e -> e.getOffering().getCourse().getLegacyCourseId())
              .collect(java.util.stream.Collectors.toSet());
      var alternatives =
          db.choices(schedule, schedule.getSubmittedRevision()).stream()
              .filter(c -> "ALTERNATE".equals(c.getKind()))
              .sorted(Comparator.comparing(ScheduleChoice::getPriority))
              .toList();
      for (var choice : alternatives) {
        if (db.enrollments(schedule).stream().filter(e -> "ENROLLED".equals(e.getState())).count()
            >= 4) break;
        var candidate = choice.getOffering();
        if (!"OPEN".equals(candidate.getStatus())
            || candidate.getProfessor() == null
            || already.contains(candidate.getCourse().getLegacyCourseId())
            || candidate.getEnrolledCount() >= candidate.getMaxStudents()) continue;
        var test = new ArrayList<CourseOffering>();
        db.enrollments(schedule).stream()
            .filter(e -> "ENROLLED".equals(e.getState()))
            .forEach(e -> test.add(e.getOffering()));
        test.add(candidate);
        if (!rules
                .check(
                    List.of(candidate),
                    rules.passed(schedule.getStudent().getId()),
                    test.stream()
                        .map(CourseOffering::getId)
                        .collect(java.util.stream.Collectors.toSet()),
                    true)
                .isEmpty()
            || !rules.conflicts(test).isEmpty()) continue;
        db.reserve(candidate);
        var e =
            db.enrollments(schedule).stream()
                .filter(x -> x.getOffering().getId().equals(candidate.getId()))
                .findFirst()
                .orElse(null);
        if (e == null) {
          e = new Enrollment();
          e.setSchedule(schedule);
          e.setOffering(candidate);
          e.setSource("ALTERNATE");
          db.persist(e);
        }
        e.setState("ENROLLED");
        e.setSource("ALTERNATE");
        e.setReason(null);
        already.add(candidate.getCourse().getLegacyCourseId());
        promoted++;
        promotedStudents.add(schedule.getId());
      }
    }
    // A final minimum-size pass cancels small classes and releases their registrations.
    for (var o : offerings)
      if ("OPEN".equals(o.getStatus())) {
        if (o.getEnrolledCount() < o.getMinStudents()) {
          cancelOffering(o, "BELOW_MINIMUM");
          cancelled++;
        } else o.setStatus("CLOSED");
      }
    int invoices = 0;
    for (var schedule : schedules) {
      schedule.setStatus("FINALIZED");
      invoices++;
    }
    var drafts =
        db.list(
            Schedule.class,
            "select s from Schedule s where s.semester.id=:s and s.firstSubmittedAt is null",
            Map.of("s", semesterId));
    drafts.forEach(x -> x.setStatus("EXPIRED"));
    List<Map<String, Object>> finalOfferings = new ArrayList<>();
    for (var o : offerings)
      finalOfferings.add(
          Map.of(
              "offeringId",
              o.getId().toString(),
              "courseName",
              o.getCourse().getName(),
              "sectionCode",
              o.getSectionCode(),
              "status",
              o.getStatus(),
              "enrolledCount",
              o.getEnrolledCount(),
              "cancelReason",
              Objects.toString(o.getCancelReason(), "")));
    Map<String, Object> summary = new LinkedHashMap<>();
    summary.put("cancelledOfferings", cancelled);
    summary.put("leveledSchedules", promotedStudents.size());
    summary.put("promotedEnrollments", promoted);
    summary.put("billingEvents", invoices);
    summary.put("pendingBillingEvents", invoices);
    summary.put("offerings", finalOfferings);
    var close = new RegistrationClose();
    close.setSemester(s);
    close.setRegistrarAccount(db.get(Account.class, actor.actorId()));
    close.setClosedAt(time.now());
    close.setSummary(summary);
    db.persist(close);
    s.setRegistrationClose(close);
    for (var schedule : schedules) createInvoice(schedule, s, close);
    s.setStatus("CLOSED");
    s.setClosedAt(time.now());
    audit.record(actor, "CLOSE_REGISTRATION", "SEMESTER", semesterId);
    db.flush();
    return result(s);
  }

  private void cancelOffering(CourseOffering o, String reason) {
    var enrollments =
        db.list(
            Enrollment.class,
            "select e from Enrollment e where e.offering.id=:o and e.state='ENROLLED' order by"
                + " e.id",
            Map.of("o", o.getId()));
    for (var e : enrollments) {
      db.release(o);
      e.setState("CANCELLED");
      e.setReason(reason);
    }
    o.setStatus("CANCELLED");
    o.setCancelReason(reason);
  }

  private void createInvoice(Schedule schedule, Semester semester, RegistrationClose close) {
    var effective =
        db.enrollments(schedule).stream().filter(e -> "ENROLLED".equals(e.getState())).toList();
    if (effective.size() > 4)
      throw BusinessException.conflict("CLOSE_INVARIANT_FAILED", "学生有效课程超过四门，关闭已取消");
    BigDecimal total = new BigDecimal("0.00");
    for (var e : effective) {
      var o = e.getOffering();
      if (!"CLOSED".equals(o.getStatus())
          || o.getProfessor() == null
          || o.getEnrolledCount() < 3
          || o.getEnrolledCount() > 10
          || !semester.getCurrency().equals(o.getCurrency()))
        throw BusinessException.conflict("CLOSE_INVARIANT_FAILED", "最终教学班或计费币种不一致，关闭已取消");
      total = total.add(o.getTuition()).setScale(2);
    }
    var invoice = new BillingInvoice();
    invoice.setSchedule(schedule);
    invoice.setStudent(schedule.getStudent());
    invoice.setSemester(semester);
    invoice.setRegistrationClose(close);
    invoice.setTotalAmount(total);
    invoice.setCurrency(semester.getCurrency());
    db.persist(invoice);
    List<Map<String, Object>> items = new ArrayList<>();
    for (var e : effective) {
      var o = e.getOffering();
      var item = new BillingInvoiceItem();
      item.setInvoice(invoice);
      item.setOffering(o);
      item.setCourseCode(o.getCourse().getCode());
      item.setCourseName(o.getCourse().getName());
      item.setSectionCode(o.getSectionCode());
      item.setProfessorName(o.getProfessor().getName());
      item.setAmount(o.getTuition());
      List<Map<String, Object>> meetings = new ArrayList<>();
      for (var m : db.meetings(o.getId())) {
        Map<String, Object> slot = new LinkedHashMap<>();
        slot.put("weekNo", m.getWeekNo());
        slot.put("weekday", m.getWeekday());
        slot.put("startPeriod", m.getStartPeriod());
        slot.put("endPeriodExclusive", m.getEndPeriodExclusive());
        if (m.getRoom() != null) slot.put("room", m.getRoom());
        meetings.add(slot);
      }
      item.setMeetingSnapshot(meetings);
      db.persist(item);
      items.add(
          Map.of(
              "offeringId",
              o.getId().toString(),
              "courseCode",
              o.getCourse().getCode(),
              "courseName",
              o.getCourse().getName(),
              "sectionCode",
              o.getSectionCode(),
              "professorName",
              o.getProfessor().getName(),
              "meetings",
              meetings,
              "amount",
              o.getTuition().setScale(2).toPlainString()));
    }
    var event = new OutboxEvent();
    event.setInvoice(invoice);
    event.setNextAttemptAt(time.now());
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("eventId", event.getEventId());
    payload.put("eventType", "INVOICE_FINALIZED");
    payload.put("studentId", schedule.getStudent().getId().toString());
    payload.put("semesterId", semester.getId().toString());
    payload.put("semesterCode", semester.getCode());
    payload.put("currency", semester.getCurrency());
    payload.put("items", items);
    payload.put("totalAmount", total.toPlainString());
    event.setPayload(payload);
    db.persist(event);
  }

  @Transactional(readOnly = true)
  public CloseResult get(CurrentActor actor, Long semesterId) {
    registrar(actor);
    return result(db.get(Semester.class, semesterId));
  }

  private CloseResult result(Semester s) {
    var close = s.getRegistrationClose();
    if (close == null) throw new BusinessException(404, "REGISTRATION_NOT_CLOSED", "该学期尚未关闭注册");
    var sum = close.getSummary();
    var list = new ArrayList<ClosedOffering>();
    Object raw = sum.get("offerings");
    if (raw instanceof List<?> rows)
      for (Object row : rows)
        if (row instanceof Map<?, ?> m)
          list.add(
              new ClosedOffering(
                  String.valueOf(m.get("offeringId")),
                  String.valueOf(m.get("courseName")),
                  String.valueOf(m.get("sectionCode")),
                  String.valueOf(m.get("status")),
                  Integer.parseInt(String.valueOf(m.get("enrolledCount"))),
                  String.valueOf(m.get("cancelReason"))));
    return new CloseResult(
        close.getRunId(),
        s.getId().toString(),
        BusinessTime.iso(close.getClosedAt()),
        number(sum, "cancelledOfferings"),
        number(sum, "leveledSchedules"),
        number(sum, "billingEvents"),
        number(sum, "pendingBillingEvents"),
        sum,
        list);
  }

  private int number(Map<String, Object> m, String key) {
    return m.get(key) == null ? 0 : Integer.parseInt(String.valueOf(m.get(key)));
  }
}
