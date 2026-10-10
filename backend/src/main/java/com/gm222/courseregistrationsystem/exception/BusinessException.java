package com.gm222.courseregistrationsystem.exception;

import java.util.List;

public class BusinessException extends RuntimeException {
  private final int status;
  private final String code;
  private final List<?> details;

  public BusinessException(int status, String code, String message) {
    this(status, code, message, List.of());
  }

  public BusinessException(int status, String code, String message, List<?> details) {
    super(message);
    this.status = status;
    this.code = code;
    this.details = details;
  }

  public int status() {
    return status;
  }

  public String code() {
    return code;
  }

  public List<?> details() {
    return details;
  }

  public static BusinessException invalid(String message) {
    return new BusinessException(422, "VALIDATION_ERROR", message);
  }

  public static BusinessException missing(String message) {
    return new BusinessException(404, "NOT_FOUND", message);
  }

  public static BusinessException forbidden() {
    return new BusinessException(403, "FORBIDDEN", "无权执行此操作");
  }

  public static BusinessException conflict(String code, String message) {
    return new BusinessException(409, code, message);
  }
}
