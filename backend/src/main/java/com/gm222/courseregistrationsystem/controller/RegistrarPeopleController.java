package com.gm222.courseregistrationsystem.controller;

import com.gm222.courseregistrationsystem.model.dto.*;
import com.gm222.courseregistrationsystem.model.dto.ApiDtos.*;
import com.gm222.courseregistrationsystem.service.identity.AuthService;
import com.gm222.courseregistrationsystem.service.people.PersonService;
import com.gm222.courseregistrationsystem.web.WebValues;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/registrar")
public class RegistrarPeopleController {
  private final PersonService people;
  private final AuthService auth;

  public RegistrarPeopleController(PersonService people, AuthService auth) {
    this.people = people;
    this.auth = auth;
  }

  @GetMapping("/{kind}")
  public ApiResponse<Page<Person>> list(
      @PathVariable String kind,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int pageSize,
      @RequestParam(defaultValue = "") String keyword) {
    check(kind);
    return ApiResponse.of(people.list(auth.current(), kind, page, pageSize, keyword));
  }

  @GetMapping("/{kind}/{id}")
  public ApiResponse<Person> get(@PathVariable String kind, @PathVariable String id) {
    check(kind);
    return ApiResponse.of(people.get(auth.current(), kind, WebValues.id(id)));
  }

  @PostMapping("/{kind}")
  public ResponseEntity<ApiResponse<Person>> create(
      @PathVariable String kind, @Valid @RequestBody PersonCommand body) {
    check(kind);
    var r = people.create(auth.current(), kind, body);
    return WebValues.versioned(r.data(), r.version());
  }

  @PutMapping("/{kind}/{id}")
  public ResponseEntity<ApiResponse<Person>> update(
      @PathVariable String kind,
      @PathVariable String id,
      @Valid @RequestBody PersonCommand body,
      @RequestHeader(value = "If-Match", required = false) String match) {
    check(kind);
    var r = people.update(auth.current(), kind, WebValues.id(id), body, WebValues.version(match));
    return WebValues.versioned(r.data(), r.version());
  }

  @DeleteMapping("/{kind}/{id}")
  public ResponseEntity<Void> delete(
      @PathVariable String kind,
      @PathVariable String id,
      @RequestHeader(value = "If-Match", required = false) String match) {
    check(kind);
    people.delete(auth.current(), kind, WebValues.id(id), WebValues.version(match));
    return ResponseEntity.noContent().build();
  }

  private void check(String kind) {
    if (!kind.equals("students") && !kind.equals("professors"))
      throw com.gm222.courseregistrationsystem.exception.BusinessException.missing("人员类型不存在");
  }
}
