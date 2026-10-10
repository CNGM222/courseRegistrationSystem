package com.gm222.courseregistrationsystem.controller;

import com.gm222.courseregistrationsystem.model.dto.*;
import com.gm222.courseregistrationsystem.model.dto.ApiDtos.ReportCard;
import com.gm222.courseregistrationsystem.service.grading.ReportCardService;
import com.gm222.courseregistrationsystem.service.identity.AuthService;
import com.gm222.courseregistrationsystem.web.WebValues;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/students/me/report-cards")
public class ReportCardController {
  private final ReportCardService cards;
  private final AuthService auth;

  public ReportCardController(ReportCardService cards, AuthService auth) {
    this.cards = cards;
    this.auth = auth;
  }

  @GetMapping("/{semesterId}")
  public ApiResponse<ReportCard> get(@PathVariable String semesterId) {
    return ApiResponse.of(cards.mine(auth.current(), WebValues.id(semesterId)));
  }
}
