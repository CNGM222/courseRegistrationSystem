package com.gm222.courseregistrationsystem.service;

import com.gm222.courseregistrationsystem.exception.BusinessException;
import com.gm222.courseregistrationsystem.model.entity.Semester;
import java.time.*;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Component;

@Component
public class BusinessTime {
  private final Clock clock;

  public BusinessTime(Clock clock) {
    this.clock = clock;
  }

  public LocalDateTime now() {
    return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
  }

  public LocalDate today() {
    return LocalDate.now(clock.withZone(ZoneId.of("Asia/Shanghai")));
  }

  public static String iso(LocalDateTime t) {
    return t == null ? null : t.atOffset(ZoneOffset.UTC).toString();
  }

  public void requireOpen(Semester s) {
    var now = now();
    if (!"OPEN".equals(s.getStatus())
        || now.isBefore(s.getRegistrationStart())
        || !now.isBefore(s.getAddDropEnd()))
      throw BusinessException.conflict("REGISTRATION_CLOSED", "当前不在允许选课或授课变更的时段");
  }

  public boolean ended(Semester s) {
    return "CLOSED".equals(s.getStatus()) && today().isAfter(s.getEndDate());
  }
}
