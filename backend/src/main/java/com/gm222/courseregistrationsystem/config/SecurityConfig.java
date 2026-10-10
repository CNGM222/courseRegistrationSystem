package com.gm222.courseregistrationsystem.config;

import com.gm222.courseregistrationsystem.web.ApiErrors;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.List;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.*;
import tools.jackson.databind.ObjectMapper;

@Configuration
@org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
public class SecurityConfig {
  @Bean
  public SecurityFilterChain security(HttpSecurity http, ObjectMapper json) throws Exception {
    http.authorizeHttpRequests(
            a ->
                a.requestMatchers("/api/v1/auth/csrf", "/api/v1/auth/login", "/error")
                    .permitAll()
                    .requestMatchers("/api/v1/students/**")
                    .hasRole("STUDENT")
                    .requestMatchers("/api/v1/professors/**")
                    .hasRole("PROFESSOR")
                    .requestMatchers("/api/v1/registrar/**")
                    .hasRole("REGISTRAR")
                    .anyRequest()
                    .authenticated())
        .csrf(
            c ->
                c.csrfTokenRepository(new HttpSessionCsrfTokenRepository())
                    .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
        .exceptionHandling(
            e ->
                e.authenticationEntryPoint(
                        (q, r, x) -> error(json, q, r, 401, "UNAUTHENTICATED", "请先登录"))
                    .accessDeniedHandler(
                        (q, r, x) ->
                            error(
                                json,
                                q,
                                r,
                                403,
                                x instanceof CsrfException ? "CSRF_INVALID" : "FORBIDDEN",
                                x instanceof CsrfException ? "安全令牌失效，请刷新后重试" : "无权执行此操作")))
        .requestCache(c -> c.disable())
        .formLogin(c -> c.disable())
        .httpBasic(c -> c.disable())
        .logout(c -> c.disable());
    return http.build();
  }

  private void error(
      ObjectMapper json,
      HttpServletRequest q,
      HttpServletResponse r,
      int status,
      String code,
      String message)
      throws IOException {
    r.setStatus(status);
    r.setContentType("application/json;charset=UTF-8");
    r.getWriter()
        .write(
            json.writeValueAsString(
                new ApiErrors.Error(
                    code, message, List.of(), (String) q.getAttribute("requestId"))));
  }
}
