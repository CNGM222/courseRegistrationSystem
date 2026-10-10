package com.gm222.courseregistrationsystem.service.billing;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class HttpBillingAdapter implements BillingPort {
  private final HttpClient client =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
  private final String url;
  private final String token;
  private final ObjectMapper json;

  public HttpBillingAdapter(
      @Value("${app.billing.url:}") String url,
      @Value("${app.billing.token:}") String token,
      ObjectMapper json) {
    this.url = url;
    this.token = token;
    this.json = json;
  }

  @Override
  public void send(String eventId, Map<String, Object> payload) throws Exception {
    if (url.isBlank()) throw new IllegalStateException("财务地址未配置");
    var builder =
        HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(5))
            .header("Content-Type", "application/json")
            .header("Idempotency-Key", eventId)
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload)));
    if (!token.isBlank()) builder.header("Authorization", "Bearer " + token);
    var result = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    if (result.statusCode() < 200 || result.statusCode() >= 300)
      throw new DeliveryFailure(result.statusCode());
    var ack = json.readTree(result.body());
    if (!eventId.equals(ack.path("eventId").asText()) || !ack.path("accepted").asBoolean())
      throw new IllegalStateException("财务确认无效");
  }

  public static class DeliveryFailure extends Exception {
    public final int status;

    public DeliveryFailure(int status) {
      super("财务返回状态 " + status);
      this.status = status;
    }
  }
}
