package com.gm222.courseregistrationsystem;

import static org.assertj.core.api.Assertions.*;

import com.gm222.courseregistrationsystem.model.dto.ApiDtos;
import com.gm222.courseregistrationsystem.service.people.SensitiveIdentifier;
import com.gm222.courseregistrationsystem.service.registration.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class PolicyTests {
  @Test
  void sensitiveHttpDtosAreRedactedInLogsButPreserveAuthorizedJsonFields() {
    var json = JsonMapper.builder().build();
    var login = new ApiDtos.Login("synthetic-user", "synthetic-private-password");
    var person =
        new ApiDtos.PersonCommand(
            "synthetic-user", "Synthetic", null, "ACTIVE", null, null, "123456789");
    var entry = new ApiDtos.GradeEntry("1", "A", null);
    var student = new ApiDtos.RosterStudent("1", "2", "Synthetic", "A", 0L);
    var item = new ApiDtos.ReportItem("1", "3", "CS1", "Course", "1", "Synthetic", "A", 0L);
    assertThat(login.toString()).doesNotContain(login.password(), login.username());
    assertThat(person.toString()).doesNotContain(person.socialSecurityNumber());
    for (var value :
        List.of(
            entry,
            new ApiDtos.Grades(List.of(entry)),
            student,
            new ApiDtos.Roster(null, true, List.of(student)),
            item,
            new ApiDtos.ReportCard("4", "Synthetic", List.of(item)))) {
      assertThat(value.toString()).doesNotContain("grade=A");
    }
    assertThat(json.readTree(json.writeValueAsString(login)).get("password").asText())
        .isEqualTo(login.password());
    assertThat(json.readTree(json.writeValueAsString(student)).get("grade").asText())
        .isEqualTo("A");
  }

  @Test
  void halfOpenSlotsConflictOnlyInSameWeekAndDay() {
    var p = new ConflictPolicy();
    var a = new ConflictPolicy.Slot(1L, 1, 2, 1, 3);
    assertThat(p.check(List.of(a, new ConflictPolicy.Slot(2L, 1, 2, 3, 5)))).isEmpty();
    assertThat(p.check(List.of(a, new ConflictPolicy.Slot(2L, 2, 2, 1, 3)))).isEmpty();
    assertThat(p.check(List.of(a, new ConflictPolicy.Slot(2L, 1, 3, 1, 3)))).isEmpty();
    assertThat(p.check(List.of(a, new ConflictPolicy.Slot(2L, 1, 2, 2, 4)))).hasSize(1);
  }

  @Test
  void prerequisitesRequireEndedPassingHistory() {
    var p = new PrerequisitePolicy();
    for (String g : List.of("A", "B", "C", "D")) assertThat(p.passed(g, true)).isTrue();
    for (String g : List.of("F", "I", "")) assertThat(p.passed(g, true)).isFalse();
    assertThat(p.passed(null, true)).isFalse();
    assertThat(p.passed("A", false)).isFalse();
    assertThat(p.missing(Set.of("X", "Y"), Set.of("X"))).containsExactly("Y");
  }

  @Test
  void encryptedIdentifiersUseRandomIvAndStableKeyedFingerprint() {
    var s = new SensitiveIdentifier("secret");
    var a = s.encrypt("123456789");
    var b = s.encrypt("123456789");
    assertThat(a).isNotEqualTo(b);
    assertThat(s.mask(a)).isEqualTo("***-**-6789");
    assertThat(s.fingerprint("123-45-6789")).isEqualTo(s.fingerprint("123456789"));
    assertThat(s.fingerprint("123456789"))
        .isNotEqualTo(new SensitiveIdentifier("different").fingerprint("123456789"));
  }
}
