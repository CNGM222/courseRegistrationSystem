package com.gm222.courseregistrationsystem.service.catalog;

import com.gm222.courseregistrationsystem.exception.BusinessException;
import com.gm222.courseregistrationsystem.model.dto.ApiDtos.*;
import com.gm222.courseregistrationsystem.model.entity.*;
import com.gm222.courseregistrationsystem.repository.BusinessStore;
import com.gm222.courseregistrationsystem.service.BusinessTime;
import com.gm222.courseregistrationsystem.service.identity.CurrentActor;
import com.gm222.courseregistrationsystem.web.WebValues;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true,timeout=8)
public class CatalogQueryService {
  private final BusinessStore db;
  private final Views views;
  private final BusinessTime time;
  private final long ttl;

  public CatalogQueryService(
      BusinessStore db,
      Views views,
      BusinessTime time,
      @Value("${app.catalog.ttl-hours:24}") long ttl) {
    this.db = db;
    this.views = views;
    this.time = time;
    this.ttl = ttl;
  }

  public CatalogSnapshot requireSnapshot(Semester s) {
    var snap = s.getActiveSnapshot();
    if (snap == null
        || !"PUBLISHED".equals(snap.getStatus())
        || !snap.getSemester().getId().equals(s.getId())
        || snap.getVerifiedAt() == null
        || snap.getVerifiedAt().isBefore(time.now().minusHours(ttl)))
      throw new BusinessException(503, "CATALOG_UNAVAILABLE", "课程目录暂不可用，请稍后重试");
    return snap;
  }

  public void requireOffering(Semester s, CourseOffering o) {
    var snap = requireSnapshot(s);
    if (!o.getSemester().getId().equals(s.getId()) || !o.getSnapshot().getId().equals(snap.getId()))
      throw BusinessException.invalid("教学班不属于本学期的有效课程目录");
  }

  public List<SemesterView> semesters(CurrentActor actor) {
    actor.requireRole("STUDENT", "PROFESSOR", "REGISTRAR");
    return db
        .list(
            Semester.class,
            "select s from Semester s order by s.startDate desc,s.id desc",
            Map.of())
        .stream()
        .map(views::semester)
        .toList();
  }

  public Page<Offering> offerings(
      CurrentActor actor, Long id, int page, int size, String keyword, String department) {
    actor.requireRole("STUDENT", "PROFESSOR", "REGISTRAR");
    WebValues.page(page, size);
    var snap = requireSnapshot(db.get(Semester.class, id));
    String from = " from CourseOffering o where o.semester.id=:s and o.snapshot.id=:snap";
    Map<String, Object> params = new HashMap<>(Map.of("s", id, "snap", snap.getId()));
    if (keyword != null && !keyword.isBlank()) {
      from +=
          " and (lower(o.course.code) like :q escape '!' or lower(o.course.name) like :q escape"
              + " '!')";
      params.put("q", like(keyword));
    }
    if (department != null && !department.isBlank()) {
      from += " and o.course.department=:d";
      params.put("d", department);
    }
    var items =
        db.page(
            CourseOffering.class,
            "select o" + from + " order by o.course.code,o.sectionCode,o.id",
            params,
            page,
            size);
    var departments =
        db.list(
            String.class,
            "select distinct o.course.department from CourseOffering o where o.semester.id=:s and"
                + " o.snapshot.id=:snap order by o.course.department",
            Map.of("s", id, "snap", snap.getId()));
    return new Page<>(
        items.stream().map(views::offering).toList(),
        page,
        size,
        db.count("select count(o)" + from, params),
        BusinessTime.iso(snap.getVerifiedAt()),
        departments);
  }

  public Offering offering(CurrentActor actor, Long id) {
    actor.requireRole("STUDENT", "PROFESSOR", "REGISTRAR");
    var o = db.get(CourseOffering.class, id);
    requireOffering(o.getSemester(), o);
    return views.offering(o);
  }

  public List<Offering> availability(CurrentActor actor, Long semester, List<Long> ids) {
    actor.requireRole("STUDENT", "PROFESSOR", "REGISTRAR");
    if (ids.size() > 6 || new HashSet<>(ids).size() != ids.size())
      throw BusinessException.invalid("人数查询最多包含六个不同教学班");
    var s = db.get(Semester.class, semester);
    requireSnapshot(s);
    return ids.stream()
        .map(
            id -> {
              var o = db.get(CourseOffering.class, id);
              requireOffering(s, o);
              return views.offering(o);
            })
        .toList();
  }

  public static String like(String text) {
    return "%"
        + text.strip()
            .toLowerCase(Locale.ROOT)
            .replace("!", "!!")
            .replace("%", "!%")
            .replace("_", "!_")
        + "%";
  }
}
