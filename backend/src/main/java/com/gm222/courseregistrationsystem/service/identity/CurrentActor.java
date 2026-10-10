package com.gm222.courseregistrationsystem.service.identity;

import com.gm222.courseregistrationsystem.exception.BusinessException;
import com.gm222.courseregistrationsystem.model.dto.ApiDtos.User;
import java.io.Serializable;
import java.util.Arrays;

public record CurrentActor(
    Long actorId, String role, Long personId, String displayName, String username)
    implements Serializable, java.security.Principal {
  @Override
  public String getName() {
    return username;
  }

  public void requireRole(String... roles) {
    if (!Arrays.asList(roles).contains(role)) throw BusinessException.forbidden();
  }

  public Long studentId() {
    requireRole("STUDENT");
    return personId;
  }

  public Long professorId() {
    requireRole("PROFESSOR");
    return personId;
  }

  public User view() {
    return new User(
        actorId.toString(),
        personId == null ? null : personId.toString(),
        role,
        displayName,
        username);
  }
}
