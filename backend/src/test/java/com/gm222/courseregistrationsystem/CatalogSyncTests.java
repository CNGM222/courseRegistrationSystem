package com.gm222.courseregistrationsystem;

import static org.assertj.core.api.Assertions.*;

import com.gm222.courseregistrationsystem.exception.BusinessException;
import com.gm222.courseregistrationsystem.model.dto.ApiDtos.Meeting;
import com.gm222.courseregistrationsystem.model.entity.*;
import com.gm222.courseregistrationsystem.repository.BusinessStore;
import com.gm222.courseregistrationsystem.service.BusinessTime;
import com.gm222.courseregistrationsystem.service.catalog.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

@SpringBootTest
@ActiveProfiles("test")
@Import(CatalogSyncTests.SourceConfig.class)
class CatalogSyncTests {
  @TestConfiguration
  static class SourceConfig {
    @Bean
    @Primary
    Source source() {
      return new Source();
    }
  }

  static class Source implements CatalogPort {
    ExternalCatalog value;
    RuntimeException failure;
    volatile boolean transactionActive;

    public ExternalCatalog fetch(String code, Instant deadline) {
      transactionActive = TransactionSynchronizationManager.isActualTransactionActive();
      if (failure != null) throw failure;
      return value;
    }

    public String sourceKind() {
      return "MOCK";
    }
  }

  @Autowired Source source;
  @Autowired CatalogSyncService sync;
  @Autowired BusinessStore db;
  @Autowired PlatformTransactionManager manager;
  @Autowired BusinessTime time;
  TransactionTemplate tx;
  Long id;
  String code;

  @BeforeEach
  void seed() {
    tx = new TransactionTemplate(manager);
    code = "SYNC-" + UUID.randomUUID().toString().substring(0, 12);
    id =
        tx.execute(
            status -> {
              var s = new Semester();
              s.setCode(code);
              s.setName("同步学期");
              s.setStartDate(time.today());
              s.setEndDate(time.today().plusMonths(4));
              s.setRegistrationStart(time.now().minusDays(1));
              s.setRegistrationEnd(time.now().plusDays(1));
              s.setAddDropEnd(time.now().plusDays(2));
              db.persist(s);
              return s.getId();
            });
    source.failure = null;
    source.value = data("课程", List.of());
  }

  private CatalogPort.ExternalCatalog data(String name, List<String> prerequisites) {
    return new CatalogPort.ExternalCatalog(
        code,
        "source-v1",
        List.of(
            new CatalogPort.CourseData(
                "CS101", "CS101", name, "计算机学院", new BigDecimal("3.0"), prerequisites)),
        List.of(
            new CatalogPort.OfferingData(
                "CS101-01",
                "CS101",
                "01",
                new BigDecimal("600.00"),
                "CNY",
                List.of(new Meeting(1, 1, 1, 3, "101")))),
        List.of());
  }

  @Test
  void publishImportsWholeCatalogAndRefreshDoesNotOverwriteBusinessFields() {
    var result = sync.sync(id);
    assertThat(result.state()).isEqualTo("PUBLISHED");
    assertThat(source.transactionActive).isFalse();
    Long offering =
        tx.execute(
            status -> {
              var s = db.get(Semester.class, id);
              assertThat(s.getStatus()).isEqualTo("OPEN");
              var o = db.offerings(id).getFirst();
              o.setEnrolledCount((short) 5);
              return o.getId();
            });
    var refreshed = sync.sync(id);
    assertThat(refreshed.state()).isEqualTo("VERIFIED");
    assertThat(refreshed.snapshotId()).isEqualTo(result.snapshotId());
    assertThat(
            tx.<Short>execute(status -> db.get(CourseOffering.class, offering).getEnrolledCount()))
        .isEqualTo((short) 5);
  }

  @Test
  void changedSourceStagesOnceWithoutReplacingPublishedSnapshot() {
    var original = sync.sync(id);
    source.value = data("课程已变化", List.of());
    var change = sync.sync(id);
    assertThat(change.state()).isEqualTo("REVIEW_REQUIRED");
    assertThat(sync.sync(id).snapshotId()).isEqualTo(change.snapshotId());
    assertThat(
            tx.<String>execute(
                status -> db.get(Semester.class, id).getActiveSnapshot().getId().toString()))
        .isEqualTo(original.snapshotId());
    assertThat(
            tx.<Long>execute(
                status ->
                    db.count(
                        "select count(s) from CatalogSnapshot s where s.semester.id=:id",
                        Map.of("id", id))))
        .isEqualTo(2L);
  }

  @Test
  void remoteFailureLeavesPublishedSnapshotUsable() {
    var original = sync.sync(id);
    source.failure = new IllegalStateException("remote credentials must not leak");
    assertThatThrownBy(() -> sync.sync(id))
        .isInstanceOf(BusinessException.class)
        .hasMessage("目录同步失败，原有快照保持不变");
    assertThat(
            tx.<String>execute(
                status -> db.get(Semester.class, id).getActiveSnapshot().getId().toString()))
        .isEqualTo(original.snapshotId());
  }

  @Test
  void selfPrerequisiteAndInvalidCurrencyRollbackAllImports() {
    source.value = data("非法", List.of("CS101"));
    assertThatThrownBy(() -> sync.sync(id)).isInstanceOf(BusinessException.class);
    assertThat(
            tx.<Long>execute(
                status ->
                    db.count(
                        "select count(s) from CatalogSnapshot s where s.semester.id=:id",
                        Map.of("id", id))))
        .isZero();
    var valid = data("课程", List.of());
    source.value =
        new CatalogPort.ExternalCatalog(
            code,
            "source-v1",
            valid.courses(),
            List.of(
                new CatalogPort.OfferingData(
                    "CS101-01",
                    "CS101",
                    "01",
                    new BigDecimal("600.00"),
                    "USD",
                    valid.offerings().getFirst().meetings())),
            List.of());
    assertThatThrownBy(() -> sync.sync(id)).isInstanceOf(BusinessException.class);
    assertThat(
            tx.<Long>execute(
                status ->
                    db.count(
                        "select count(s) from CatalogSnapshot s where s.semester.id=:id",
                        Map.of("id", id))))
        .isZero();
    assertThat(tx.<String>execute(status -> db.get(Semester.class, id).getStatus()))
        .isEqualTo("PREPARATION");
  }
}
