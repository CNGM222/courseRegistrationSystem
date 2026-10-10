package com.gm222.courseregistrationsystem;

import static org.assertj.core.api.Assertions.*;

import com.gm222.courseregistrationsystem.service.billing.HttpBillingAdapter;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class BillingAdapterTests {
  @Test
  void requiresMatchingAcknowledgmentAndStableIdempotencyHeader() throws Exception {
    var json = new ObjectMapper();
    var ack = new AtomicReference<>("matching");
    var seen = new AtomicReference<String>();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/integration/invoices",
        exchange -> {
          String eventId = exchange.getRequestHeaders().getFirst("Idempotency-Key");
          seen.set(eventId);
          byte[] body =
              json.writeValueAsString(
                      Map.of(
                          "eventId",
                          "matching".equals(ack.get()) ? eventId : ack.get(),
                          "accepted",
                          true))
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getRequestBody().readAllBytes();
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
    try {
      var port =
          new HttpBillingAdapter(
              "http://127.0.0.1:" + server.getAddress().getPort() + "/integration/invoices",
              "test-service-token",
              json);
      String id = UUID.randomUUID().toString();
      var payload =
          Map.<String, Object>of(
              "eventId",
              id,
              "studentId",
              "1",
              "semesterCode",
              "TEST",
              "items",
              List.of(),
              "totalAmount",
              "0.00");
      port.send(id, payload);
      assertThat(seen.get()).isEqualTo(id);
      port.send(id, payload);
      assertThat(seen.get()).isEqualTo(id);
      ack.set("different-event");
      assertThatThrownBy(() -> port.send(id, payload))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("财务确认无效");
    } finally {
      server.stop(0);
    }
  }
}
