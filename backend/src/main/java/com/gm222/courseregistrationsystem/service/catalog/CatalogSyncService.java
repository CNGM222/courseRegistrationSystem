package com.gm222.courseregistrationsystem.service.catalog;

import com.gm222.courseregistrationsystem.exception.BusinessException;
import com.gm222.courseregistrationsystem.model.entity.*;
import com.gm222.courseregistrationsystem.repository.BusinessStore;
import com.gm222.courseregistrationsystem.service.BusinessTime;
import com.gm222.courseregistrationsystem.web.WebValues;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Service
public class CatalogSyncService {
  public record SyncResult(String state, String snapshotId) {}

  private final BusinessStore db;
  private final CatalogPort source;
  private final BusinessTime time;
  private final ObjectMapper json;
  private final TransactionTemplate tx;
  private final boolean enabled;

  public CatalogSyncService(
      BusinessStore db,
      CatalogPort source,
      BusinessTime time,
      ObjectMapper json,
      PlatformTransactionManager manager,
      @Value("${app.catalog.sync-enabled:false}") boolean enabled) {
    this.db = db;
    this.source = source;
    this.time = time;
    this.json = json;
    this.tx = new TransactionTemplate(manager);
    tx.setTimeout(2);
    this.enabled = enabled;
  }

  public SyncResult sync(Long semesterId) {
    String code = tx.execute(status -> db.get(Semester.class, semesterId).getCode());
    CatalogPort.ExternalCatalog catalog;
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    Future<CatalogPort.ExternalCatalog> future =
        executor.submit(() -> source.fetch(code, Instant.now().plusSeconds(8)));
    try {
      catalog = future.get(8, TimeUnit.SECONDS);
    } catch (Exception e) {
      future.cancel(true);
      throw new BusinessException(503, "CATALOG_UNAVAILABLE", "目录同步失败，原有快照保持不变");
    } finally {
      executor.shutdownNow();
    }
    validate(catalog, code);
    String hash;
    try {
      hash =
          HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest(
                          json.writeValueAsString(
                                  List.of(
                                      catalog.courses(),
                                      catalog.offerings(),
                                      catalog.qualifications()))
                              .getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw BusinessException.invalid("目录内容不能解析");
    }
    return tx.execute(status -> importCatalog(semesterId, catalog, hash));
  }

  private SyncResult importCatalog(Long id, CatalogPort.ExternalCatalog data, String hash) {
    var semester = db.lock(Semester.class, id);
    var active = semester.getActiveSnapshot();
    if ("CLOSED".equals(semester.getStatus()))
      throw BusinessException.conflict("REGISTRATION_CLOSED", "已关闭学期不再同步目录");
    if (active != null && hash.equals(active.getContentHash())) {
      active.setVerifiedAt(time.now());
      active.setLastError(null);
      return new SyncResult("VERIFIED", active.getId().toString());
    }
    var staged =
        db.first(
            CatalogSnapshot.class,
            "select s from CatalogSnapshot s where s.semester.id=:s and s.contentHash=:h and"
                + " s.status='STAGING' order by s.id",
            Map.of("s", id, "h", hash));
    if (staged.isPresent()) {
      staged.get().setVerifiedAt(time.now());
      return new SyncResult("REVIEW_REQUIRED", staged.get().getId().toString());
    }
    var snapshot = new CatalogSnapshot();
    snapshot.setSemester(semester);
    snapshot.setContentHash(hash);
    snapshot.setSourceVersion(data.sourceVersion());
    snapshot.setSourceKind(source.sourceKind());
    snapshot.setVerifiedAt(time.now());
    db.persist(snapshot);
    Map<String, Course> courses = new HashMap<>();
    for (var input : data.courses()) {
      var c = new Course();
      c.setSnapshot(snapshot);
      c.setLegacyCourseId(input.legacyId());
      c.setCode(input.code());
      c.setName(input.name());
      c.setDepartment(input.department());
      c.setCredits(input.credits());
      db.persist(c);
      courses.put(input.legacyId(), c);
    }
    for (var input : data.courses())
      for (String required : input.prerequisites())
        db.persist(
            new CoursePrerequisite(
                courses.get(input.legacyId()).getId(),
                courses.get(required).getId(),
                courses.get(input.legacyId()),
                courses.get(required)));
    for (var input : data.offerings()) {
      if (!semester.getCurrency().equals(input.currency()))
        throw BusinessException.invalid("目录费用币种与学期不一致");
      var o = new CourseOffering();
      o.setSemester(semester);
      o.setSnapshot(snapshot);
      o.setCourse(courses.get(input.courseLegacyId()));
      o.setLegacyOfferingId(input.legacyId());
      o.setSectionCode(input.sectionCode());
      o.setTuition(input.tuition().setScale(2));
      o.setCurrency(input.currency());
      db.persist(o);
      for (var slot : input.meetings()) {
        var m = new OfferingMeeting();
        m.setOffering(o);
        m.setWeekNo((short) slot.weekNo());
        m.setWeekday((short) slot.weekday());
        m.setStartPeriod((short) slot.startPeriod());
        m.setEndPeriodExclusive((short) slot.endPeriodExclusive());
        m.setRoom(slot.room());
        db.persist(m);
      }
    }
    for (var q : data.qualifications()) {
      var p = db.get(Professor.class, WebValues.id(q.professorId()));
      var c = courses.get(q.courseLegacyId());
      var record = new ProfessorQualification();
      record.setProfessorId(p.getId());
      record.setCourseId(c.getId());
      record.setProfessor(p);
      record.setCourse(c);
      record.setSourceRef(data.sourceVersion());
      db.persist(record);
    }
    if ("PREPARATION".equals(semester.getStatus())) {
      snapshot.setStatus("PUBLISHED");
      snapshot.setPublishedAt(time.now());
      semester.setActiveSnapshot(snapshot);
      semester.setStatus("OPEN");
      return new SyncResult("PUBLISHED", snapshot.getId().toString());
    }
    snapshot.setLastError("源内容已变化，需要评审后在新学期发布；当前目录不覆盖");
    return new SyncResult("REVIEW_REQUIRED", snapshot.getId().toString());
  }

  private void validate(CatalogPort.ExternalCatalog data, String code) {
    if (data == null
        || !code.equals(data.semesterCode())
        || data.courses() == null
        || data.offerings() == null
        || data.qualifications() == null) throw BusinessException.invalid("目录学期或结构不正确");
    Set<String> ids = new HashSet<>();
    Map<String, List<String>> graph = new HashMap<>();
    for (var c : data.courses()) {
      if (c.legacyId() == null
          || c.legacyId().isBlank()
          || c.legacyId().length() > 64
          || c.code() == null
          || c.code().isBlank()
          || c.code().length() > 64
          || c.name() == null
          || c.name().isBlank()
          || c.name().length() > 200
          || c.department() == null
          || c.department().isBlank()
          || c.department().length() > 100
          || c.credits() == null
          || c.credits().signum() <= 0
          || c.credits().compareTo(new java.math.BigDecimal("999.9")) > 0
          || c.credits().scale() > 1
          || c.prerequisites() == null
          || !ids.add(c.legacyId())) throw BusinessException.invalid("课程数据不合法或重复");
      if (new HashSet<>(c.prerequisites()).size() != c.prerequisites().size())
        throw BusinessException.invalid("先修课程重复");
      graph.put(c.legacyId(), c.prerequisites());
    }
    for (var c : data.courses())
      for (String p : c.prerequisites())
        if (!ids.contains(p) || p.equals(c.legacyId()))
          throw BusinessException.invalid("先修课程引用不正确");
    Set<String> done = new HashSet<>();
    for (String id : ids) visit(id, graph, new HashSet<>(), done);
    Set<String> offerings = new HashSet<>();
    for (var o : data.offerings()) {
      if (o.legacyId() == null
          || o.legacyId().isBlank()
          || o.legacyId().length() > 64
          || !offerings.add(o.legacyId())
          || !ids.contains(o.courseLegacyId())
          || o.sectionCode() == null
          || o.sectionCode().isBlank()
          || o.sectionCode().length() > 32
          || o.tuition() == null
          || o.tuition().signum() < 0
          || o.tuition().scale() > 2
          || o.tuition().compareTo(new java.math.BigDecimal("99999999.99")) > 0
          || o.currency() == null
          || !o.currency().matches("[A-Z]{3}")
          || o.meetings() == null
          || o.meetings().isEmpty()) throw BusinessException.invalid("教学班、费用或时间数据不合法");
      Set<String> slots = new HashSet<>();
      for (var m : o.meetings())
        if (m.weekNo() < 1
            || m.weekNo() > 30
            || m.weekday() < 1
            || m.weekday() > 7
            || m.startPeriod() < 1
            || m.endPeriodExclusive() <= m.startPeriod()
            || m.endPeriodExclusive() > 13
            || !slots.add(
                m.weekNo()
                    + ":"
                    + m.weekday()
                    + ":"
                    + m.startPeriod()
                    + ":"
                    + m.endPeriodExclusive())) throw BusinessException.invalid("教学时间片不合法或重复");
    }
    Set<String> qualifications = new HashSet<>();
    for (var q : data.qualifications())
      if (!ids.contains(q.courseLegacyId())
          || !qualifications.add(q.professorId() + ":" + q.courseLegacyId()))
        throw BusinessException.invalid("授课资格引用不正确或重复");
  }

  private void visit(
      String id, Map<String, List<String>> graph, Set<String> path, Set<String> done) {
    if (done.contains(id)) return;
    if (!path.add(id)) throw BusinessException.invalid("先修关系存在循环");
    for (String p : graph.get(id)) visit(p, graph, path, done);
    path.remove(id);
    done.add(id);
  }

  @Scheduled(fixedDelayString = "${app.catalog.sync-interval-ms:3600000}",initialDelayString="${app.catalog.sync-initial-delay-ms:10000}")
  public void tick() {
    if (!enabled) return;
    var ids =
        tx.execute(
            status ->
                db.list(
                    Long.class,
                    "select s.id from Semester s where s.status<>'CLOSED' order by s.id",
                    Map.of()));
    for (Long id : ids)
      try {
        sync(id);
      } catch (RuntimeException e) {
        org.slf4j.LoggerFactory.getLogger(getClass())
            .warn("学期 {} 目录同步未完成 ({})", id, e.getClass().getSimpleName());
      }
  }
}
