package com.gm222.courseregistrationsystem.service.identity;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.stereotype.Component;

@Component
public class SessionRevoker {
  private final ObjectProvider<JdbcIndexedSessionRepository> sessions;

  public SessionRevoker(ObjectProvider<JdbcIndexedSessionRepository> sessions) {
    this.sessions = sessions;
  }

  public void revoke(String username) {
    var repository = sessions.getIfAvailable();
    if (repository != null)
      repository.findByPrincipalName(username).keySet().forEach(repository::deleteById);
  }
}
