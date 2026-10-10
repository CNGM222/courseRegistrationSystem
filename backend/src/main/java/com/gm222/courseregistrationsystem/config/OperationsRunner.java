package com.gm222.courseregistrationsystem.config;

import com.gm222.courseregistrationsystem.model.entity.Account;
import com.gm222.courseregistrationsystem.repository.BusinessStore;
import com.gm222.courseregistrationsystem.service.catalog.CatalogSyncService;
import com.gm222.courseregistrationsystem.service.identity.SessionRevoker;
import com.gm222.courseregistrationsystem.web.WebValues;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Local administration only: no HTTP endpoint and no plaintext credential output. */
@Component
@ConditionalOnProperty(name = "app.operation")
public class OperationsRunner implements ApplicationRunner {
  private final BusinessStore db;
  private final PasswordEncoder passwords;
  private final SessionRevoker sessions;
  private final CatalogSyncService catalog;
  private final TransactionTemplate tx;
  private final ConfigurableApplicationContext context;
  private final String operation, username, password, semester;

  public OperationsRunner(
      BusinessStore db,
      PasswordEncoder passwords,
      SessionRevoker sessions,
      CatalogSyncService catalog,
      PlatformTransactionManager manager,
      ConfigurableApplicationContext context,
      @Value("${app.operation}") String operation,
      @Value("${app.provision.username:}") String username,
      @Value("${app.provision.password:}") String password,
      @Value("${app.sync.semester-id:}") String semester) {
    this.db = db;
    this.passwords = passwords;
    this.sessions = sessions;
    this.catalog = catalog;
    this.tx = new TransactionTemplate(manager);
    this.context = context;
    this.operation = operation;
    this.username = username;
    this.password = password;
    this.semester = semester;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (!"none".equals(context.getEnvironment().getProperty("spring.main.web-application-type")))
      throw new IllegalStateException("管理命令必须使用 spring.main.web-application-type=none");
    switch (operation) {
      case "provision-account" ->
          tx.executeWithoutResult(
              status -> {
                if (username.isBlank()
                    || username.length() > 64
                    || password.length() < 12
                    || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72)
                  throw new IllegalArgumentException("管理凭据需要非空账号和至少 12 字符、至多 72 字节密码");
                var normalized = username.strip().toLowerCase(Locale.ROOT);
                var account =
                    db.first(
                            Account.class,
                            "select a from Account a where a.username=:u",
                            Map.of("u", normalized))
                        .orElse(null);
                if (account == null) {
                  account = new Account();
                  account.setUsername(normalized);
                  account.setRole("REGISTRAR");
                  account.setPasswordHash(passwords.encode(password));
                  db.persist(account);
                } else account = db.lock(Account.class, account.getId());
                account.setPasswordHash(passwords.encode(password));
                account.setFailedAttempts(0);
                account.setLockedUntil(null);
                sessions.revoke(normalized);
              });
      case "sync-catalog" -> {
        var result = catalog.sync(WebValues.id(semester));
        org.slf4j.LoggerFactory.getLogger(getClass())
            .info("目录同步结果 {}，快照 {}", result.state(), result.snapshotId());
      }
      default -> throw new IllegalArgumentException("不支持的管理操作");
    }
    context.close();
  }
}
