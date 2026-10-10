package com.gm222.courseregistrationsystem.service.identity;

import com.gm222.courseregistrationsystem.exception.BusinessException;
import com.gm222.courseregistrationsystem.model.entity.*;
import com.gm222.courseregistrationsystem.repository.BusinessStore;
import com.gm222.courseregistrationsystem.service.BusinessTime;
import java.util.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AuthService {
  private final BusinessStore db;
  private final PasswordEncoder passwords;
  private final BusinessTime time;
  private final TransactionTemplate tx;
  private final String dummyHash;

  public AuthService(
      BusinessStore db,
      PasswordEncoder passwords,
      BusinessTime time,
      PlatformTransactionManager manager) {
    this.db = db;
    this.passwords = passwords;
    this.time = time;
    this.tx = new TransactionTemplate(manager);
    this.dummyHash = passwords.encode(UUID.randomUUID().toString());
  }

  public CurrentActor login(String username, String password) {
    if (password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72)
      throw BusinessException.invalid("密码不得超过 72 字节");
    // Authentication failure is raised after committing the counter transaction.
    CurrentActor actor =
        tx.execute(
            status -> {
              var found =
                  db.first(
                      Account.class,
                      "select a from Account a where a.username=:u",
                      Map.of("u", username.strip().toLowerCase(Locale.ROOT)));
              if (found.isEmpty()) {
                passwords.matches(password, dummyHash);
                return null;
              }
              var a = db.lock(Account.class, found.get().getId());
              if (!Boolean.TRUE.equals(a.getEnabled())
                  || a.getLockedUntil() != null && time.now().isBefore(a.getLockedUntil())) {
                passwords.matches(password, dummyHash);
                return null;
              }
              if (a.getLockedUntil() != null) {
                a.setLockedUntil(null);
                a.setFailedAttempts(0);
              }
              if (!passwords.matches(password, a.getPasswordHash())) {
                a.setFailedAttempts(a.getFailedAttempts() + 1);
                if (a.getFailedAttempts() >= 5) a.setLockedUntil(time.now().plusMinutes(15));
                return null;
              }
              a.setFailedAttempts(0);
              a.setLockedUntil(null);
              return resolve(a);
            });
    if (actor == null) throw new BusinessException(401, "UNAUTHENTICATED", "账号或密码错误，或账号暂不可用");
    return actor;
  }

  @Transactional(readOnly = true)
  public CurrentActor current() {
    var auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth == null || !(auth.getPrincipal() instanceof CurrentActor stored))
      throw unauthenticated();
    var a = db.get(Account.class, stored.actorId());
    if (!Boolean.TRUE.equals(a.getEnabled())
        || !a.getRole().equals(stored.role())
        || a.getLockedUntil() != null && time.now().isBefore(a.getLockedUntil()))
      throw unauthenticated();
    return resolve(a);
  }

  private CurrentActor resolve(Account a) {
    if ("STUDENT".equals(a.getRole())) {
      var s = a.getStudent();
      if (s == null || s.getDeletedAt() != null || !"ACTIVE".equals(s.getStatus()))
        throw unauthenticated();
      return new CurrentActor(a.getId(), a.getRole(), s.getId(), s.getName(), a.getUsername());
    }
    if ("PROFESSOR".equals(a.getRole())) {
      var p = a.getProfessor();
      if (p == null || p.getDeletedAt() != null || !"ACTIVE".equals(p.getStatus()))
        throw unauthenticated();
      return new CurrentActor(a.getId(), a.getRole(), p.getId(), p.getName(), a.getUsername());
    }
    if (!"REGISTRAR".equals(a.getRole())) throw unauthenticated();
    return new CurrentActor(a.getId(), a.getRole(), null, a.getUsername(), a.getUsername());
  }

  private BusinessException unauthenticated() {
    return new BusinessException(401, "UNAUTHENTICATED", "请重新登录");
  }
}
