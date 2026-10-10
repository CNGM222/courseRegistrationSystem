package com.gm222.courseregistrationsystem.web;

import java.util.UUID;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

public final class RequestIds {
  private RequestIds() {}

  public static String current() {
    var attributes = RequestContextHolder.getRequestAttributes();
    if (attributes instanceof ServletRequestAttributes a
        && a.getRequest().getAttribute("requestId") instanceof String id) return id;
    return UUID.randomUUID().toString();
  }
}
