package com.gm222.courseregistrationsystem.web;

import com.gm222.courseregistrationsystem.exception.BusinessException;
import com.gm222.courseregistrationsystem.model.dto.ApiResponse;
import java.util.Objects;
import org.springframework.http.ResponseEntity;

public final class WebValues {
  private WebValues() {}

  public static long id(String value) {
    try {
      if (value == null || !value.matches("[1-9][0-9]{0,18}")) throw new NumberFormatException();
      return Long.parseLong(value);
    } catch (NumberFormatException e) {
      throw BusinessException.invalid("ID 必须是有效的正整数");
    }
  }

  public static Long version(String header) {
    if (header == null) return null;
    if (!header.matches("\"[0-9]{1,16}\"")) throw BusinessException.invalid("If-Match 必须使用带引号的版本号");
    long value = Long.parseLong(header.substring(1, header.length() - 1));
    if (value > 9007199254740991L) throw BusinessException.invalid("版本号超出支持范围");
    return value;
  }

  public static void match(Long expected, Long actual) {
    if (expected == null)
      throw new BusinessException(428, "PRECONDITION_REQUIRED", "此操作需要 If-Match 版本号");
    if (!Objects.equals(expected, actual))
      throw new BusinessException(412, "PRECONDITION_FAILED", "数据已更新，请读取最新结果");
  }

  public static <T> ResponseEntity<ApiResponse<T>> versioned(T data, long version) {
    return ResponseEntity.ok().eTag("\"" + version + "\"").body(ApiResponse.of(data));
  }

  public static void page(int page, int pageSize) {
    if (page < 1 || page > 1000000 || pageSize < 1 || pageSize > 100)
      throw BusinessException.invalid("分页参数不合法，pageSize 为 1 至 100");
  }
}
