package com.gm222.courseregistrationsystem.controller;

import com.gm222.courseregistrationsystem.model.dto.*;
import com.gm222.courseregistrationsystem.model.dto.ApiDtos.*;
import com.gm222.courseregistrationsystem.service.billing.BillingQueryService;
import com.gm222.courseregistrationsystem.service.closing.RegistrationCloseService;
import com.gm222.courseregistrationsystem.service.identity.AuthService;
import com.gm222.courseregistrationsystem.service.registration.RegistrationControlService;
import com.gm222.courseregistrationsystem.service.teaching.RosterService;
import com.gm222.courseregistrationsystem.web.WebValues;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/registrar/semesters/{semesterId}")
public class RegistrarClosingController {
  private final RegistrationCloseService close;
  private final BillingQueryService billing;
  private final RegistrationControlService control;
  private final AuthService auth;
  private final RosterService rosters;

  public RegistrarClosingController(
      RegistrationCloseService close,
      BillingQueryService billing,
      RegistrationControlService control,
      AuthService auth,
      RosterService rosters) {
    this.close = close;
    this.billing = billing;
    this.control = control;
    this.auth = auth;
    this.rosters = rosters;
  }

  @PutMapping("/registration-deadline")
  public ResponseEntity<ApiResponse<SemesterView>> updateDeadline(
      @PathVariable String semesterId,
      @Valid @RequestBody RegistrationDeadlineCommand body,
      @RequestHeader(value = "If-Match", required = false) String match) {
    var result =
        control.updateDeadline(
            auth.current(), WebValues.id(semesterId), body, WebValues.version(match));
    return WebValues.versioned(result, result.version());
  }

  @PostMapping("/cutoff")
  public ResponseEntity<ApiResponse<SemesterView>> cutoff(
      @PathVariable String semesterId,
      @RequestHeader(value = "If-Match", required = false) String match) {
    var result =
        control.cutoffNow(auth.current(), WebValues.id(semesterId), WebValues.version(match));
    return WebValues.versioned(result, result.version());
  }

  @PostMapping("/close")
  public ApiResponse<CloseResult> close(@PathVariable String semesterId) {
    return ApiResponse.of(close.close(auth.current(), WebValues.id(semesterId)));
  }

  @GetMapping("/close-result")
  public ApiResponse<CloseResult> result(@PathVariable String semesterId) {
    return ApiResponse.of(close.get(auth.current(), WebValues.id(semesterId)));
  }

  @GetMapping("/offerings/{offeringId}/roster")
  public ResponseEntity<ApiResponse<RegistrarRoster>> roster(
      @PathVariable String semesterId, @PathVariable String offeringId) {
    var result =
        rosters.finalRoster(auth.current(), WebValues.id(semesterId), WebValues.id(offeringId));
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.of(result));
  }

  @GetMapping("/billing-events")
  public ApiResponse<Page<BillingEvent>> events(
      @PathVariable String semesterId,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int pageSize,
      @RequestParam(defaultValue = "") String state) {
    return ApiResponse.of(
        billing.events(auth.current(), WebValues.id(semesterId), page, pageSize, state));
  }

  @GetMapping("/students/{studentId}/invoice")
  public ResponseEntity<ApiResponse<Invoice>> invoice(
      @PathVariable String semesterId, @PathVariable String studentId) {
    var result = billing.forStudent(
        auth.current(), WebValues.id(semesterId), WebValues.id(studentId));
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.of(result));
  }
}
