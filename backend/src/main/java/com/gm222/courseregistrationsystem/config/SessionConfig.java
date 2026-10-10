package com.gm222.courseregistrationsystem.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.session.web.http.DefaultCookieSerializer;

@Configuration
public class SessionConfig {
  @Bean
  public DefaultCookieSerializer sessionCookie(
      @Value("${server.servlet.session.cookie.secure:false}") boolean secure) {
    var serializer = new DefaultCookieSerializer();
    serializer.setCookieName("SESSION");
    serializer.setUseHttpOnlyCookie(true);
    serializer.setUseSecureCookie(secure);
    serializer.setSameSite("Lax");
    serializer.setCookiePath("/");
    return serializer;
  }
}
