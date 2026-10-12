package com.gm222.courseregistrationsystem.service.registration;

import com.gm222.courseregistrationsystem.exception.BusinessException;
import com.gm222.courseregistrationsystem.model.dto.ApiDtos.RegistrationDeadlineCommand;
import com.gm222.courseregistrationsystem.model.dto.ApiDtos.SemesterView;
import com.gm222.courseregistrationsystem.model.entity.Semester;
import com.gm222.courseregistrationsystem.repository.BusinessStore;
import com.gm222.courseregistrationsystem.service.BusinessTime;
import com.gm222.courseregistrationsystem.service.audit.AuditService;
import com.gm222.courseregistrationsystem.service.catalog.Views;
import com.gm222.courseregistrationsystem.service.identity.CurrentActor;
import com.gm222.courseregistrationsystem.web.WebValues;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RegistrationControlService {
  private final BusinessStore db;
  private final BusinessTime time;
  private final Views views;
  private final AuditService audit;

  public RegistrationControlService(BusinessStore db, BusinessTime time, Views views, AuditService audit) {
    this.db = db;
    this.time = time;
    this.views = views;
    this.audit = audit;
  }

  @Transactional
  public SemesterView updateDeadline(
      CurrentActor actor, Long semesterId, RegistrationDeadlineCommand command, Long expectedVersion) {
    actor.requireRole("REGISTRAR");
    var semester = db.lock(Semester.class, semesterId);
    requireNotClosed(semester);
    WebValues.match(expectedVersion, semester.getVersion());
    var deadline =
        command
            .addDropEnd()
            .withOffsetSameInstant(ZoneOffset.UTC)
            .toLocalDateTime()
            .truncatedTo(ChronoUnit.MILLIS);
    if (!deadline.isAfter(semester.getRegistrationStart()))
      throw BusinessException.invalid("加退课截止时间必须晚于选课开始时间");
    if (!deadline.equals(semester.getAddDropEnd())) {
      setDeadline(semester, deadline);
      audit.record(actor, "UPDATE_REGISTRATION_DEADLINE", "SEMESTER", semesterId);
      db.flush();
    }
    return views.semester(semester);
  }

  @Transactional
  public SemesterView cutoffNow(CurrentActor actor, Long semesterId, Long expectedVersion) {
    actor.requireRole("REGISTRAR");
    var semester = db.lock(Semester.class, semesterId);
    WebValues.match(expectedVersion, semester.getVersion());
    if (!"OPEN".equals(semester.getStatus()))
      throw BusinessException.conflict("REGISTRATION_NOT_OPEN", "学期尚未开放或已关闭");
    var now = time.now();
    if (!now.isAfter(semester.getRegistrationStart()))
      throw BusinessException.conflict("REGISTRATION_NOT_STARTED", "选课尚未开始，不能立即截止");
    if (now.isBefore(semester.getAddDropEnd())) {
      setDeadline(semester, now);
      audit.record(actor, "MANUALLY_CUTOFF_REGISTRATION", "SEMESTER", semesterId);
      db.flush();
    }
    return views.semester(semester);
  }

  private void setDeadline(Semester semester, LocalDateTime deadline) {
    // Keep registration_start < registration_end <= add_drop_end when ending early.
    if (deadline.isBefore(semester.getRegistrationEnd()))
      semester.setRegistrationEnd(deadline);
    semester.setAddDropEnd(deadline);
  }

  private void requireNotClosed(Semester semester) {
    if ("CLOSED".equals(semester.getStatus()))
      throw BusinessException.conflict("REGISTRATION_ALREADY_CLOSED", "注册已关闭，不能再修改截止时间");
  }
}
