package com.gm222.courseregistrationsystem.config;

import com.gm222.courseregistrationsystem.model.entity.*;
import com.gm222.courseregistrationsystem.repository.BusinessStore;
import com.gm222.courseregistrationsystem.service.BusinessTime;
import com.gm222.courseregistrationsystem.service.catalog.CatalogSyncService;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.*;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@Profile("demo")
public class DemoData implements ApplicationRunner {
  private final BusinessStore db;
  private final CatalogSyncService catalog;
  private final BusinessTime time;
  private final PasswordEncoder passwords;
  private final String password;
  private final TransactionTemplate tx;

  public DemoData(
      BusinessStore db,
      CatalogSyncService catalog,
      BusinessTime time,
      PasswordEncoder passwords,
      @Value("${app.demo-password}") String password,
      PlatformTransactionManager manager) {
    this.db = db;
    this.catalog = catalog;
    this.time = time;
    this.passwords = passwords;
    this.password = password;
    this.tx = new TransactionTemplate(manager);
  }

  @Override
  public void run(ApplicationArguments args) {
    if (tx.<Long>execute(status -> db.count("select count(a) from Account a", Map.of())) > 0)
      return;
    Long semester =
        tx.execute(
            status -> {
              var registrar = account("registrar.demo", "REGISTRAR");
              var a = account("student.demo", "STUDENT");
              var student = new Student();
              student.setAccount(a);
              student.setName("演示学生");
              student.setBirthDate(time.today().minusYears(20));
              db.persist(student);
              var p = account("professor.demo", "PROFESSOR");
              var professor = new Professor();
              professor.setAccount(p);
              professor.setName("演示教师");
              professor.setBirthDate(time.today().minusYears(40));
              professor.setDepartment("计算机学院");
              db.persist(professor);
              var s = new Semester();
              s.setCode("DEMO-CURRENT");
              s.setName("演示选课学期");
              s.setStartDate(time.today().minusDays(7));
              s.setEndDate(time.today().plusMonths(4));
              s.setRegistrationStart(time.now().minusDays(10));
              s.setRegistrationEnd(time.now().plusDays(1));
              s.setAddDropEnd(time.now().plusDays(2));
              db.persist(s);
              return s.getId();
            });
    catalog.sync(semester);
    tx.executeWithoutResult(
        status -> {
          var professor =
              db.first(
                      Professor.class,
                      "select p from Professor p where p.account.username='professor.demo'",
                      Map.of())
                  .orElseThrow();
          for (var o : db.offerings(semester)) {
            var q = new ProfessorQualification();
            q.setProfessorId(professor.getId());
            q.setCourseId(o.getCourse().getId());
            q.setProfessor(professor);
            q.setCourse(o.getCourse());
            q.setSourceRef("synthetic-demo");
            db.persist(q);
          }
        });
  }

  private Account account(String username, String role) {
    var a = new Account();
    a.setUsername(username);
    a.setRole(role);
    a.setPasswordHash(passwords.encode(password));
    db.persist(a);
    return a;
  }
}
