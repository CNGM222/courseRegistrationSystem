package com.gm222.courseregistrationsystem.service.billing;

import com.gm222.courseregistrationsystem.model.entity.OutboxEvent;
import com.gm222.courseregistrationsystem.service.BusinessTime;
import jakarta.persistence.*;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class OutboxDispatcher {
  public record Claimed(String eventId, String claimToken, Map<String, Object> payload) {}

  @PersistenceContext private EntityManager em;
  private final TransactionTemplate tx;
  private final BusinessTime time;
  private final BillingPort port;
  private final boolean enabled;

  public OutboxDispatcher(
      PlatformTransactionManager manager,
      BusinessTime time,
      BillingPort port,
      @Value("${app.billing.dispatch-enabled:true}") boolean enabled) {
    this.tx = new TransactionTemplate(manager);
    tx.setPropagationBehavior(
        org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.time = time;
    this.port = port;
    this.enabled = enabled;
  }

  public List<Claimed> claimDue(int limit) {
    if (limit < 1 || limit > 100) throw new IllegalArgumentException("batch size");
    return tx.execute(
        status -> {
          @SuppressWarnings("unchecked")
          List<OutboxEvent> rows =
              em.createNativeQuery(
                      "select * from outbox_event where ((state in ('PENDING','RETRY') and"
                          + " next_attempt_at<=:now) or (state='IN_FLIGHT' and locked_until<=:now))"
                          + " order by next_attempt_at,event_id limit :n for update skip locked",
                      OutboxEvent.class)
                  .setParameter("now", time.now())
                  .setParameter("n", limit)
                  .getResultList();
          List<Claimed> result = new ArrayList<>();
          for (var e : rows) {
            e.setState("IN_FLIGHT");
            e.setClaimToken(UUID.randomUUID().toString());
            e.setLockedUntil(time.now().plusSeconds(30));
            e.setAttempts(e.getAttempts() + 1);
            result.add(new Claimed(e.getEventId(), e.getClaimToken(), Map.copyOf(e.getPayload())));
          }
          return result;
        });
  }

  public void dispatch(Claimed event) {
    // The claim transaction has committed before making a remote request.
    try {
      port.send(event.eventId(), event.payload());
      ackSuccess(event.eventId(), event.claimToken());
    } catch (Exception e) {
      ackFailure(event.eventId(), event.claimToken(), e);
    }
  }

  public boolean ackSuccess(String eventId, String token) {
    return Boolean.TRUE.equals(
        tx.execute(
            status ->
                em.createNativeQuery(
                            "update outbox_event set"
                                + " state='DELIVERED',delivered_at=:now,last_error=null,locked_until=null,claim_token=null,updated_at=:now"
                                + " where event_id=:id and claim_token=:token and"
                                + " state='IN_FLIGHT'")
                        .setParameter("now", time.now())
                        .setParameter("id", eventId)
                        .setParameter("token", token)
                        .executeUpdate()
                    == 1));
  }

  public boolean ackFailure(String eventId, String token, Exception failure) {
    return Boolean.TRUE.equals(
        tx.execute(
            status -> {
              var e = em.find(OutboxEvent.class, eventId, LockModeType.PESSIMISTIC_WRITE);
              if (e == null
                  || !"IN_FLIGHT".equals(e.getState())
                  || !Objects.equals(e.getClaimToken(), token)) return false;
              long delay = Math.min(1800, 30L << Math.min(6, Math.max(0, e.getAttempts() - 1)));
              if (failure instanceof HttpBillingAdapter.DeliveryFailure f
                  && f.status >= 400
                  && f.status < 500) delay = 1800;
              e.setState("RETRY");
              e.setNextAttemptAt(
                  time.now()
                      .plusSeconds(
                          delay + ThreadLocalRandom.current().nextLong(Math.max(1, delay / 10))));
              e.setLockedUntil(null);
              e.setClaimToken(null);
              e.setLastError(
                  failure instanceof HttpBillingAdapter.DeliveryFailure f
                      ? "财务系统拒绝请求（HTTP " + f.status + "），通知已保留"
                      : "财务系统暂不可用或确认无效，通知已保留并安排重试");
              return true;
            }));
  }

  @Scheduled(fixedDelayString = "${app.billing.dispatch-interval-ms:5000}",initialDelayString="${app.billing.dispatch-initial-delay-ms:5000}")
  public void tick() {
    if (!enabled) return;
    for (int i = 0; i < 10; i++) {
      try {
        var events = claimDue(1);
        if (events.isEmpty()) return;
        dispatch(events.getFirst());
      } catch (RuntimeException e) {
        org.slf4j.LoggerFactory.getLogger(getClass())
            .warn("财务通知调度暂不可用 ({})", e.getClass().getSimpleName());
        return;
      }
    }
  }
}
