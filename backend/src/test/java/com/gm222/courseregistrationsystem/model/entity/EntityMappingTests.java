package com.gm222.courseregistrationsystem.model.entity;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.Table;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class EntityMappingTests {
    @PersistenceContext
    private EntityManager entityManager;

    @Test
    void mapsAllNineteenBusinessTables() {
        assertThat(entityManager.getMetamodel().getEntities())
                .extracting(entity -> entity.getJavaType().getAnnotation(Table.class).name())
                .containsExactlyInAnyOrder("accounts", "students", "professors", "semester", "catalog_snapshot",
                        "courses", "course_prerequisite", "course_offering", "offering_meeting",
                        "professor_qualification", "schedule", "schedule_choice", "enrollment", "grade",
                        "registration_close", "billing_invoice", "billing_invoice_item", "outbox_event", "audit_log");
    }

    @Test
    void roundTripsCompositeKeysSharedPrimaryKeyAndCalendarTypes() {
        Registration data = registration();
        Course prerequisite = course(data.offering().getSnapshot(), "PRE101");
        CoursePrerequisite relation = new CoursePrerequisite(data.offering().getCourse().getId(),
                prerequisite.getId(), data.offering().getCourse(), prerequisite);
        entityManager.persist(relation);
        ProfessorQualification qualification = new ProfessorQualification();
        qualification.setProfessorId(data.professor().getId());
        qualification.setCourseId(data.offering().getCourse().getId());
        qualification.setProfessor(data.professor());
        qualification.setCourse(data.offering().getCourse());
        entityManager.persist(qualification);

        Grade grade = new Grade();
        grade.setEnrollment(data.enrollment());
        grade.setValue("I");
        grade.setRecordedBy(data.professor());
        entityManager.persist(grade);
        entityManager.flush();
        entityManager.clear();

        CoursePrerequisite restored = entityManager.find(CoursePrerequisite.class,
                new CoursePrerequisiteId(relation.getCourseId(), relation.getPrerequisiteCourseId()));
        assertThat(restored.getPrerequisiteCourse().getCode()).isEqualTo("PRE101");
        ProfessorQualification restoredQualification = entityManager.find(ProfessorQualification.class,
                new ProfessorQualificationId(qualification.getProfessorId(), qualification.getCourseId()));
        assertThat(restoredQualification.getProfessor().getBirthDate()).isEqualTo(LocalDate.of(1980, 1, 2));
        assertThat(restoredQualification.getImportedAt()).isNotNull();
        Grade restoredGrade = entityManager.find(Grade.class, data.enrollment().getId());
        assertThat(restoredGrade.getEnrollmentId()).isEqualTo(data.enrollment().getId());
        assertThat(restoredGrade.getEnrollment().getOffering().getId()).isEqualTo(data.offering().getId());
        assertThat(restoredGrade.getValue()).isEqualTo("I");
        assertThat(restoredGrade.getVersion()).isZero();
        assertThat(restoredGrade.getRecordedAt()).isNotNull();
        Semester semester = entityManager.find(Semester.class, data.semester().getId());
        assertThat(semester.getStartDate()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(semester.getCurrency()).isEqualTo("CNY");
        assertThat(semester.getActiveSnapshot().getSemester().getId()).isEqualTo(semester.getId());
        Student student = entityManager.find(Student.class, data.schedule().getStudent().getId());
        assertThat(student.getSsnCiphertext()).containsExactly((byte) 1, (byte) 2, (byte) 3);
        assertThat(student.getSsnFingerprint()).hasSize(32);
    }

    @Test
    void roundTripsFrozenBillingJsonAndUpdatesDeliveryState() {
        Registration data = registration();
        RegistrationClose close = new RegistrationClose();
        close.setSemester(data.semester());
        close.setRegistrarAccount(account("REGISTRAR"));
        close.setSummary(Map.of("invoiceCount", 1, "cancelledCount", 0));
        entityManager.persist(close);

        BillingInvoice invoice = new BillingInvoice();
        invoice.setSchedule(data.schedule());
        invoice.setStudent(data.schedule().getStudent());
        invoice.setSemester(data.semester());
        invoice.setRegistrationClose(close);
        invoice.setTotalAmount(new BigDecimal("600.00"));
        entityManager.persist(invoice);

        BillingInvoiceItem item = new BillingInvoiceItem();
        item.setInvoice(invoice);
        item.setOffering(data.offering());
        item.setCourseCode("CS101");
        item.setCourseName("Software Engineering");
        item.setSectionCode("01");
        item.setProfessorName(data.professor().getName());
        item.setMeetingSnapshot(List.of(Map.of("weekNo", 1, "weekday", 2, "startPeriod", 1,
                "endPeriodExclusive", 3)));
        item.setAmount(new BigDecimal("600.00"));
        entityManager.persist(item);

        OutboxEvent event = new OutboxEvent();
        event.setInvoice(invoice);
        event.setPayload(Map.of("eventId", event.getEventId(), "totalAmount", "600.00", "items", List.of()));
        entityManager.persist(event);
        AuditLog audit = new AuditLog();
        audit.setAction("REGISTRATION_CLOSE");
        audit.setTargetType("SEMESTER");
        audit.setTargetId(data.semester().getId().toString());
        audit.setRequestId(UUID.randomUUID().toString());
        audit.setDetail(Map.of("runId", close.getRunId()));
        entityManager.persist(audit);
        entityManager.flush();
        entityManager.clear();

        BillingInvoiceItem restoredItem = entityManager.find(BillingInvoiceItem.class, item.getId());
        assertThat(restoredItem.getMeetingSnapshot()).hasSize(1);
        assertThat(restoredItem.getMeetingSnapshot().getFirst()).containsEntry("endPeriodExclusive", 3);
        assertThat(restoredItem.getAmount()).isEqualByComparingTo("600.00");
        BillingInvoice restoredInvoice = restoredItem.getInvoice();
        assertThat(restoredInvoice.getRegistrationClose().getSummary()).containsEntry("invoiceCount", 1);
        assertThat(restoredInvoice.getCurrency()).isEqualTo("CNY");
        assertThat(restoredInvoice.getCreatedAt()).isNotNull();
        OutboxEvent restoredEvent = entityManager.find(OutboxEvent.class, event.getEventId());
        assertThat(restoredEvent.getPayload()).containsEntry("eventId", event.getEventId());
        assertThat(restoredEvent.getNextAttemptAt()).isEqualTo(restoredEvent.getCreatedAt());
        assertThat(entityManager.find(AuditLog.class, audit.getId()).getDetail()).containsEntry("runId", close.getRunId());

        restoredInvoice.setTotalAmount(BigDecimal.ZERO);
        restoredItem.setCourseName("Changed after closing");
        restoredEvent.setPayload(Map.of("totalAmount", "0.00"));
        restoredEvent.setState("RETRY");
        restoredEvent.setAttempts(1);
        entityManager.flush();
        entityManager.clear();

        assertThat(entityManager.find(BillingInvoice.class, invoice.getId()).getTotalAmount()).isEqualByComparingTo("600.00");
        assertThat(entityManager.find(BillingInvoiceItem.class, item.getId()).getCourseName()).isEqualTo("Software Engineering");
        restoredEvent = entityManager.find(OutboxEvent.class, event.getEventId());
        assertThat(restoredEvent.getState()).isEqualTo("RETRY");
        assertThat(restoredEvent.getAttempts()).isEqualTo(1);
        assertThat(restoredEvent.getPayload()).containsEntry("totalAmount", "600.00");
    }

    @Test
    void auditsInUtcAndIncrementsVersionWithoutChangingCreationTime() {
        LocalDateTime before = LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1);
        Account account = account("STUDENT");
        entityManager.flush();
        LocalDateTime createdAt = account.getCreatedAt();
        assertThat(createdAt).isAfter(before).isBefore(LocalDateTime.now(ZoneOffset.UTC).plusSeconds(1));
        assertThat(createdAt.getNano() % 1_000_000).isZero();
        assertThat(account.getVersion()).isZero();
        account.setEnabled(false);
        entityManager.flush();
        assertThat(account.getCreatedAt()).isEqualTo(createdAt);
        assertThat(account.getUpdatedAt()).isAfterOrEqualTo(createdAt);
        assertThat(account.getVersion()).isEqualTo(1L);
    }

    @Test
    void supportsEmptyLogicalDraftAndKeepsSavedChoicesImmutable() {
        Registration data = registration();
        data.schedule().setLatestRevision(2L);
        data.schedule().setDraftRevision(2L);
        data.choice().setPriority((short) 4);
        entityManager.flush();
        entityManager.clear();
        Schedule restored = entityManager.find(Schedule.class, data.schedule().getId());
        assertThat(restored.getDraftRevision()).isEqualTo(2L);
        assertThat(entityManager.createQuery("select count(c) from ScheduleChoice c where c.schedule = :schedule and c.revision = 2", Long.class)
                .setParameter("schedule", restored).getSingleResult()).isZero();
        assertThat(entityManager.find(ScheduleChoice.class, data.choice().getId()).getPriority()).isEqualTo((short) 1);
    }

    @Test
    void rejectsDuplicateStudentSemesterSchedule() {
        Registration data = registration();
        Schedule duplicate = new Schedule();
        duplicate.setStudent(data.schedule().getStudent());
        duplicate.setSemester(data.semester());
        assertThatThrownBy(() -> {
            entityManager.persist(duplicate);
            entityManager.flush();
        }).isInstanceOf(PersistenceException.class);
    }

    @Test
    void rejectsDuplicateEnrollment() {
        Registration data = registration();
        Enrollment duplicate = new Enrollment();
        duplicate.setSchedule(data.schedule());
        duplicate.setOffering(data.offering());
        duplicate.setSource("PRIMARY");
        assertThatThrownBy(() -> {
            entityManager.persist(duplicate);
            entityManager.flush();
        }).isInstanceOf(PersistenceException.class);
    }

    private Registration registration() {
        Student student = new Student();
        student.setAccount(account("STUDENT"));
        student.setName("Test Student");
        student.setBirthDate(LocalDate.of(2005, 1, 2));
        student.setSsnCiphertext(new byte[]{1, 2, 3});
        student.setSsnFingerprint(new byte[32]);
        entityManager.persist(student);
        Professor professor = new Professor();
        professor.setAccount(account("PROFESSOR"));
        professor.setName("Test Professor");
        professor.setBirthDate(LocalDate.of(1980, 1, 2));
        professor.setDepartment("CS");
        entityManager.persist(professor);
        Semester semester = new Semester();
        semester.setCode("2026-FALL");
        semester.setName("Fall 2026");
        semester.setStartDate(LocalDate.of(2026, 9, 1));
        semester.setEndDate(LocalDate.of(2027, 1, 15));
        semester.setRegistrationStart(LocalDateTime.of(2026, 8, 1, 0, 0));
        semester.setRegistrationEnd(LocalDateTime.of(2026, 8, 20, 0, 0));
        semester.setAddDropEnd(LocalDateTime.of(2026, 9, 5, 0, 0));
        entityManager.persist(semester);
        CatalogSnapshot snapshot = new CatalogSnapshot();
        snapshot.setSemester(semester);
        snapshot.setSourceKind("MOCK");
        snapshot.setContentHash("a".repeat(64));
        entityManager.persist(snapshot);
        semester.setActiveSnapshot(snapshot);
        CourseOffering offering = new CourseOffering();
        offering.setSemester(semester);
        offering.setSnapshot(snapshot);
        offering.setCourse(course(snapshot, "CS101"));
        offering.setLegacyOfferingId("CS101-01");
        offering.setSectionCode("01");
        offering.setProfessor(professor);
        offering.setTuition(new BigDecimal("600.00"));
        entityManager.persist(offering);
        OfferingMeeting meeting = new OfferingMeeting();
        meeting.setOffering(offering);
        meeting.setWeekNo((short) 1);
        meeting.setWeekday((short) 2);
        meeting.setStartPeriod((short) 1);
        meeting.setEndPeriodExclusive((short) 3);
        meeting.setRoom("101");
        entityManager.persist(meeting);
        Schedule schedule = new Schedule();
        schedule.setStudent(student);
        schedule.setSemester(semester);
        schedule.setLatestRevision(1L);
        schedule.setDraftRevision(1L);
        entityManager.persist(schedule);
        ScheduleChoice choice = new ScheduleChoice();
        choice.setSchedule(schedule);
        choice.setOffering(offering);
        choice.setRevision(1L);
        choice.setKind("PRIMARY");
        choice.setPriority((short) 1);
        entityManager.persist(choice);
        Enrollment enrollment = new Enrollment();
        enrollment.setSchedule(schedule);
        enrollment.setOffering(offering);
        enrollment.setSource("PRIMARY");
        entityManager.persist(enrollment);
        entityManager.flush();
        return new Registration(semester, professor, offering, schedule, choice, enrollment);
    }

    private Account account(String role) {
        Account account = new Account();
        account.setUsername(UUID.randomUUID().toString());
        account.setPasswordHash("synthetic-hash-for-mapping-test");
        account.setRole(role);
        entityManager.persist(account);
        return account;
    }

    private Course course(CatalogSnapshot snapshot, String code) {
        Course course = new Course();
        course.setSnapshot(snapshot);
        course.setLegacyCourseId(code);
        course.setCode(code);
        course.setName(code);
        course.setDepartment("CS");
        course.setCredits(new BigDecimal("3.0"));
        entityManager.persist(course);
        return course;
    }

    private record Registration(Semester semester, Professor professor, CourseOffering offering,
                                Schedule schedule, ScheduleChoice choice, Enrollment enrollment) {
    }
}
