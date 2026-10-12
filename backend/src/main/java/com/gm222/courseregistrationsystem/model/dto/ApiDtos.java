package com.gm222.courseregistrationsystem.model.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public final class ApiDtos {
  private ApiDtos() {}

  public record Login(
      @NotBlank @Size(max = 64) String username, @NotBlank @Size(max = 128) String password) {
    @Override
    public String toString() {
      return "Login[credentials=<redacted>]";
    }
  }

  public record Csrf(String token, String headerName) {}

  public record User(
      String accountId, String personId, String role, String displayName, String username) {}

  public record Page<T>(
      List<T> items,
      int page,
      int pageSize,
      long total,
      String snapshotVerifiedAt,
      List<String> departments) {}

  public record SemesterView(
      String id,
      String code,
      String name,
      LocalDate startDate,
      LocalDate endDate,
      String registrationStart,
      String registrationEnd,
      String addDropEnd,
      String status,
      String currency,
      long version) {}

  public record RegistrationDeadlineCommand(@NotNull OffsetDateTime addDropEnd) {}

  public record Meeting(
      int weekNo, int weekday, int startPeriod, int endPeriodExclusive, String room) {}

  public record Offering(
      String id,
      String semesterId,
      String courseId,
      String courseCode,
      String courseName,
      String department,
      java.math.BigDecimal credits,
      String sectionCode,
      String professorId,
      String professorName,
      List<String> prerequisites,
      List<Meeting> meetings,
      int enrolledCount,
      int minStudents,
      int maxStudents,
      String status,
      String cancelReason,
      String tuition,
      String currency,
      long version) {}

  public record Draft(
      @NotNull @Size(max = 4) List<@NotBlank String> primaryOfferingIds,
      @NotNull @Size(max = 2) List<@NotBlank String> alternateOfferingIds) {}

  public record Revision(@NotNull @Positive Long draftRevision) {}

  public record Choice(String offeringId, String kind, int priority) {}

  public record EnrollmentView(
      String id,
      String offeringId,
      String state,
      String source,
      String reason,
      Offering offering) {}

  public record ScheduleView(
      String id,
      String semesterId,
      String status,
      long version,
      Long draftRevision,
      Long submittedRevision,
      long latestRevision,
      String firstSubmittedAt,
      List<Choice> draftChoices,
      List<Choice> submittedChoices,
      List<EnrollmentView> enrollments) {}

  public record Issue(
      String code, String field, String message, String offeringId, String enrollmentId) {
    public static Issue offering(String code, String message, Long id) {
      return new Issue(code, null, message, id == null ? null : id.toString(), null);
    }
  }

  public record Validation(boolean valid, List<Issue> issues, List<Offering> affectedOfferings) {}

  public record TeachingCommand(@NotNull @Size(max = 100) List<@NotBlank String> offeringIds) {}

  public record TeachingPlan(
      long version,
      List<Offering> eligibleOfferings,
      List<String> selectedOfferingIds,
      List<Offering> selectedOfferings) {}

  public record GradeEntry(
      @NotBlank String enrollmentId,
      @NotBlank @Pattern(regexp = "[ABCDFI]") String grade,
      @PositiveOrZero Long expectedVersion) {
    @Override
    public String toString() {
      return "GradeEntry[enrollmentId=" + enrollmentId + ", grade=<redacted>]";
    }
  }

  public record Grades(@NotNull @Size(min = 1, max = 10) List<@Valid GradeEntry> entries) {
    @Override
    public String toString() {
      return "Grades[entryCount=" + (entries == null ? 0 : entries.size()) + "]";
    }
  }

  public record RosterStudent(
      String enrollmentId, String studentId, String studentName, String grade, Long version) {
    @Override
    public String toString() {
      return "RosterStudent[enrollmentId=" + enrollmentId + ", grade=<redacted>]";
    }
  }

  public record Roster(Offering offering, boolean canEditGrades, List<RosterStudent> students) {
    @Override
    public String toString() {
      return "Roster[studentCount=" + (students == null ? 0 : students.size()) + "]";
    }
  }

  public record RegistrarRosterStudent(
      String enrollmentId, String studentId, String studentName, String source) {}

  public record RegistrarRoster(
      String semesterId, Offering offering, List<RegistrarRosterStudent> students) {}

  public record ReportItem(
      String enrollmentId,
      String offeringId,
      String courseCode,
      String courseName,
      String sectionCode,
      String professorName,
      String grade,
      Long version) {
    @Override
    public String toString() {
      return "ReportItem[enrollmentId=" + enrollmentId + ", grade=<redacted>]";
    }
  }

  public record ReportCard(String semesterId, String semesterName, List<ReportItem> items) {
    @Override
    public String toString() {
      return "ReportCard[semesterId="
          + semesterId
          + ", itemCount="
          + (items == null ? 0 : items.size())
          + "]";
    }
  }

  public record PersonCommand(
      @Size(max = 64) String username,
      @NotBlank @Size(max = 100) String name,
      @NotNull LocalDate birthDate,
      @NotBlank String status,
      LocalDate graduationDate,
      @Size(max = 100) String department,
      @Size(max = 11) String socialSecurityNumber,
      @Size(max = 128) String password) {
    @Override
    public String toString() {
      return "PersonCommand[personalData=<redacted>]";
    }
  }

  public record PasswordCommand(@NotBlank @Size(min = 12, max = 128) String password) {
    @Override
    public String toString() {
      return "PasswordCommand[credentials=<redacted>]";
    }
  }

  public record Person(
      String id,
      String accountId,
      String username,
      String name,
      LocalDate birthDate,
      String status,
      LocalDate graduationDate,
      String department,
      String ssnMasked,
      long accountVersion,
      long version) {}

  public record ClosedOffering(
      String offeringId,
      String courseName,
      String sectionCode,
      String status,
      int enrolledCount,
      String cancelReason) {}

  public record CloseResult(
      String runId,
      String semesterId,
      String closedAt,
      int cancelledOfferings,
      int leveledSchedules,
      int billingEvents,
      int pendingBillingEvents,
      Map<String, Object> summary,
      List<ClosedOffering> offerings) {}

  public record InvoiceItem(
      String offeringId,
      String courseCode,
      String courseName,
      String sectionCode,
      String professorName,
      String amount) {}

  public record Invoice(
      String id,
      String semesterId,
      String semesterName,
      String studentId,
      String studentName,
      String totalAmount,
      String currency,
      String createdAt,
      String deliveryState,
      String deliveredAt,
      List<InvoiceItem> items) {}

  public record BillingEvent(
      String eventId,
      String studentId,
      String studentName,
      String state,
      int attempts,
      String nextAttemptAt,
      String lastError,
      String deliveredAt) {}
}
