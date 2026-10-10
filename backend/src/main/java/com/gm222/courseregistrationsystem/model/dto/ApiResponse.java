package com.gm222.courseregistrationsystem.model.dto;

import com.gm222.courseregistrationsystem.web.RequestIds;

public record ApiResponse<T>(T data, String requestId) {
  public static <T> ApiResponse<T> of(T data) {
    return new ApiResponse<>(data, RequestIds.current());
  }
}
