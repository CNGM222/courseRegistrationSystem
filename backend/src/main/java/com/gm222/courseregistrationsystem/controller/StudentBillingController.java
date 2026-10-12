package com.gm222.courseregistrationsystem.controller;

import com.gm222.courseregistrationsystem.model.dto.ApiDtos.Invoice;
import com.gm222.courseregistrationsystem.model.dto.ApiResponse;
import com.gm222.courseregistrationsystem.service.billing.BillingQueryService;
import com.gm222.courseregistrationsystem.service.identity.AuthService;
import com.gm222.courseregistrationsystem.web.WebValues;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/students/me/invoices")
public class StudentBillingController {
  private final BillingQueryService billing;
  private final AuthService auth;

  public StudentBillingController(BillingQueryService billing, AuthService auth) {
    this.billing = billing;
    this.auth = auth;
  }

  @GetMapping("/{semesterId}")
  public ResponseEntity<ApiResponse<Invoice>> invoice(@PathVariable String semesterId) {
    var result = billing.mine(auth.current(), WebValues.id(semesterId));
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.of(result));
  }
}
