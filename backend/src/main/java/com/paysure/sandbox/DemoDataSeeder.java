package com.paysure.sandbox;

import com.paysure.account.application.AccountService;
import com.paysure.config.SeedProperties;
import com.paysure.identity.application.AuthService;
import com.paysure.identity.domain.Role;
import com.paysure.identity.domain.User;
import com.paysure.identity.repository.UserRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Seeds demo users with funded accounts and VPAs so every scenario can be demonstrated without
 * editing the database. Idempotent: users that already exist are left untouched.
 */
@Component
@ConditionalOnProperty(prefix = "paysure.seed", name = "enabled", havingValue = "true")
public class DemoDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    record DemoUser(String email, String fullName, Role role, String accountName, String handle) {
    }

    static final List<DemoUser> DEMO_USERS = List.of(
            new DemoUser("ananya@example.com", "Ananya Sharma", Role.USER, "Ananya Savings", "ananya"),
            new DemoUser("rahul@example.com", "Rahul Verma", Role.USER, "Rahul Savings", "rahul"),
            new DemoUser("merchant@example.com", "Corner Tea Stall", Role.USER, "Tea Stall Current", "teastall"),
            new DemoUser("labadmin@example.com", "Reliability Lab Admin", Role.ADMIN_DEMO, "Lab Ops", "labadmin"));

    private final SeedProperties props;
    private final UserRepository users;
    private final AuthService authService;
    private final AccountService accountService;
    private final TransactionTemplate tx;

    public DemoDataSeeder(SeedProperties props, UserRepository users, AuthService authService,
                          AccountService accountService, TransactionTemplate tx) {
        this.props = props;
        this.users = users;
        this.authService = authService;
        this.accountService = accountService;
        this.tx = tx;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (props.demoPassword() == null || props.demoPassword().isBlank()) {
            log.warn("Demo seeding is enabled but PAYSURE_DEMO_PASSWORD is empty; skipping demo users.");
            return;
        }
        int created = 0;
        for (DemoUser demo : DEMO_USERS) {
            if (users.existsByEmail(demo.email())) {
                continue;
            }
            // One transaction per demo user: a user is never left without its account and VPA.
            tx.executeWithoutResult(status -> {
                User user = authService.createUser(demo.email(), props.demoPassword(), demo.fullName(), demo.role());
                accountService.openAccount(user.getId(), demo.accountName(), demo.handle());
            });
            created++;
        }
        log.info("Demo seed complete: {} user(s) created, {} already present.", created, DEMO_USERS.size() - created);
    }
}
