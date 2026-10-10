package com.gm222.courseregistrationsystem.service.billing;

import java.util.Map;

public interface BillingPort {
  void send(String eventId, Map<String, Object> payload) throws Exception;
}
