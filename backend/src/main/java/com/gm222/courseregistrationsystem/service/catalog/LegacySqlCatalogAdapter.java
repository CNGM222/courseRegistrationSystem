package com.gm222.courseregistrationsystem.service.catalog;

import com.gm222.courseregistrationsystem.model.dto.ApiDtos.Meeting;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.catalog.source", havingValue = "legacy")
public class LegacySqlCatalogAdapter implements CatalogPort {
  private final String url, user, password;

  public LegacySqlCatalogAdapter(
      @Value("${app.catalog.legacy-url:}") String url,
      @Value("${app.catalog.legacy-user:}") String user,
      @Value("${app.catalog.legacy-password:}") String password) {
    this.url = url;
    this.user = user;
    this.password = password;
  }

  public ExternalCatalog fetch(String code, Instant deadline) throws Exception {
    Properties props = new Properties();
    props.setProperty("user", user);
    props.setProperty("password", password);
    props.setProperty("connectTimeout", "2000");
    props.setProperty("socketTimeout", "5000");
    try (var c = DriverManager.getConnection(url, props)) {
      c.setReadOnly(true);
      var prerequisites = new HashMap<String, List<String>>();
      try (var q =
              query(
                  c,
                  "select course_id,prerequisite_id from crs_prerequisites where semester_code=?"
                      + " order by course_id,prerequisite_id",
                  code);
          var r = q.executeQuery()) {
        while (r.next())
          prerequisites.computeIfAbsent(r.getString(1), x -> new ArrayList<>()).add(r.getString(2));
      }
      List<CourseData> courses = new ArrayList<>();
      try (var q =
              query(
                  c,
                  "select legacy_id,code,name,department,credits from crs_courses where"
                      + " semester_code=? order by legacy_id",
                  code);
          var r = q.executeQuery()) {
        while (r.next())
          courses.add(
              new CourseData(
                  r.getString(1),
                  r.getString(2),
                  r.getString(3),
                  r.getString(4),
                  r.getBigDecimal(5),
                  prerequisites.getOrDefault(r.getString(1), List.of())));
      }
      var meetings = new HashMap<String, List<Meeting>>();
      try (var q =
              query(
                  c,
                  "select offering_id,week_no,weekday,start_period,end_period_exclusive,room from"
                      + " crs_meetings where semester_code=? order by"
                      + " offering_id,week_no,weekday,start_period",
                  code);
          var r = q.executeQuery()) {
        while (r.next())
          meetings
              .computeIfAbsent(r.getString(1), x -> new ArrayList<>())
              .add(new Meeting(r.getInt(2), r.getInt(3), r.getInt(4), r.getInt(5), r.getString(6)));
      }
      List<OfferingData> offerings = new ArrayList<>();
      try (var q =
              query(
                  c,
                  "select legacy_id,course_id,section_code,tuition,currency from crs_offerings"
                      + " where semester_code=? order by legacy_id",
                  code);
          var r = q.executeQuery()) {
        while (r.next())
          offerings.add(
              new OfferingData(
                  r.getString(1),
                  r.getString(2),
                  r.getString(3),
                  r.getBigDecimal(4),
                  r.getString(5),
                  meetings.getOrDefault(r.getString(1), List.of())));
      }
      List<Qualification> qualifications = new ArrayList<>();
      try (var q =
              query(
                  c,
                  "select professor_id,course_id from crs_qualifications where semester_code=?"
                      + " order by professor_id,course_id",
                  code);
          var r = q.executeQuery()) {
        while (r.next()) qualifications.add(new Qualification(r.getString(1), r.getString(2)));
      }
      return new ExternalCatalog(code, null, courses, offerings, qualifications);
    }
  }

  private PreparedStatement query(Connection c, String sql, String code) throws SQLException {
    var q = c.prepareStatement(sql);
    q.setQueryTimeout(8);
    q.setString(1, code);
    return q;
  }

  public String sourceKind() {
    return "LEGACY";
  }
}
