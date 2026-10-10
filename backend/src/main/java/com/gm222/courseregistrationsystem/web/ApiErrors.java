package com.gm222.courseregistrationsystem.web;

import com.gm222.courseregistrationsystem.exception.BusinessException;
import jakarta.validation.ConstraintViolationException;
import java.util.*;
import org.springframework.dao.*;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiErrors {
  public record Error(String code, String message, List<?> details, String requestId) {}

  @ExceptionHandler(BusinessException.class)
  public ResponseEntity<Error> business(BusinessException e) {
    return response(e.status(), e.code(), e.getMessage(), e.details());
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<Error> validation(MethodArgumentNotValidException e) {
    return response(
        422,
        "VALIDATION_ERROR",
        "请修正输入内容",
        e.getBindingResult().getFieldErrors().stream()
            .map(f -> Map.of("field", f.getField(), "message", "输入格式或长度不符合要求"))
            .toList());
  }

  @ExceptionHandler({
    HttpMessageNotReadableException.class,
    MethodArgumentTypeMismatchException.class,
    ConstraintViolationException.class
  })
  public ResponseEntity<Error> malformed(Exception e) {
    return response(422, "VALIDATION_ERROR", "请求格式或参数不正确", List.of());
  }

  @ExceptionHandler({
    org.springframework.web.servlet.resource.NoResourceFoundException.class,
    org.springframework.web.servlet.NoHandlerFoundException.class
  })
  public ResponseEntity<Error> notFound(Exception e) {
    return response(404, "NOT_FOUND", "指定接口或资源不存在", List.of());
  }

  @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
  public ResponseEntity<Error> method(Exception e) {
    return response(405, "METHOD_NOT_ALLOWED", "此接口不支持该请求方法", List.of());
  }

  @ExceptionHandler({
    org.springframework.web.bind.MissingServletRequestParameterException.class,
    org.springframework.web.method.annotation.HandlerMethodValidationException.class
  })
  public ResponseEntity<Error> parameters(Exception e) {
    return response(422, "VALIDATION_ERROR", "请求参数缺失或不合法", List.of());
  }

  @ExceptionHandler({
    PessimisticLockingFailureException.class,
    jakarta.persistence.LockTimeoutException.class,
    jakarta.persistence.PessimisticLockException.class
  })
  public ResponseEntity<Error> busy(Exception e) {
    return response(409, "REGISTRATION_BUSY", "有业务变更正在处理，请读取最新结果后重试", List.of());
  }

  @ExceptionHandler({
    ObjectOptimisticLockingFailureException.class,
    jakarta.persistence.OptimisticLockException.class
  })
  public ResponseEntity<Error> stale(Exception e) {
    return response(412, "PRECONDITION_FAILED", "数据已更新，请读取最新结果", List.of());
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  public ResponseEntity<Error> integrity(Exception e) {
    return response(409, "DATA_CONFLICT", "数据重复或存在关联限制，请核对输入", List.of());
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<Error> unexpected(Exception e) {
    org.slf4j.LoggerFactory.getLogger(ApiErrors.class)
        .error("请求 {} 处理失败 ({})", RequestIds.current(), e.getClass().getSimpleName());
    return response(500, "INTERNAL_ERROR", "服务暂时无法完成操作，请通过请求编号联系管理员", List.of());
  }

  private ResponseEntity<Error> response(int status, String code, String message, List<?> details) {
    return ResponseEntity.status(status)
        .body(new Error(code, message, details, RequestIds.current()));
  }
}
