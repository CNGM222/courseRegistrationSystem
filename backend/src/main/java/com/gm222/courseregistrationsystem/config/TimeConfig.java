package com.gm222.courseregistrationsystem.config;

import java.time.Clock;
import org.springframework.context.annotation.*;

@Configuration
public class TimeConfig {
  @Bean
  public Clock clock() {
    return Clock.systemUTC();
  }
}
