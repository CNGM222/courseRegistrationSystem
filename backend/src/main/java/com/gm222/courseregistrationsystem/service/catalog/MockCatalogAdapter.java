package com.gm222.courseregistrationsystem.service.catalog;

import java.nio.file.*;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(name = "app.catalog.source", havingValue = "mock", matchIfMissing = true)
public class MockCatalogAdapter implements CatalogPort {
  private final String file;
  private final ObjectMapper json;

  public MockCatalogAdapter(@Value("${app.catalog.mock-file:}") String file, ObjectMapper json) {
    this.file = file;
    this.json = json;
  }

  public ExternalCatalog fetch(String code, Instant deadline) throws Exception {
    if (file.isBlank()) throw new IllegalStateException("未配置独立模拟目录文件");
    String content;
    if (file.startsWith("classpath:")) {
      try (var stream =
          new org.springframework.core.io.ClassPathResource(file.substring(10)).getInputStream()) {
        content = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
      }
    } else content = Files.readString(Path.of(file));
    var catalogs = json.readValue(content, ExternalCatalog[].class);
    for (var catalog : catalogs) if (code.equals(catalog.semesterCode())) return catalog;
    throw new IllegalStateException("模拟源不存在该学期");
  }

  public String sourceKind() {
    return "MOCK";
  }
}
