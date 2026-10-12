package com.gm222.courseregistrationsystem;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.gm222.courseregistrationsystem.exception.BusinessException;
import com.gm222.courseregistrationsystem.model.dto.ApiDtos.*;
import com.gm222.courseregistrationsystem.model.entity.*;
import com.gm222.courseregistrationsystem.repository.BusinessStore;
import com.gm222.courseregistrationsystem.service.BusinessTime;
import com.gm222.courseregistrationsystem.service.billing.OutboxDispatcher;
import com.gm222.courseregistrationsystem.service.billing.BillingQueryService;
import com.gm222.courseregistrationsystem.service.catalog.*;
import com.gm222.courseregistrationsystem.service.closing.RegistrationCloseService;
import com.gm222.courseregistrationsystem.service.grading.*;
import com.gm222.courseregistrationsystem.service.identity.*;
import com.gm222.courseregistrationsystem.service.people.PersonService;
import com.gm222.courseregistrationsystem.service.registration.*;
import com.gm222.courseregistrationsystem.service.teaching.*;
import java.math.BigDecimal;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class BusinessWorkflowTests {
  @Autowired BusinessStore db;
  @Autowired PlatformTransactionManager manager;
  @Autowired BusinessTime time;
  @Autowired ScheduleService schedules;
  @Autowired RegistrationCloseService close;
  @Autowired GradeService grades;
  @Autowired ReportCardService cards;
  @Autowired RosterService rosters;
  @Autowired TeachingAssignmentService teaching;
  @Autowired PersonService people;
  @Autowired RegistrationControlService registrationControl;
  @Autowired PasswordEncoder passwords;
  @Autowired CatalogQueryService catalog;
  @Autowired OutboxDispatcher outbox;
  @Autowired BillingQueryService billing;
  @Autowired MockMvc mvc;
  @Autowired DataSource datasource;
  TransactionTemplate tx;
  Fixture f;

  record Fixture(
      Long semester,
      CurrentActor student,
      CurrentActor professor,
      CurrentActor registrar,
      List<Long> offerings) {}

  @BeforeEach
  void setup() {
    tx = new TransactionTemplate(manager);
    f = tx.execute(status -> fixture());
  }

  private Account account(String role) {
    var a = new Account();
    a.setUsername(UUID.randomUUID().toString());
    a.setPasswordHash("mapping-only");
    a.setRole(role);
    db.persist(a);
    return a;
  }

  private Fixture fixture() {
    var a = account("STUDENT");
    var student = new Student();
    student.setAccount(a);
    student.setName("学生");
    student.setBirthDate(LocalDate.of(2005, 1, 1));
    db.persist(student);
    var pa = account("PROFESSOR");
    var professor = new Professor();
    professor.setAccount(pa);
    professor.setName("教师");
    professor.setBirthDate(LocalDate.of(1980, 1, 1));
    professor.setDepartment("CS");
    db.persist(professor);
    var ra = account("REGISTRAR");
    var s = new Semester();
    s.setCode(UUID.randomUUID().toString().substring(0, 20));
    s.setName("测试学期");
    s.setStartDate(time.today().minusMonths(1));
    s.setEndDate(time.today().plusMonths(3));
    s.setRegistrationStart(time.now().minusDays(10));
    s.setRegistrationEnd(time.now().plusDays(1));
    s.setAddDropEnd(time.now().plusDays(2));
    s.setStatus("OPEN");
    db.persist(s);
    var snap = new CatalogSnapshot();
    snap.setSemester(s);
    snap.setSourceKind("MOCK");
    snap.setStatus("PUBLISHED");
    snap.setVerifiedAt(time.now());
    db.persist(snap);
    s.setActiveSnapshot(snap);
    List<Long> ids = new ArrayList<>();
    for (int i = 0; i < 8; i++) {
      var c = new Course();
      c.setSnapshot(snap);
      c.setLegacyCourseId("C" + i);
      c.setCode("C" + i);
      c.setName("课程" + i);
      c.setDepartment(i == 7 ? "Math" : "CS");
      c.setCredits(new BigDecimal("3.0"));
      db.persist(c);
      var o = new CourseOffering();
      o.setCourse(c);
      o.setSemester(s);
      o.setSnapshot(snap);
      o.setLegacyOfferingId("O" + i);
      o.setSectionCode("01");
      o.setProfessor(professor);
      o.setTuition(new BigDecimal("600.00"));
      db.persist(o);
      ids.add(o.getId());
      var q = new ProfessorQualification();
      q.setProfessorId(professor.getId());
      q.setCourseId(c.getId());
      q.setProfessor(professor);
      q.setCourse(c);
      db.persist(q);
      var m = new OfferingMeeting();
      m.setOffering(o);
      m.setWeekNo((short) 1);
      m.setWeekday((short) (i % 7 + 1));
      m.setStartPeriod((short) (i < 7 ? 1 : 4));
      m.setEndPeriodExclusive((short) (i < 7 ? 3 : 6));
      db.persist(m);
    }
    db.flush();
    return new Fixture(
        s.getId(),
        new CurrentActor(a.getId(), "STUDENT", student.getId(), student.getName(), a.getUsername()),
        new CurrentActor(
            pa.getId(), "PROFESSOR", professor.getId(), professor.getName(), pa.getUsername()),
        new CurrentActor(ra.getId(), "REGISTRAR", null, "教务", ra.getUsername()),
        ids);
  }

  private Draft draft(int... primary) {
    return new Draft(
        Arrays.stream(primary).mapToObj(i -> f.offerings.get(i).toString()).toList(),
        List.of(f.offerings.get(4).toString(), f.offerings.get(5).toString()));
  }

  private ScheduleView submitted() {
    var d = schedules.saveDraft(f.student, f.semester, draft(0, 1, 2, 3), null);
    return schedules.submit(f.student, f.semester, d.draftRevision(), d.version());
  }

  private void expired() {
    tx.executeWithoutResult(
        status -> {
          var s = db.get(Semester.class, f.semester);
          s.setRegistrationEnd(time.now().minusDays(2));
          s.setAddDropEnd(time.now().minusDays(1));
        });
  }

  @Test
  void draftsNeverReserveAndFirstSubmitRequiresFourPlusTwo() {
    var d = schedules.saveDraft(f.student, f.semester, draft(0, 1, 2), null);
    assertThat(d.enrollments()).isEmpty();
    assertThat(schedules.validate(f.student, f.semester, d.draftRevision()).valid()).isFalse();
    assertThatThrownBy(
            () -> schedules.submit(f.student, f.semester, d.draftRevision(), d.version()))
        .isInstanceOf(BusinessException.class);
    assertThat(
            tx.<Short>execute(
                status -> db.get(CourseOffering.class, f.offerings.getFirst()).getEnrolledCount()))
        .isZero();
    var v = schedules.saveDraft(f.student, f.semester, draft(0, 1, 2, 3), d.version());
    var r = schedules.submit(f.student, f.semester, v.draftRevision(), v.version());
    assertThat(r.enrollments()).hasSize(4);
    assertThat(r.submittedChoices()).hasSize(6);
    var newer = schedules.saveDraft(f.student, f.semester, draft(0, 1), r.version());
    assertThat(newer.status()).isEqualTo("REGISTERED");
    assertThat(newer.enrollments()).hasSize(4);
    assertThat(newer.submittedChoices()).hasSize(6);
    assertThatThrownBy(
            () -> schedules.submit(f.student, f.semester, newer.draftRevision(), r.version()))
        .isInstanceOf(BusinessException.class)
        .extracting("status")
        .isEqualTo(412);
  }

  @Test
  void capacityFailureKeepsExistingEnrollmentAndCounts() {
    var old = submitted();
    tx.executeWithoutResult(
        status -> db.get(CourseOffering.class, f.offerings.get(6)).setEnrolledCount((short) 10));
    var d = schedules.saveDraft(f.student, f.semester, draft(0, 1, 2, 6), old.version());
    assertThatThrownBy(
            () -> schedules.submit(f.student, f.semester, d.draftRevision(), d.version()))
        .isInstanceOf(BusinessException.class);
    assertThat(schedules.get(f.student, f.semester).enrollments())
        .extracting(EnrollmentView::offeringId)
        .contains(f.offerings.get(3).toString())
        .doesNotContain(f.offerings.get(6).toString());
    assertThat(
            tx.<Short>execute(
                status -> db.get(CourseOffering.class, f.offerings.get(3)).getEnrolledCount()))
        .isEqualTo((short) 1);
  }

  @Test
  void withdrawReleasesSeatsAndReusesHistoricalEnrollment() {
    var old = submitted();
    schedules.withdraw(f.student, f.semester, old.version());
    var empty = schedules.get(f.student, f.semester);
    assertThat(empty.firstSubmittedAt()).isNotNull();
    assertThat(empty.submittedChoices()).isEmpty();
    assertThat(empty.enrollments()).allMatch(e -> "DROPPED".equals(e.state()));
    var d =
        schedules.saveDraft(
            f.student,
            f.semester,
            new Draft(List.of(f.offerings.getFirst().toString()), List.of()),
            empty.version());
    var v = schedules.submit(f.student, f.semester, d.draftRevision(), d.version());
    assertThat(v.enrollments()).hasSize(4);
    assertThat(v.enrollments().stream().filter(e -> e.state().equals("ENROLLED"))).hasSize(1);
    assertThat(v.enrollments().getFirst().id()).isEqualTo(old.enrollments().getFirst().id());
  }

  @Test
  void closeIsAtomicIdempotentAndBillsZeroForSubmittedStudents() {
    submitted();
    expired();
    var result = close.close(f.registrar, f.semester);
    assertThat(result.cancelledOfferings()).isEqualTo(8);
    assertThat(result.billingEvents()).isEqualTo(1);
    assertThat(close.close(f.registrar, f.semester).runId()).isEqualTo(result.runId());
    assertThat(
            tx.<Long>execute(
                status ->
                    db.count(
                        "select count(i) from BillingInvoice i where i.semester.id=:s",
                        Map.of("s", f.semester))))
        .isEqualTo(1L);
    assertThat(
            tx.<BigDecimal>execute(
                status ->
                    db.first(
                            BillingInvoice.class,
                            "select i from BillingInvoice i where i.semester.id=:s",
                            Map.of("s", f.semester))
                        .orElseThrow()
                        .getTotalAmount()))
        .isEqualByComparingTo("0.00");
    assertThatThrownBy(() -> schedules.saveDraft(f.student, f.semester, draft(0), null))
        .isInstanceOf(BusinessException.class);
    var invoice = billing.mine(f.student, f.semester);
    assertThat(invoice.totalAmount()).isEqualTo("0.00");
    assertThat(invoice.items()).isEmpty();
    assertThat(invoice.deliveryState()).isEqualTo("PENDING");
    assertThat(billing.forStudent(f.registrar, f.semester, f.student.personId()))
        .isEqualTo(invoice);
  }

  @Test
  void registrarCanChangeDeadlineAndCutOffWritesBeforeFinalClosure() {
    var version =
        tx.execute(status -> db.get(Semester.class, f.semester).getVersion());
    var updated =
        registrationControl.updateDeadline(
            f.registrar,
            f.semester,
            new RegistrationDeadlineCommand(time.now().plusDays(4).atOffset(ZoneOffset.UTC)),
            version);
    assertThat(updated.version()).isGreaterThan(version);
    assertThat(OffsetDateTime.parse(updated.addDropEnd()).truncatedTo(ChronoUnit.SECONDS))
        .isEqualTo(time.now().plusDays(4).atOffset(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS));

    var saved = schedules.saveDraft(f.student, f.semester, draft(0), null);
    var cutOff = registrationControl.cutoffNow(f.registrar, f.semester, updated.version());
    assertThat(OffsetDateTime.parse(cutOff.addDropEnd()))
        .isBeforeOrEqualTo(time.now().atOffset(ZoneOffset.UTC));
    assertThatThrownBy(
            () -> schedules.saveDraft(f.student, f.semester, draft(1), saved.version()))
        .isInstanceOf(BusinessException.class)
        .extracting("code")
        .isEqualTo("REGISTRATION_CLOSED");
    assertThat(close.close(f.registrar, f.semester).semesterId()).isEqualTo(f.semester.toString());
    assertThatThrownBy(
            () -> registrationControl.updateDeadline(
                f.registrar,
                f.semester,
                new RegistrationDeadlineCommand(time.now().plusDays(5).atOffset(ZoneOffset.UTC)),
                cutOff.version()))
        .isInstanceOf(BusinessException.class)
        .extracting("code")
        .isEqualTo("REGISTRATION_ALREADY_CLOSED");
  }

  @Test
  void onlyDraftsExpireWithoutBilling() {
    schedules.saveDraft(f.student, f.semester, draft(0), null);
    expired();
    var r = close.close(f.registrar, f.semester);
    assertThat(r.billingEvents()).isZero();
    assertThat(schedules.get(f.student, f.semester).status()).isEqualTo("EXPIRED");
    assertThatThrownBy(() -> billing.mine(f.student, f.semester))
        .isInstanceOf(BusinessException.class)
        .extracting("code")
        .isEqualTo("INVOICE_NOT_FOUND");
  }

  @Test
  void invoicesAreNotReadyBeforeFinalClosureEvenAfterCutoff() {
    submitted();
    expired();
    assertThatThrownBy(() -> billing.mine(f.student, f.semester))
        .isInstanceOf(BusinessException.class)
        .extracting("code")
        .isEqualTo("INVOICE_NOT_READY");
  }

  @Test
  void invoiceHttpEndpointsEnforceOwnershipRolesAndNoStore() throws Exception {
    submitted();
    expired();
    close.close(f.registrar, f.semester);
    var studentAuth = new UsernamePasswordAuthenticationToken(
        f.student, null, List.of(new SimpleGrantedAuthority("ROLE_STUDENT")));
    var registrarAuth = new UsernamePasswordAuthenticationToken(
        f.registrar, null, List.of(new SimpleGrantedAuthority("ROLE_REGISTRAR")));
    var professorAuth = new UsernamePasswordAuthenticationToken(
        f.professor, null, List.of(new SimpleGrantedAuthority("ROLE_PROFESSOR")));
    String mine = "/api/v1/students/me/invoices/" + f.semester;
    String registrar = "/api/v1/registrar/semesters/" + f.semester
        + "/students/" + f.student.personId() + "/invoice";
    mvc.perform(get(mine)).andExpect(status().isUnauthorized());
    mvc.perform(get(mine).with(authentication(studentAuth)))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(jsonPath("$.data.studentId").value(f.student.personId().toString()))
        .andExpect(jsonPath("$.data.totalAmount").value("0.00"))
        .andExpect(jsonPath("$.data.currency").value("CNY"))
        .andExpect(jsonPath("$.data.items").isEmpty());
    mvc.perform(get(registrar).with(authentication(registrarAuth)))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store"));
    mvc.perform(get(registrar).with(authentication(studentAuth)))
        .andExpect(status().isForbidden());
    mvc.perform(get(mine).with(authentication(professorAuth)))
        .andExpect(status().isForbidden());
    var other = tx.execute(status -> anotherStudent());
    var otherAuth = new UsernamePasswordAuthenticationToken(
        other, null, List.of(new SimpleGrantedAuthority("ROLE_STUDENT")));
    mvc.perform(get(mine).with(authentication(otherAuth)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("INVOICE_NOT_FOUND"));
    var forged = new CurrentActor(f.student.actorId(), "STUDENT", other.personId(), "", "");
    assertThatThrownBy(() -> billing.mine(forged, f.semester))
        .isInstanceOf(BusinessException.class)
        .extracting("status")
        .isEqualTo(403);
  }

  @Test
  void currentProfessorRosterIsReadOnlyAndExcludesDraftsAndAlternates() {
    var saved = submitted();
    var draftOnly = tx.execute(status -> anotherStudent());
    schedules.saveDraft(draftOnly, f.semester, draft(0), null);
    var roster = rosters.get(f.professor, f.offerings.getFirst());
    assertThat(roster.canEditGrades()).isFalse();
    assertThat(roster.students()).hasSize(1);
    assertThat(roster.students().getFirst().studentId()).isEqualTo(f.student.personId().toString());
    assertThat(rosters.get(f.professor, f.offerings.get(4)).students()).isEmpty();
    assertThatThrownBy(
            () -> grades.update(f.professor, f.offerings.getFirst(),
                new Grades(List.of(new GradeEntry(saved.enrollments().getFirst().id(), "A", null)))))
        .isInstanceOf(BusinessException.class)
        .extracting("code")
        .isEqualTo("GRADE_WINDOW_CLOSED");
    assertThatThrownBy(() -> rosters.get(f.student, f.offerings.getFirst()))
        .isInstanceOf(BusinessException.class)
        .extracting("status")
        .isEqualTo(403);
    var other = tx.execute(status -> {
      var a = account("PROFESSOR");
      var p = new Professor();
      p.setAccount(a);
      p.setName("其他教师");
      p.setBirthDate(LocalDate.of(1980, 1, 1));
      p.setDepartment("CS");
      db.persist(p);
      return new CurrentActor(a.getId(), "PROFESSOR", p.getId(), p.getName(), a.getUsername());
    });
    assertThatThrownBy(() -> rosters.get(other, f.offerings.getFirst()))
        .isInstanceOf(BusinessException.class)
        .extracting("status")
        .isEqualTo(403);
  }

  @Test
  void registrarFinalRosterRequiresClosureAndOnlyContainsEffectiveRegistrations() {
    submitted();
    assertThatThrownBy(() -> rosters.finalRoster(f.registrar, f.semester, f.offerings.getFirst()))
        .isInstanceOf(BusinessException.class)
        .extracting("code")
        .isEqualTo("REGISTRATION_NOT_CLOSED");
    var draftOnly = tx.execute(status -> anotherStudent());
    schedules.saveDraft(draftOnly, f.semester, draft(0), null);
    tx.executeWithoutResult(status -> {
      seedRegistration(anotherStudent(), List.of(f.offerings.getFirst()), true);
      var alternateStudent = anotherStudent();
      seedRegistration(alternateStudent, List.of(f.offerings.getFirst()), true);
      var alternate = db.enrollments(db.schedule(alternateStudent.personId(), f.semester).orElseThrow()).getFirst();
      alternate.setSource("ALTERNATE");
      var withdrawnStudent = anotherStudent();
      seedRegistration(withdrawnStudent, List.of(f.offerings.getFirst()), true);
      db.enrollments(db.schedule(withdrawnStudent.personId(), f.semester).orElseThrow()).getFirst().setState("DROPPED");
      var offering = db.get(CourseOffering.class, f.offerings.getFirst());
      offering.setEnrolledCount((short) (offering.getEnrolledCount() - 1));
    });
    expired();
    close.close(f.registrar, f.semester);
    var roster = rosters.finalRoster(f.registrar, f.semester, f.offerings.getFirst());
    assertThat(roster.semesterId()).isEqualTo(f.semester.toString());
    assertThat(roster.offering().status()).isEqualTo("CLOSED");
    assertThat(roster.students()).hasSize(3);
    assertThat(roster.students()).extracting(RegistrarRosterStudent::source).contains("PRIMARY", "ALTERNATE");
    assertThat(roster.students()).extracting(RegistrarRosterStudent::studentId)
        .doesNotContain(draftOnly.personId().toString());
    assertThat(rosters.finalRoster(f.registrar, f.semester, f.offerings.get(1)).students()).isEmpty();
    assertThatThrownBy(() -> rosters.finalRoster(f.student, f.semester, f.offerings.getFirst()))
        .isInstanceOf(BusinessException.class)
        .extracting("status")
        .isEqualTo(403);
    assertThatThrownBy(() -> rosters.finalRoster(f.professor, f.semester, f.offerings.getFirst()))
        .isInstanceOf(BusinessException.class)
        .extracting("status")
        .isEqualTo(403);
    var otherSemester = tx.execute(status -> fixture().semester());
    assertThatThrownBy(() -> rosters.finalRoster(f.registrar, f.semester,
            tx.execute(status -> db.offerings(otherSemester).getFirst().getId())))
        .isInstanceOf(BusinessException.class)
        .extracting("status")
        .isEqualTo(404);
  }

  @Test
  void rosterGradeExistenceAndBatchRollback() {
    var v = submitted();
    tx.executeWithoutResult(
        status -> {
          var s = db.get(Semester.class, f.semester);
          s.setStatus("CLOSED");
          s.setEndDate(time.today().minusDays(1));
          db.get(CourseOffering.class, f.offerings.getFirst()).setStatus("CLOSED");
        });
    var roster = rosters.get(f.professor, f.offerings.getFirst());
    assertThat(roster.students().getFirst().version()).isNull();
    String id = roster.students().getFirst().enrollmentId();
    var first =
        grades.update(
            f.professor,
            f.offerings.getFirst(),
            new Grades(List.of(new GradeEntry(id, "I", null))));
    assertThat(first.students().getFirst().version()).isZero();
    assertThat(first.students().getFirst().grade()).isEqualTo("I");
    assertThatThrownBy(
            () ->
                grades.update(
                    f.professor,
                    f.offerings.getFirst(),
                    new Grades(List.of(new GradeEntry(id, "A", null)))))
        .isInstanceOf(BusinessException.class)
        .extracting("status")
        .isEqualTo(412);
    var updated =
        grades.update(
            f.professor, f.offerings.getFirst(), new Grades(List.of(new GradeEntry(id, "A", 0L))));
    assertThat(updated.students().getFirst().version()).isEqualTo(1L);
    assertThat(cards.mine(f.student, f.semester).items().getFirst().grade()).isEqualTo("A");
    assertThatThrownBy(
            () ->
                grades.update(
                    f.professor,
                    f.offerings.getFirst(),
                    new Grades(
                        List.of(
                            new GradeEntry(id, "B", 1L),
                            new GradeEntry(v.enrollments().get(1).id(), "A", null)))))
        .isInstanceOf(BusinessException.class);
    assertThat(rosters.get(f.professor, f.offerings.getFirst()).students().getFirst().grade())
        .isEqualTo("A");
  }

  @Test
  void teachingVersionChangesAndFailedReplacementKeepsOriginal() {
    var plan = teaching.get(f.professor, f.semester);
    var selected = plan.selectedOfferingIds();
    var replaced =
        teaching.replace(
            f.professor, f.semester, new TeachingCommand(selected.subList(0, 4)), plan.version());
    assertThat(replaced.version()).isGreaterThan(plan.version());
    assertThat(replaced.selectedOfferingIds()).hasSize(4);
    assertThatThrownBy(
            () ->
                teaching.replace(
                    f.professor, f.semester, new TeachingCommand(List.of()), plan.version()))
        .isInstanceOf(BusinessException.class);
    assertThat(teaching.get(f.professor, f.semester).selectedOfferingIds()).hasSize(4);
  }

  @Test
  void personDeactivationRequiresNoCurrentLinksAndMasksSsn() {
    String ssn = String.format("%09d", new java.security.SecureRandom().nextInt(1000000000));
    var p =
        people
            .create(
                f.registrar,
                "students",
                new PersonCommand(
                    "new-" + UUID.randomUUID(),
                    "测试",
                    LocalDate.of(2000, 1, 1),
                    "ACTIVE",
                    null,
                    null,
                    ssn,
                    "initial-password-123"))
            .data();
    assertThat(p.ssnMasked()).isEqualTo("***-**-" + ssn.substring(5));
    assertThat(
            tx.<Boolean>execute(
                status -> passwords.matches(
                    "initial-password-123", db.get(Account.class, Long.parseLong(p.accountId())).getPasswordHash())))
        .isTrue();
    assertThatThrownBy(
            () ->
                people.create(
                    f.registrar,
                    "professors",
                    new PersonCommand(
                        "p-" + UUID.randomUUID(),
                        "教师",
                        LocalDate.of(1980, 1, 1),
                        "ACTIVE",
                        null,
                        "CS",
                        ssn,
                        "initial-password-456")))
        .isInstanceOf(BusinessException.class);
    submitted();
    var student = people.get(f.registrar, "students", f.student.personId());
    assertThatThrownBy(
            () ->
                people.update(
                    f.registrar,
                    "students",
                    f.student.personId(),
                    new PersonCommand(
                        null, "停用", student.birthDate(), "INACTIVE", null, null, null, null),
                    student.version()))
        .isInstanceOf(BusinessException.class);
  }

  @Test
  void registrarCanResetStudentAndProfessorPasswordsWithAccountVersions() {
    var student = people.get(f.registrar, "students", f.student.personId());
    var resetStudent =
        people.resetPassword(
            f.registrar,
            "students",
            f.student.personId(),
            new PasswordCommand("changed-student-password"),
            student.accountVersion());
    assertThat(resetStudent.data().accountVersion()).isGreaterThan(student.accountVersion());
    assertThatThrownBy(
            () -> people.resetPassword(
                f.registrar,
                "students",
                f.student.personId(),
                new PasswordCommand("another-student-password"),
                student.accountVersion()))
        .isInstanceOf(BusinessException.class)
        .extracting("status")
        .isEqualTo(412);

    var professor =
        people
            .create(
                f.registrar,
                "professors",
                new PersonCommand(
                    "reset-p-" + UUID.randomUUID(),
                    "新教师",
                    LocalDate.of(1980, 1, 1),
                    "ACTIVE",
                    null,
                    "CS",
                    null,
                    "initial-professor-password"))
            .data();
    var resetProfessor =
        people.resetPassword(
            f.registrar,
            "professors",
            Long.parseLong(professor.id()),
            new PasswordCommand("changed-professor-password"),
            professor.accountVersion());
    assertThat(
            tx.<Boolean>execute(
                status -> passwords.matches(
                    "changed-professor-password",
                    db.get(Account.class, Long.parseLong(resetProfessor.data().accountId()))
                        .getPasswordHash())))
        .isTrue();
  }

  @Test
  void staleCatalogFailsExplicitlyButSavedScheduleRemainsReadable() {
    submitted();
    tx.executeWithoutResult(
        status ->
            db.get(Semester.class, f.semester)
                .getActiveSnapshot()
                .setVerifiedAt(time.now().minusHours(25)));
    assertThatThrownBy(() -> catalog.offerings(f.student, f.semester, 1, 20, null, null))
        .isInstanceOf(BusinessException.class)
        .extracting("status")
        .isEqualTo(503);
    assertThat(schedules.get(f.student, f.semester).enrollments()).hasSize(4);
  }

  private CurrentActor anotherStudent() {
    var a = account("STUDENT");
    var s = new Student();
    s.setAccount(a);
    s.setName("另一学生");
    s.setBirthDate(LocalDate.of(2000, 1, 1));
    db.persist(s);
    return new CurrentActor(a.getId(), "STUDENT", s.getId(), s.getName(), a.getUsername());
  }

  private void seedRegistration(CurrentActor actor, List<Long> ids, boolean submitted) {
    var s = new Schedule();
    s.setStudent(db.get(Student.class, actor.personId()));
    s.setSemester(db.get(Semester.class, f.semester));
    if (submitted) {
      s.setFirstSubmittedAt(time.now());
      s.setSubmittedRevision(1L);
      s.setLatestRevision(1L);
      s.setStatus("REGISTERED");
    }
    db.persist(s);
    for (Long id : ids) {
      var o = db.get(CourseOffering.class, id);
      var e = new Enrollment();
      e.setSchedule(s);
      e.setOffering(o);
      e.setSource("PRIMARY");
      db.persist(e);
      o.setEnrolledCount((short) (o.getEnrolledCount() + 1));
    }
  }

  @Test
  void concurrentStudentsCompeteForLastSeatWithoutPartialEnrollment() throws Exception {
    tx.executeWithoutResult(
        status -> {
          for (int i = 0; i < 9; i++)
            seedRegistration(anotherStudent(), List.of(f.offerings.getFirst()), true);
        });
    var second = tx.execute(status -> anotherStudent());
    var a = schedules.saveDraft(f.student, f.semester, draft(0, 1, 2, 3), null);
    var b = schedules.saveDraft(second, f.semester, draft(0, 1, 2, 3), null);
    CountDownLatch start = new CountDownLatch(1);
    var pool = Executors.newFixedThreadPool(2);
    try {
      var one =
          pool.submit(
              () -> {
                start.await();
                try {
                  schedules.submit(f.student, f.semester, a.draftRevision(), a.version());
                  return "OK";
                } catch (BusinessException e) {
                  return e.code();
                }
              });
      var two =
          pool.submit(
              () -> {
                start.await();
                try {
                  schedules.submit(second, f.semester, b.draftRevision(), b.version());
                  return "OK";
                } catch (BusinessException e) {
                  return e.code();
                }
              });
      start.countDown();
      assertThat(List.of(one.get(15, TimeUnit.SECONDS), two.get(15, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder("OK", "CAPACITY_FULL");
      assertThat(
              tx.<Short>execute(
                  status ->
                      db.get(CourseOffering.class, f.offerings.getFirst()).getEnrolledCount()))
          .isEqualTo((short) 10);
      assertThat(
              tx.<Short>execute(
                  status -> db.get(CourseOffering.class, f.offerings.get(1)).getEnrolledCount()))
          .isEqualTo((short) 1);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void mysqlCloseNowaitRefusesInFlightSharedGate() throws Exception {
    try (var c = datasource.getConnection()) {
      Assumptions.assumeTrue(c.getMetaData().getDatabaseProductName().equals("MySQL"));
    }
    expired();
    CountDownLatch locked = new CountDownLatch(1), release = new CountDownLatch(1);
    var pool = Executors.newSingleThreadExecutor();
    var pending =
        pool.submit(
            () ->
                tx.executeWithoutResult(
                    status -> {
                      db.gate(f.semester);
                      locked.countDown();
                      try {
                        release.await(10, TimeUnit.SECONDS);
                      } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                      }
                    }));
    try {
      assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
      long before = System.nanoTime();
      assertThatThrownBy(() -> close.close(f.registrar, f.semester))
          .isInstanceOf(org.springframework.dao.PessimisticLockingFailureException.class);
      assertThat(Duration.ofNanos(System.nanoTime() - before)).isLessThan(Duration.ofSeconds(2));
    } finally {
      release.countDown();
      pending.get(10, TimeUnit.SECONDS);
      pool.shutdownNow();
    }
    assertThat(close.close(f.registrar, f.semester).runId()).isNotBlank();
  }

  @Test
  void closePromotesSubmittedAlternatesBeforeFinalMinimumPassAndBillsFinalSet() {
    var d = schedules.saveDraft(f.student, f.semester, draft(0, 1, 2, 3), null);
    schedules.submit(f.student, f.semester, d.draftRevision(), d.version());
    tx.executeWithoutResult(
        status -> {
          db.get(CourseOffering.class, f.offerings.getFirst()).setProfessor(null);
          for (int i = 0; i < 2; i++)
            seedRegistration(
                anotherStudent(),
                List.of(
                    f.offerings.get(1), f.offerings.get(2), f.offerings.get(3), f.offerings.get(4)),
                true);
        });
    var current = schedules.get(f.student, f.semester);
    // Save a different draft; closing must still use submitted alternative 4.
    schedules.saveDraft(
        f.student,
        f.semester,
        new Draft(List.of(), List.of(f.offerings.get(6).toString())),
        current.version());
    expired();
    var result = close.close(f.registrar, f.semester);
    assertThat(result.leveledSchedules()).isEqualTo(1);
    assertThat(result.billingEvents()).isEqualTo(3);
    var finalSchedule = schedules.get(f.student, f.semester);
    assertThat(finalSchedule.enrollments().stream().filter(e -> e.state().equals("ENROLLED")))
        .extracting(EnrollmentView::offeringId)
        .containsExactlyInAnyOrder(
            f.offerings.get(1).toString(),
            f.offerings.get(2).toString(),
            f.offerings.get(3).toString(),
            f.offerings.get(4).toString());
    assertThat(
            tx.<BigDecimal>execute(
                status ->
                    db.first(
                            BillingInvoice.class,
                            "select i from BillingInvoice i where i.student.id=:p and"
                                + " i.semester.id=:s",
                            Map.of("p", f.student.personId(), "s", f.semester))
                        .orElseThrow()
                        .getTotalAmount()))
        .isEqualByComparingTo("2400.00");
    var invoice = billing.mine(f.student, f.semester);
    assertThat(invoice.totalAmount()).isEqualTo("2400.00");
    assertThat(invoice.items()).extracting(InvoiceItem::offeringId)
        .containsExactlyInAnyOrder(
            f.offerings.get(1).toString(), f.offerings.get(2).toString(),
            f.offerings.get(3).toString(), f.offerings.get(4).toString());
    // Catalog edits must not change the frozen amount or course description.
    tx.executeWithoutResult(status -> {
      var offering = db.get(CourseOffering.class, f.offerings.get(1));
      offering.setTuition(new BigDecimal("999.00"));
      offering.getCourse().setName("目录已改名");
      db.first(OutboxEvent.class,
          "select e from OutboxEvent e where e.invoice.id=:i", Map.of("i", Long.valueOf(invoice.id())))
          .orElseThrow().setState("RETRY");
    });
    var refreshed = billing.mine(f.student, f.semester);
    assertThat(refreshed.totalAmount()).isEqualTo("2400.00");
    assertThat(refreshed.items()).isEqualTo(invoice.items());
    assertThat(refreshed.deliveryState()).isEqualTo("RETRY");
  }

  @Test
  void finalSmallClassCancellationDoesNotRunThirdPromotionRound() {
    submitted();
    tx.executeWithoutResult(
        status -> {
          for (int i = 0; i < 2; i++)
            seedRegistration(
                anotherStudent(),
                List.of(f.offerings.get(1), f.offerings.get(2), f.offerings.get(3)),
                true);
        });
    expired();
    close.close(f.registrar, f.semester);
    assertThat(
            schedules.get(f.student, f.semester).enrollments().stream()
                .filter(e -> "ENROLLED".equals(e.state())))
        .hasSize(3);
    assertThat(
            tx.<BigDecimal>execute(
                status ->
                    db.first(
                            BillingInvoice.class,
                            "select i from BillingInvoice i where i.student.id=:p and"
                                + " i.semester.id=:s",
                            Map.of("p", f.student.personId(), "s", f.semester))
                        .orElseThrow()
                        .getTotalAmount()))
        .isEqualByComparingTo("1800.00");
  }

  @Test
  void withdrawnStudentWithSubmissionHistoryGetsZeroInvoice() {
    var submitted = submitted();
    schedules.withdraw(f.student, f.semester, submitted.version());
    expired();
    var r = close.close(f.registrar, f.semester);
    assertThat(r.billingEvents()).isEqualTo(1);
    assertThat(schedules.get(f.student, f.semester).status()).isEqualTo("FINALIZED");
  }

  @Test
  void outboxRetriesKeepPayloadAndOldClaimCannotAcknowledgeNewLease() {
    submitted();
    expired();
    close.close(f.registrar, f.semester);
    var first =
        outbox.claimDue(100).stream()
            .filter(e -> e.payload().get("studentId").equals(f.student.personId().toString()))
            .findFirst()
            .orElseThrow();
    assertThat(
            outbox.ackFailure(
                first.eventId(), first.claimToken(), new Exception("secret remote body")))
        .isTrue();
    tx.executeWithoutResult(
        status ->
            db.get(OutboxEvent.class, first.eventId())
                .setNextAttemptAt(time.now().minusSeconds(1)));
    var second =
        outbox.claimDue(100).stream()
            .filter(e -> e.eventId().equals(first.eventId()))
            .findFirst()
            .orElseThrow();
    assertThat(second.payload()).isEqualTo(first.payload());
    assertThat(second.claimToken()).isNotEqualTo(first.claimToken());
    assertThat(outbox.ackSuccess(first.eventId(), first.claimToken())).isFalse();
    assertThat(outbox.ackSuccess(second.eventId(), second.claimToken())).isTrue();
  }
}
