package com.gm222.courseregistrationsystem.service.billing;

import com.gm222.courseregistrationsystem.exception.BusinessException;
import com.gm222.courseregistrationsystem.model.dto.ApiDtos.*;
import com.gm222.courseregistrationsystem.model.entity.*;
import com.gm222.courseregistrationsystem.repository.BusinessStore;
import com.gm222.courseregistrationsystem.service.BusinessTime;
import com.gm222.courseregistrationsystem.service.identity.CurrentActor;
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
