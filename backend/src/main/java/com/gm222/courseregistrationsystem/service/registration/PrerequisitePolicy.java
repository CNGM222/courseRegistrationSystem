package com.gm222.courseregistrationsystem.service.registration;

import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class PrerequisitePolicy {
  public boolean passed(String grade, boolean ended) {
    return ended && Set.of("A", "B", "C", "D").contains(grade == null ? "" : grade);
  }

  public Set<String> missing(Set<String> required, Set<String> passed) {
    Set<String> result = new TreeSet<>(required);
    result.removeAll(passed);
    return result;
  }
}
