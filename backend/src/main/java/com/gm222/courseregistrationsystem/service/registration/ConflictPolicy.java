package com.gm222.courseregistrationsystem.service.registration;

import com.gm222.courseregistrationsystem.model.dto.ApiDtos.Issue;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class ConflictPolicy {
  public record Slot(Long offeringId, int week, int weekday, int start, int end) {}

  public List<Issue> check(List<Slot> slots) {
    List<Issue> issues = new ArrayList<>();
    Set<String> reported = new HashSet<>();
    for (int i = 0; i < slots.size(); i++)
      for (int j = i + 1; j < slots.size(); j++) {
        var a = slots.get(i);
        var b = slots.get(j);
        if (!a.offeringId().equals(b.offeringId())
            && a.week() == b.week()
            && a.weekday() == b.weekday()
            && a.start() < b.end()
            && b.start() < a.end()) {
          String key =
              Math.min(a.offeringId(), b.offeringId())
                  + ":"
                  + Math.max(a.offeringId(), b.offeringId());
          if (reported.add(key))
            issues.add(
                Issue.offering(
                    "TIME_CONFLICT",
                    "第 "
                        + a.week()
                        + " 周，星期 "
                        + a.weekday()
                        + "，教学班 "
                        + a.offeringId()
                        + " 与 "
                        + b.offeringId()
                        + " 时间冲突",
                    b.offeringId()));
        }
      }
    return issues;
  }
}
