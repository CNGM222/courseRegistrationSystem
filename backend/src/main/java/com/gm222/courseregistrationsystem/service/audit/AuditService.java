package com.gm222.courseregistrationsystem.service.audit;

import com.gm222.courseregistrationsystem.model.entity.*;
import com.gm222.courseregistrationsystem.repository.BusinessStore;
import com.gm222.courseregistrationsystem.service.identity.CurrentActor;
import com.gm222.courseregistrationsystem.web.RequestIds;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class AuditService {
  private final BusinessStore db;

  public AuditService(BusinessStore db) {
    this.db = db;
  }

  public void record(CurrentActor actor, String action, String type, Object id) {
    var log = new AuditLog();
    log.setActorAccount(db.get(Account.class, actor.actorId()));
    log.setAction(action);
    log.setTargetType(type);
    log.setTargetId(id.toString());
    log.setRequestId(RequestIds.current());
    log.setDetail(Map.of("result", "SUCCESS"));
    db.persist(log);
  }
}
