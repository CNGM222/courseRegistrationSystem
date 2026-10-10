package com.gm222.courseregistrationsystem;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.gm222.courseregistrationsystem.model.entity.*;
import com.gm222.courseregistrationsystem.repository.BusinessStore;
import jakarta.servlet.http.Cookie;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class ApiSecurityTests {
  @Autowired MockMvc mvc;
  @Autowired BusinessStore db;
  @Autowired PlatformTransactionManager manager;
  @Autowired PasswordEncoder passwords;
  @Autowired ObjectMapper json;
  String username;
  Long id;

  @BeforeEach
  void seed() {
    username = "s-" + UUID.randomUUID();
    id =
        new TransactionTemplate(manager)
            .execute(
                status -> {
                  var a = new Account();
                  a.setUsername(username);
                  a.setPasswordHash(passwords.encode("test-only-password"));
                  a.setRole("STUDENT");
                  db.persist(a);
                  var s = new Student();
                  s.setAccount(a);
                  s.setName("学生");
                  s.setBirthDate(LocalDate.of(2005, 1, 1));
                  db.persist(s);
                  return a.getId();
                });
  }

  private Cookie login() throws Exception {
    var csrfResult = mvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isOk()).andReturn();
    var session = csrfResult.getResponse().getCookie("SESSION");
    var token =
        json.readTree(csrfResult.getResponse().getContentAsString())
            .path("data")
            .path("token")
            .asText();
    var result =
        mvc.perform(
                post("/api/v1/auth/login")
                    .cookie(session)
                    .header("X-CSRF-TOKEN", token)
                    .contentType("application/json")
                    .content(
                        json.writeValueAsString(
                            java.util.Map.of(
                                "username", username, "password", "test-only-password"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.accountId").value(id.toString()))
            .andExpect(jsonPath("$.data.role").value("STUDENT"))
            .andExpect(jsonPath("$.requestId").isNotEmpty())
            .andReturn();
    var rotated = result.getResponse().getCookie("SESSION");
    assertThat(rotated.getValue()).isNotEqualTo(session.getValue());
    return rotated;
  }

  @Test
  void sessionLoginCsrfRotationAndLogout() throws Exception {
    mvc.perform(get("/api/v1/semesters"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    mvc.perform(post("/api/v1/auth/login").contentType("application/json").content("{}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("CSRF_INVALID"));
    var session = login();
    mvc.perform(get("/api/v1/auth/me").cookie(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.username").value(username));
    mvc.perform(get("/api/v1/registrar/students").cookie(session))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/v1/registrar/semesters/1/offerings/1/roster").cookie(session))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/v1/professors/me/teaching/1").cookie(session))
        .andExpect(status().isForbidden());
    mvc.perform(post("/api/v1/auth/logout").cookie(session).with(csrf()))
        .andExpect(status().isNoContent());
    mvc.perform(get("/api/v1/auth/me").cookie(session)).andExpect(status().isUnauthorized());
  }

  @Test
  void failedPasswordCountersCommitAndLockAfterFiveFailures() throws Exception {
    for (int i = 0; i < 5; i++)
      mvc.perform(
              post("/api/v1/auth/login")
                  .with(csrf())
                  .contentType("application/json")
                  .content(
                      json.writeValueAsString(
                          java.util.Map.of("username", username, "password", "wrong"))))
          .andExpect(status().isUnauthorized());
    new TransactionTemplate(manager)
        .executeWithoutResult(
            status -> {
              var a = db.get(Account.class, id);
              assertThat(a.getFailedAttempts()).isEqualTo(5);
              assertThat(a.getLockedUntil()).isNotNull();
            });
    mvc.perform(
            post("/api/v1/auth/login")
                .with(csrf())
                .contentType("application/json")
                .content(
                    json.writeValueAsString(
                        java.util.Map.of("username", username, "password", "test-only-password"))))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void disablingAccountInvalidatesExistingSessionOnNextRequest() throws Exception {
    var session = login();
    new TransactionTemplate(manager)
        .executeWithoutResult(status -> db.get(Account.class, id).setEnabled(false));
    mvc.perform(get("/api/v1/auth/me").cookie(session))
        .andExpect(status().isUnauthorized())
        .andExpect(header().string("Cache-Control", "no-store"));
  }
}
