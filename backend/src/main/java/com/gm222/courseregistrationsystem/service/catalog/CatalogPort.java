package com.gm222.courseregistrationsystem.service.catalog;

import com.gm222.courseregistrationsystem.model.dto.ApiDtos.Meeting;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public interface CatalogPort {
  record CourseData(
      String legacyId,
      String code,
      String name,
      String department,
      BigDecimal credits,
      List<String> prerequisites) {}

  record OfferingData(
      String legacyId,
      String courseLegacyId,
      String sectionCode,
      BigDecimal tuition,
      String currency,
      List<Meeting> meetings) {}

  record Qualification(String professorId, String courseLegacyId) {}

  record ExternalCatalog(
      String semesterCode,
      String sourceVersion,
      List<CourseData> courses,
      List<OfferingData> offerings,
      List<Qualification> qualifications) {}

  ExternalCatalog fetch(String semesterCode, Instant deadline) throws Exception;

  String sourceKind();
}
