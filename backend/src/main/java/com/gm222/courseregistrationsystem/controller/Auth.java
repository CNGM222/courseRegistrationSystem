package com.gm222.courseregistrationsystem.controller;

import com.gm222.courseregistrationsystem.model.dto.ApiDtos.*;
import com.gm222.courseregistrationsystem.model.dto.ApiResponse;
import com.gm222.courseregistrationsystem.service.identity.*;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class Auth {
  private final AuthService auth;
  private final SessionPolicy sessions;

  public Auth(AuthService auth, SessionPolicy sessions) {
    this.auth = auth;
    this.sessions = sessions;
  }

  @GetMapping("/csrf")
  public ApiResponse<Csrf> csrf(CsrfToken token) {
    return ApiResponse.of(new Csrf(token.getToken(), token.getHeaderName()));
  }

  @PostMapping("/login")
  public ApiResponse<User> login(
      @Valid @RequestBody Login body, HttpServletRequest request, HttpServletResponse response) {
    var actor = auth.login(body.username(), body.password());
    sessions.login(actor, request, response);
    return ApiResponse.of(actor.view());
  }

  @GetMapping("/me")
  public ApiResponse<User> me() {
    return ApiResponse.of(auth.current().view());
  }

  @PostMapping("/logout")
  public ResponseEntity<Void> logout(HttpServletRequest request) {
    sessions.logout(request);
    return ResponseEntity.noContent().build();
  }
}
