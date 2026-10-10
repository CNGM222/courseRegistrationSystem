package com.gm222.courseregistrationsystem.controller;

import com.gm222.courseregistrationsystem.model.dto.*;
import com.gm222.courseregistrationsystem.model.dto.ApiDtos.*;
import com.gm222.courseregistrationsystem.service.catalog.CatalogQueryService;
import com.gm222.courseregistrationsystem.service.identity.AuthService;
import com.gm222.courseregistrationsystem.web.WebValues;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class CatalogController {
  private final CatalogQueryService catalog;
  private final AuthService auth;

  public CatalogController(CatalogQueryService catalog, AuthService auth) {
    this.catalog = catalog;
    this.auth = auth;
  }

  @GetMapping("/semesters")
  public ApiResponse<List<SemesterView>> semesters() {
    return ApiResponse.of(catalog.semesters(auth.current()));
  }

  @GetMapping("/semesters/{id}/offerings")
  public ApiResponse<Page<Offering>> offerings(
      @PathVariable String id,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int pageSize,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String department) {
    return ApiResponse.of(
        catalog.offerings(auth.current(), WebValues.id(id), page, pageSize, keyword, department));
  }

  @GetMapping("/offerings/{id}")
  public ApiResponse<Offering> offering(@PathVariable String id) {
    return ApiResponse.of(catalog.offering(auth.current(), WebValues.id(id)));
  }

  @GetMapping("/semesters/{id}/offering-availability")
  public ApiResponse<List<Offering>> availability(
      @PathVariable String id, @RequestParam(defaultValue = "") List<String> ids) {
    return ApiResponse.of(
        catalog.availability(
            auth.current(), WebValues.id(id), ids.stream().map(WebValues::id).toList()));
  }
}
