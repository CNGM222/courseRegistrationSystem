package com.gm222.courseregistrationsystem.controller;

import com.gm222.courseregistrationsystem.model.dto.*;
import com.gm222.courseregistrationsystem.model.dto.ApiDtos.*;
import com.gm222.courseregistrationsystem.service.identity.AuthService;
import com.gm222.courseregistrationsystem.service.registration.ScheduleService;
import com.gm222.courseregistrationsystem.web.WebValues;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/students/me/schedules/{semesterId}")
public class StudentController {
  private final ScheduleService schedules;
  private final AuthService auth;

  public StudentController(ScheduleService schedules, AuthService auth) {
    this.schedules = schedules;
    this.auth = auth;
  }

  @GetMapping
  public ResponseEntity<ApiResponse<ScheduleView>> get(@PathVariable String semesterId) {
    var v = schedules.get(auth.current(), WebValues.id(semesterId));
    return WebValues.versioned(v, v.version());
  }

  @PutMapping("/draft")
  public ResponseEntity<ApiResponse<ScheduleView>> draft(
      @PathVariable String semesterId,
      @Valid @RequestBody Draft body,
      @RequestHeader(value = "If-Match", required = false) String match) {
    var v =
        schedules.saveDraft(
            auth.current(), WebValues.id(semesterId), body, WebValues.version(match));
    return WebValues.versioned(v, v.version());
  }

  @PostMapping("/validate")
  public ApiResponse<Validation> validate(
      @PathVariable String semesterId, @Valid @RequestBody Revision body) {
    return ApiResponse.of(
        schedules.validate(auth.current(), WebValues.id(semesterId), body.draftRevision()));
  }

  @PostMapping("/submit")
  public ResponseEntity<ApiResponse<ScheduleView>> submit(
      @PathVariable String semesterId,
      @Valid @RequestBody Revision body,
      @RequestHeader(value = "If-Match", required = false) String match) {
    var v =
        schedules.submit(
            auth.current(),
            WebValues.id(semesterId),
            body.draftRevision(),
            WebValues.version(match));
    return WebValues.versioned(v, v.version());
  }

  @DeleteMapping
  public ResponseEntity<Void> delete(
      @PathVariable String semesterId,
      @RequestHeader(value = "If-Match", required = false) String match) {
    schedules.withdraw(auth.current(), WebValues.id(semesterId), WebValues.version(match));
    return ResponseEntity.noContent().build();
  }
}
