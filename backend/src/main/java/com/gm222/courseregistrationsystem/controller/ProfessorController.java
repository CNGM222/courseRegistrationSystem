package com.gm222.courseregistrationsystem.controller;

import com.gm222.courseregistrationsystem.model.dto.*;
import com.gm222.courseregistrationsystem.model.dto.ApiDtos.*;
import com.gm222.courseregistrationsystem.service.grading.GradeService;
import com.gm222.courseregistrationsystem.service.identity.AuthService;
import com.gm222.courseregistrationsystem.service.teaching.*;
import com.gm222.courseregistrationsystem.web.WebValues;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/professors/me")
public class ProfessorController {
  private final AuthService auth;
  private final TeachingAssignmentService teaching;
  private final RosterService roster;
  private final GradeService grades;

  public ProfessorController(
      AuthService auth,
      TeachingAssignmentService teaching,
      RosterService roster,
      GradeService grades) {
    this.auth = auth;
    this.teaching = teaching;
    this.roster = roster;
    this.grades = grades;
  }

  @GetMapping("/teaching/{semesterId}")
  public ResponseEntity<ApiResponse<TeachingPlan>> teaching(@PathVariable String semesterId) {
    var v = teaching.get(auth.current(), WebValues.id(semesterId));
    return WebValues.versioned(v, v.version());
  }

  @PutMapping("/teaching/{semesterId}")
  public ResponseEntity<ApiResponse<TeachingPlan>> save(
      @PathVariable String semesterId,
      @Valid @RequestBody TeachingCommand body,
      @RequestHeader(value = "If-Match", required = false) String match) {
    var v =
        teaching.replace(auth.current(), WebValues.id(semesterId), body, WebValues.version(match));
    return WebValues.versioned(v, v.version());
  }

  @GetMapping("/offerings/{offeringId}/roster")
  public ApiResponse<Roster> roster(@PathVariable String offeringId) {
    return ApiResponse.of(roster.get(auth.current(), WebValues.id(offeringId)));
  }

  @PutMapping("/offerings/{offeringId}/grades")
  public ApiResponse<Roster> grades(
      @PathVariable String offeringId, @Valid @RequestBody Grades body) {
    return ApiResponse.of(grades.update(auth.current(), WebValues.id(offeringId), body));
  }
}
