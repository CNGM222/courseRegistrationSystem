package com.gm222.courseregistrationsystem.service.identity;

import jakarta.servlet.http.*;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.stereotype.Component;

@Component
public class SessionPolicy {
  public void login(CurrentActor actor, HttpServletRequest request, HttpServletResponse response) {
    request.getSession(true);
    request.changeSessionId();
    var context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(
        UsernamePasswordAuthenticationToken.authenticated(
            actor, null, List.of(new SimpleGrantedAuthority("ROLE_" + actor.role()))));
    SecurityContextHolder.setContext(context);
    new HttpSessionSecurityContextRepository().saveContext(context, request, response);
    new HttpSessionCsrfTokenRepository().saveToken(null, request, response);
  }

  public void logout(HttpServletRequest request) {
    var session = request.getSession(false);
    if (session != null) session.invalidate();
    SecurityContextHolder.clearContext();
  }
}
