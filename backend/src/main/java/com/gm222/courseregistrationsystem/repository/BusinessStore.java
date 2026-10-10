package com.gm222.courseregistrationsystem.repository;

import com.gm222.courseregistrationsystem.exception.BusinessException;
import com.gm222.courseregistrationsystem.model.entity.*;
import jakarta.persistence.*;
import java.util.*;
import org.springframework.stereotype.Repository;

/** Explicit aggregate queries; every caller owns its transaction and authorization. */
@Repository
public class BusinessStore {
  @PersistenceContext private EntityManager em;

  public <T> T get(Class<T> type, Object id) {
    T result = em.find(type, id);
    if (result == null) throw BusinessException.missing("指定资源不存在");
    return result;
  }

  public <T> T lock(Class<T> type, Long id) {
    String table = type.getAnnotation(Table.class).name();
    var ids =
        em.createNativeQuery("select id from " + table + " where id=:id for update")
            .setParameter("id", id)
            .getResultList();
    if (ids.isEmpty()) throw BusinessException.missing("指定资源不存在");
    T result = get(type, id);
    em.refresh(result);
    return result;
  }

  public Semester gate(Long id) {
    // Lock only the gate row, never Hibernate's eager inverse one-to-one joins.
    boolean mysql =
        em.unwrap(org.hibernate.Session.class)
            .doReturningWork(c -> c.getMetaData().getDatabaseProductName().equals("MySQL"));
    var ids =
        em.createNativeQuery(
                "select id from semester where id=:id " + (mysql ? "for share" : "for update"))
            .setParameter("id", id)
            .getResultList();
    if (ids.isEmpty()) throw BusinessException.missing("学期不存在");
    Semester s = get(Semester.class, id);
    em.refresh(s);
    return s;
  }

  public Semester closeGate(Long id) {
    var result =
        em.createNativeQuery("select id from semester where id=:id for update nowait")
            .setParameter("id", id)
            .getResultList();
    if (result.isEmpty()) throw BusinessException.missing("学期不存在");
    Semester s = get(Semester.class, id);
    em.refresh(s);
    return s;
  }

  public <T> List<T> list(Class<T> type, String jpql, Map<String, ?> params) {
    var q = em.createQuery(jpql, type);
    params.forEach(q::setParameter);
    return q.getResultList();
  }

  public <T> Optional<T> first(Class<T> type, String jpql, Map<String, ?> params) {
    var q = em.createQuery(jpql, type);
    params.forEach(q::setParameter);
    return q.setMaxResults(1).getResultStream().findFirst();
  }

  public <T> List<T> page(Class<T> type, String jpql, Map<String, ?> params, int page, int size) {
    var q = em.createQuery(jpql, type);
    params.forEach(q::setParameter);
    return q.setFirstResult((page - 1) * size).setMaxResults(size).getResultList();
  }

  public long count(String jpql, Map<String, ?> params) {
    return list(Long.class, jpql, params).getFirst();
  }

  public void persist(Object entity) {
    em.persist(entity);
  }

  public void flush() {
    em.flush();
  }


  public void bumpProfessor(Professor professor) {
    em.flush();
    em.createNativeQuery("update professors set version=version+1 where id=:id")
        .setParameter("id", professor.getId())
        .executeUpdate();
    em.refresh(professor);
  }

  public List<CourseOffering> offerings(Long semesterId) {
    return list(
        CourseOffering.class,
        "select o from CourseOffering o where o.semester.id=:s order by o.id",
        Map.of("s", semesterId));
  }

  public List<CourseOffering> lockOfferings(Collection<Long> ids) {
    return ids.stream().distinct().sorted().map(id -> lock(CourseOffering.class, id)).toList();
  }

  public Optional<Schedule> schedule(Long student, Long semester) {
    return first(
        Schedule.class,
        "select s from Schedule s where s.student.id=:p and s.semester.id=:s",
        Map.of("p", student, "s", semester));
  }

  public List<ScheduleChoice> choices(Schedule schedule, Long revision) {
    if (revision == null) return List.of();
    return list(
        ScheduleChoice.class,
        "select c from ScheduleChoice c where c.schedule.id=:s and c.revision=:r order by c.kind"
            + " desc,c.priority",
        Map.of("s", schedule.getId(), "r", revision));
  }

  public List<Enrollment> enrollments(Schedule s) {
    return list(
        Enrollment.class,
        "select e from Enrollment e where e.schedule.id=:s order by e.id",
        Map.of("s", s.getId()));
  }

  public List<CoursePrerequisite> prerequisites(Long courseId) {
    return list(
        CoursePrerequisite.class,
        "select p from CoursePrerequisite p where p.courseId=:c order by p.prerequisiteCourseId",
        Map.of("c", courseId));
  }

  public List<OfferingMeeting> meetings(Long offeringId) {
    return list(
        OfferingMeeting.class,
        "select m from OfferingMeeting m where m.offering.id=:o order by"
            + " m.weekNo,m.weekday,m.startPeriod",
        Map.of("o", offeringId));
  }

  public void reserve(CourseOffering o) {
    em.flush();
    int n =
        em.createNativeQuery(
                "update course_offering set enrolled_count=enrolled_count+1,version=version+1 where"
                    + " id=:id and status='OPEN' and enrolled_count<max_students")
            .setParameter("id", o.getId())
            .executeUpdate();
    if (n != 1)
      throw BusinessException.conflict("CAPACITY_FULL", o.getCourse().getName() + "已满员或停止注册");
    em.refresh(o);
  }

  public void release(CourseOffering o) {
    em.flush();
    int n =
        em.createNativeQuery(
                "update course_offering set enrolled_count=enrolled_count-1,version=version+1 where"
                    + " id=:id and enrolled_count>0")
            .setParameter("id", o.getId())
            .executeUpdate();
    if (n != 1) throw BusinessException.conflict("COUNT_MISMATCH", "教学班人数不一致，操作已取消");
    em.refresh(o);
  }
}
