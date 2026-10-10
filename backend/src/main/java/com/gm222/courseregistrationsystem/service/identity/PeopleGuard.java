package com.gm222.courseregistrationsystem.service.identity;

import com.gm222.courseregistrationsystem.exception.BusinessException;
import com.gm222.courseregistrationsystem.model.entity.*;

public final class PeopleGuard {
  private PeopleGuard() {}

  public static void student(Student s, CurrentActor a) {
    a.requireRole("STUDENT");
    if (!s.getId().equals(a.personId()) || !s.getAccount().getId().equals(a.actorId()))
      throw BusinessException.forbidden();
    if (s.getDeletedAt() != null
        || !"ACTIVE".equals(s.getStatus())
        || !Boolean.TRUE.equals(s.getAccount().getEnabled()))
      throw new BusinessException(401, "UNAUTHENTICATED", "学生账号已停用，请重新登录");
  }

  public static void professor(Professor p, CurrentActor a) {
    a.requireRole("PROFESSOR");
    if (!p.getId().equals(a.personId()) || !p.getAccount().getId().equals(a.actorId()))
      throw BusinessException.forbidden();
    if (p.getDeletedAt() != null
        || !"ACTIVE".equals(p.getStatus())
        || !Boolean.TRUE.equals(p.getAccount().getEnabled()))
      throw new BusinessException(401, "UNAUTHENTICATED", "教师账号已停用，请重新登录");
  }
}
