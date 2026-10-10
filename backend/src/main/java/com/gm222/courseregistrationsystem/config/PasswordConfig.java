package com.gm222.courseregistrationsystem.config;

import org.springframework.context.annotation.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class PasswordConfig {
  @Bean
  public PasswordEncoder passwords() {
    return new BCryptPasswordEncoder(12);
  }

  @Bean
  public org.springframework.security.core.userdetails.UserDetailsService noDefaultUser() {
    return username -> {
      throw new org.springframework.security.core.userdetails.UsernameNotFoundException(
          "认证由账号服务处理");
    };
  }
}
