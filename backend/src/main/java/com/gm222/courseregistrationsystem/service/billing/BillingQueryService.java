package com.gm222.courseregistrationsystem.service.billing;

import com.gm222.courseregistrationsystem.exception.BusinessException;
import com.gm222.courseregistrationsystem.model.dto.ApiDtos.*;
import com.gm222.courseregistrationsystem.model.entity.*;
import com.gm222.courseregistrationsystem.repository.BusinessStore;
import com.gm222.courseregistrationsystem.service.BusinessTime;
import com.gm222.courseregistrationsystem.service.identity.CurrentActor;
import com.gm222.courseregistrationsystem.service.identity.PeopleGuard;
import com.gm222.courseregistrationsystem.web.WebValues;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BillingQueryService {
  private final BusinessStore db;

  public BillingQueryService(BusinessStore db) {
    this.db = db;
  }

  @Transactional(readOnly = true)
  public Invoice mine(CurrentActor actor, Long semesterId) {
    var student = db.get(Student.class, actor.studentId());
    PeopleGuard.student(student, actor);
    return invoice(semesterId, student.getId());
  }

  @Transactional(readOnly = true)
  public Invoice forStudent(CurrentActor actor, Long semesterId, Long studentId) {
    actor.requireRole("REGISTRAR");
    return invoice(semesterId, studentId);
  }

  private Invoice invoice(Long semesterId, Long studentId) {
    var semester = db.get(Semester.class, semesterId);
    if (!"CLOSED".equals(semester.getStatus()))
      throw new BusinessException(409, "INVOICE_NOT_READY", "注册尚未最终关闭，账单尚未生成");
    var invoice =
        db.first(
                BillingInvoice.class,
                "select i from BillingInvoice i where i.semester.id=:s and i.student.id=:p",
                Map.of("s", semesterId, "p", studentId))
            .orElseThrow(
                () -> new BusinessException(404, "INVOICE_NOT_FOUND", "该学期没有账单，仅有草稿或从未提交选课不会生成账单"));
    var items =
        db.list(
                BillingInvoiceItem.class,
                "select i from BillingInvoiceItem i where i.invoice.id=:i order by i.courseCode,i.sectionCode,i.id",
                Map.of("i", invoice.getId()))
            .stream()
            .map(i -> new InvoiceItem(
                i.getOffering().getId().toString(),
                i.getCourseCode(),
                i.getCourseName(),
                i.getSectionCode(),
                i.getProfessorName(),
                i.getAmount().setScale(2).toPlainString()))
            .toList();
    var event = db.first(
        OutboxEvent.class,
        "select e from OutboxEvent e where e.invoice.id=:i and e.eventType='INVOICE_FINALIZED'",
        Map.of("i", invoice.getId())).orElse(null);
    return new Invoice(
        invoice.getId().toString(),
        semesterId.toString(),
        semester.getName(),
        studentId.toString(),
        invoice.getStudent().getName(),
        invoice.getTotalAmount().setScale(2).toPlainString(),
        invoice.getCurrency(),
        BusinessTime.iso(invoice.getCreatedAt()),
        event == null ? null : event.getState(),
        event == null ? null : BusinessTime.iso(event.getDeliveredAt()),
        items);
  }

  @Transactional(readOnly = true)
  public Page<BillingEvent> events(
      CurrentActor actor, Long semester, int page, int size, String state) {
    actor.requireRole("REGISTRAR");
    WebValues.page(page, size);
    db.get(Semester.class, semester);
    if (state != null
        && !state.isBlank()
        && !Set.of("PENDING", "IN_FLIGHT", "RETRY", "DELIVERED").contains(state))
      throw BusinessException.invalid("通知状态不合法");
    String where = " where e.invoice.semester.id=:s";
    Map<String, Object> p = new HashMap<>(Map.of("s", semester));
    if (state != null && !state.isBlank()) {
      where += " and e.state=:state";
      p.put("state", state);
    }
    var rows =
        db.page(
            OutboxEvent.class,
            "select e from OutboxEvent e" + where + " order by e.nextAttemptAt,e.eventId",
            p,
            page,
            size);
    var total = db.count("select count(e) from OutboxEvent e" + where, p);
    return new Page<>(
        rows.stream()
            .map(
                e ->
                    new BillingEvent(
                        e.getEventId(),
                        e.getInvoice().getStudent().getId().toString(),
                        e.getInvoice().getStudent().getName(),
                        e.getState(),
                        e.getAttempts(),
                        "DELIVERED".equals(e.getState()) ? null : BusinessTime.iso(e.getNextAttemptAt()),
                        e.getLastError(),
                        BusinessTime.iso(e.getDeliveredAt())))
            .toList(),
        page,
        size,
        total,
        null,
        List.of());
  }
}
