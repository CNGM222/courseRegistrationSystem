package com.gm222.courseregistrationsystem.service.people;

import com.gm222.courseregistrationsystem.exception.BusinessException;
import com.gm222.courseregistrationsystem.model.dto.ApiDtos.*;
import com.gm222.courseregistrationsystem.model.entity.*;
import com.gm222.courseregistrationsystem.repository.BusinessStore;
import com.gm222.courseregistrationsystem.service.BusinessTime;
import com.gm222.courseregistrationsystem.service.audit.AuditService;
import com.gm222.courseregistrationsystem.service.identity.CurrentActor;
import com.gm222.courseregistrationsystem.service.identity.SessionRevoker;
import com.gm222.courseregistrationsystem.web.WebValues;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PersonService {
  private final BusinessStore db;
  private final SensitiveIdentifier ssn;
  private final PasswordEncoder passwords;
  private final BusinessTime time;
  private final AuditService audit;
  private final SessionRevoker sessions;

  public PersonService(
      BusinessStore db,
      SensitiveIdentifier ssn,
      PasswordEncoder passwords,
      BusinessTime time,
      AuditService audit,
      SessionRevoker sessions) {
    this.db = db;
    this.ssn = ssn;
    this.passwords = passwords;
    this.time = time;
    this.audit = audit;
    this.sessions = sessions;
  }

  private void registrar(CurrentActor a) {
    a.requireRole("REGISTRAR");
  }

  public record Result<T>(T data, long version) {}

  @Transactional(readOnly = true)
  public com.gm222.courseregistrationsystem.model.dto.ApiDtos.Page<Person> list(
      CurrentActor actor, String kind, int page, int size, String keyword) {
    registrar(actor);
    WebValues.page(page, size);
    String q = keyword == null ? "" : keyword.strip().toLowerCase(Locale.ROOT);
    String like = "%" + q + "%";
    if ("students".equals(kind)) {
      String jp =
          "select s from Student s where s.deletedAt is null and (lower(s.name) like :q or"
              + " lower(s.account.username) like :q or cast(s.id as string) like :q) order by s.id";
      var items = db.page(Student.class, jp, Map.of("q", like), page, size);
      return new com.gm222.courseregistrationsystem.model.dto.ApiDtos.Page<>(
          items.stream().map(this::student).toList(),
          page,
          size,
          db.count(
              "select count(s) from Student s where s.deletedAt is null and (lower(s.name) like :q"
                  + " or lower(s.account.username) like :q or cast(s.id as string) like :q)",
              Map.of("q", like)),
          null,
          List.of());
    }
    String jp =
        "select p from Professor p where p.deletedAt is null and (lower(p.name) like :q or"
            + " lower(p.account.username) like :q or cast(p.id as string) like :q) order by p.id";
    var items = db.page(Professor.class, jp, Map.of("q", like), page, size);
    return new com.gm222.courseregistrationsystem.model.dto.ApiDtos.Page<>(
        items.stream().map(this::professor).toList(),
        page,
        size,
        db.count(
            "select count(p) from Professor p where p.deletedAt is null and (lower(p.name) like :q"
                + " or lower(p.account.username) like :q or cast(p.id as string) like :q)",
            Map.of("q", like)),
        null,
        List.of());
  }

  @Transactional(readOnly = true)
  public Person get(CurrentActor actor, String kind, Long id) {
    registrar(actor);
    return "students".equals(kind) ? student(liveStudent(id)) : professor(liveProfessor(id));
  }

  @Transactional
  public Result<Person> create(CurrentActor actor, String kind, PersonCommand c) {
    registrar(actor);
    validate(c, kind);
    if (c.username() == null || c.username().isBlank()) throw BusinessException.invalid("登录账号不能为空");
    String username = c.username().strip().toLowerCase(Locale.ROOT);
    if (db.count(
            "select count(a) from Account a where lower(a.username)=:u",
            Map.of("u", username))
        > 0) throw BusinessException.conflict("DUPLICATE_USERNAME", "登录账号已存在");
    uniqueSsn(kind, null, c.socialSecurityNumber());
    validatePassword(c.password());
    var account = new Account();
    account.setEnabled("ACTIVE".equals(c.status()));
    account.setUsername(username);
    account.setPasswordHash(passwords.encode(c.password()));
    account.setRole("students".equals(kind) ? "STUDENT" : "PROFESSOR");
    db.persist(account);
    if ("students".equals(kind)) {
      var s = new Student();
      s.setAccount(account);
      apply(s, c);
      db.persist(s);
      db.flush();
      audit.record(actor, "CREATE_STUDENT", "STUDENT", s.getId());
      return new Result<>(student(s), s.getVersion());
    }
    var p = new Professor();
    p.setAccount(account);
    apply(p, c);
    db.persist(p);
    db.flush();
    audit.record(actor, "CREATE_PROFESSOR", "PROFESSOR", p.getId());
    return new Result<>(professor(p), p.getVersion());
  }

  @Transactional
  public Result<Person> update(
      CurrentActor actor, String kind, Long id, PersonCommand c, Long expected) {
    registrar(actor);
    validate(c, kind);
    if (c.username() != null) throw BusinessException.invalid("修改资料不能更改登录账号");
    if (c.password() != null) throw BusinessException.invalid("密码请通过单独的重置操作修改");
    gates();
    uniqueSsn(kind, id, c.socialSecurityNumber());
    if ("students".equals(kind)) {
      var s = db.lock(Student.class, id);
      if (s.getDeletedAt() != null) throw BusinessException.missing("学生不存在");
      WebValues.match(expected, s.getVersion());
      // Keep account status changes in the same lock order as the person
      // aggregate. This serializes a concurrent login/disable operation.
      db.lock(Account.class, s.getAccount().getId());
      if (!"ACTIVE".equals(c.status())) requireNoRegistration(id);
      apply(s, c);
      s.getAccount().setEnabled("ACTIVE".equals(c.status()));
      if (!s.getAccount().getEnabled()) sessions.revoke(s.getAccount().getUsername());
      audit.record(actor, "UPDATE_STUDENT", "STUDENT", id);
      db.flush();
      return new Result<>(student(s), s.getVersion());
    }
    var p = db.lock(Professor.class, id);
    if (p.getDeletedAt() != null) throw BusinessException.missing("教师不存在");
    WebValues.match(expected, p.getVersion());
    db.lock(Account.class, p.getAccount().getId());
    if (!"ACTIVE".equals(c.status())) requireNoTeaching(id);
    apply(p, c);
    p.getAccount().setEnabled("ACTIVE".equals(c.status()));
    if (!p.getAccount().getEnabled()) sessions.revoke(p.getAccount().getUsername());
    audit.record(actor, "UPDATE_PROFESSOR", "PROFESSOR", id);
    db.flush();
    return new Result<>(professor(p), p.getVersion());
  }

  @Transactional
  public Result<Person> resetPassword(
      CurrentActor actor, String kind, Long id, PasswordCommand command, Long expectedAccountVersion) {
    registrar(actor);
    validatePassword(command.password());
    if ("students".equals(kind)) {
      var s = db.lock(Student.class, id);
      if (s.getDeletedAt() != null) throw BusinessException.missing("学生不存在");
      var account = db.lock(Account.class, s.getAccount().getId());
      WebValues.match(expectedAccountVersion, account.getVersion());
      applyPassword(account, command.password());
      sessions.revoke(account.getUsername());
      audit.record(actor, "RESET_STUDENT_PASSWORD", "STUDENT", id);
      db.flush();
      return new Result<>(student(s), account.getVersion());
    }
    if (!"professors".equals(kind)) throw BusinessException.missing("人员类型不存在");
    var p = db.lock(Professor.class, id);
    if (p.getDeletedAt() != null) throw BusinessException.missing("教师不存在");
    var account = db.lock(Account.class, p.getAccount().getId());
    WebValues.match(expectedAccountVersion, account.getVersion());
    applyPassword(account, command.password());
    sessions.revoke(account.getUsername());
    audit.record(actor, "RESET_PROFESSOR_PASSWORD", "PROFESSOR", id);
    db.flush();
    return new Result<>(professor(p), account.getVersion());
  }

  @Transactional
  public void delete(CurrentActor actor, String kind, Long id, Long expected) {
    registrar(actor);
    gates();
    if ("students".equals(kind)) {
      var s = db.lock(Student.class, id);
      if (s.getDeletedAt() != null) throw BusinessException.missing("学生不存在");
      WebValues.match(expected, s.getVersion());
      db.lock(Account.class, s.getAccount().getId());
      requireNoRegistration(id);
      s.setStatus("INACTIVE");
      s.setDeletedAt(time.now());
      s.getAccount().setEnabled(false);
      sessions.revoke(s.getAccount().getUsername());
      audit.record(actor, "DELETE_STUDENT", "STUDENT", id);
    } else {
      var p = db.lock(Professor.class, id);
      if (p.getDeletedAt() != null) throw BusinessException.missing("教师不存在");
      WebValues.match(expected, p.getVersion());
      db.lock(Account.class, p.getAccount().getId());
      requireNoTeaching(id);
      p.setStatus("INACTIVE");
      p.setDeletedAt(time.now());
      p.getAccount().setEnabled(false);
      sessions.revoke(p.getAccount().getUsername());
      audit.record(actor, "DELETE_PROFESSOR", "PROFESSOR", id);
    }
  }

  private void gates() {
    db.list(Long.class, "select s.id from Semester s order by s.id", Map.of()).forEach(db::gate);
  }

  private Student liveStudent(Long id) {
    var s = db.get(Student.class, id);
    if (s.getDeletedAt() != null) throw BusinessException.missing("学生不存在");
    return s;
  }

  private Professor liveProfessor(Long id) {
    var p = db.get(Professor.class, id);
    if (p.getDeletedAt() != null) throw BusinessException.missing("教师不存在");
    return p;
  }

  private void requireNoRegistration(Long id) {
    if (db.count(
            "select count(e) from Enrollment e where e.schedule.student.id=:s and"
                + " e.state='ENROLLED' and (e.schedule.semester.status='OPEN' or"
                + " e.schedule.semester.endDate>=:today)",
            Map.of("s", id, "today", time.today()))
        > 0) throw BusinessException.conflict("ACTIVE_REGISTRATION", "该学生仍有当前有效注册，不能停用或删除");
  }

  private void requireNoTeaching(Long id) {
    if (db.count(
            "select count(o) from CourseOffering o where o.professor.id=:p and"
                + " o.status<>'CANCELLED' and (o.semester.status='OPEN' or"
                + " o.semester.endDate>=:today)",
            Map.of("p", id, "today", time.today()))
        > 0) throw BusinessException.conflict("ACTIVE_TEACHING", "该教师仍有当前授课任务，不能停用或删除");
  }

  private void uniqueSsn(String kind, Long id, String raw) {
    if (raw == null || raw.isBlank()) return;
    byte[] fingerprint = ssn.fingerprint(raw);
    var params = new HashMap<String, Object>();
    params.put("f", fingerprint);
    params.put("id", id == null ? -1L : id);
    String same = "students".equals(kind) ? "Student" : "Professor",
        other = "students".equals(kind) ? "Professor" : "Student";
    if (db.count(
                "select count(p) from " + same + " p where p.ssnFingerprint=:f and p.id<>:id",
                params)
            > 0
        || db.count(
                "select count(p) from " + other + " p where p.ssnFingerprint=:f",
                Map.of("f", fingerprint))
            > 0) throw BusinessException.conflict("DUPLICATE_IDENTIFIER", "社会安全号码已存在");
  }

  private void validate(PersonCommand c, String kind) {
    if (c.birthDate().isAfter(time.today())) throw BusinessException.invalid("出生日期不能晚于今天");
    if (c.graduationDate() != null && c.graduationDate().isBefore(c.birthDate()))
      throw BusinessException.invalid("毕业日期不能早于出生日期");
    if ("students".equals(kind) && !Set.of("ACTIVE", "INACTIVE", "GRADUATED").contains(c.status()))
      throw BusinessException.invalid("学生状态不合法");
    if ("professors".equals(kind) && !Set.of("ACTIVE", "INACTIVE").contains(c.status()))
      throw BusinessException.invalid("教师状态不合法");
    if ("professors".equals(kind) && (c.department() == null || c.department().isBlank()))
      throw BusinessException.invalid("教师院系不能为空");
    if (c.socialSecurityNumber() != null
        && !c.socialSecurityNumber().isBlank()
        && !c.socialSecurityNumber().matches("(?:[0-9]{9}|[0-9]{3}-[0-9]{2}-[0-9]{4})"))
      throw BusinessException.invalid("社会安全号码必须为九位数字");
  }

  private void validatePassword(String password) {
    if (password == null || password.length() < 12)
      throw BusinessException.invalid("密码至少需要 12 个字符");
    if (password.getBytes(StandardCharsets.UTF_8).length > 72)
      throw BusinessException.invalid("密码不得超过 72 个 UTF-8 字节");
  }

  private void applyPassword(Account account, String password) {
    account.setPasswordHash(passwords.encode(password));
    account.setFailedAttempts(0);
    account.setLockedUntil(null);
  }

  private void apply(Student s, PersonCommand c) {
    s.setName(c.name().strip());
    s.setBirthDate(c.birthDate());
    s.setStatus(c.status());
    s.setGraduationDate(c.graduationDate());
    if (c.socialSecurityNumber() != null && !c.socialSecurityNumber().isBlank()) {
      s.setSsnCiphertext(ssn.encrypt(c.socialSecurityNumber()));
      s.setSsnFingerprint(ssn.fingerprint(c.socialSecurityNumber()));
    }
  }

  private void apply(Professor p, PersonCommand c) {
    p.setName(c.name().strip());
    p.setBirthDate(c.birthDate());
    p.setStatus(c.status());
    p.setDepartment(c.department() == null ? "" : c.department().strip());
    if (c.socialSecurityNumber() != null && !c.socialSecurityNumber().isBlank()) {
      p.setSsnCiphertext(ssn.encrypt(c.socialSecurityNumber()));
      p.setSsnFingerprint(ssn.fingerprint(c.socialSecurityNumber()));
    }
  }

  private Person student(Student s) {
    return new Person(
        s.getId().toString(),
        s.getAccount().getId().toString(),
        s.getAccount().getUsername(),
        s.getName(),
        s.getBirthDate(),
        s.getStatus(),
        s.getGraduationDate(),
        null,
        ssn.mask(s.getSsnCiphertext()),
        s.getAccount().getVersion(),
        s.getVersion());
  }

  private Person professor(Professor p) {
    return new Person(
        p.getId().toString(),
        p.getAccount().getId().toString(),
        p.getAccount().getUsername(),
        p.getName(),
        p.getBirthDate(),
        p.getStatus(),
        null,
        p.getDepartment(),
        ssn.mask(p.getSsnCiphertext()),
        p.getAccount().getVersion(),
        p.getVersion());
  }
}
