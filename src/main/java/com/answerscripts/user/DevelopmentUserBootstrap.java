package com.answerscripts.user;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class DevelopmentUserBootstrap implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(DevelopmentUserBootstrap.class);

    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;
    private final List<BootstrapAccount> accounts;

    public DevelopmentUserBootstrap(
            JdbcTemplate jdbcTemplate,
            PasswordEncoder passwordEncoder,
            @Value("${demo.scanner.password:}") String scannerPassword,
            @Value("${demo.uploader.password:}") String examControllerPassword,
            @Value("${demo.super-admin.password:}") String superAdminPassword) {
        this.jdbcTemplate = jdbcTemplate;
        this.passwordEncoder = passwordEncoder;
        this.accounts = List.of(
                new BootstrapAccount("scanner", "Scanner", "SCANNER", "DEMO_SCANNER_PASSWORD", scannerPassword),
                new BootstrapAccount("paper_uploader", "Exam Controller", "EXAM_CONTROLLER",
                        "DEMO_UPLOADER_PASSWORD", examControllerPassword),
                new BootstrapAccount("super_admin", "Super Admin", "SUPER_ADMIN",
                        "DEMO_SUPER_ADMIN_PASSWORD", superAdminPassword));
    }

    @Override
    public void run(ApplicationArguments args) {
        Integer accountCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users", Integer.class);
        if (accountCount != null && accountCount > 0) {
            return;
        }

        logger.warn("The users table is empty; one-time development account bootstrap is running.");
        int createdCount = 0;
        for (BootstrapAccount account : accounts) {
            if (account.password().isBlank()) {
                logger.warn("Skipping development account '{}' because {} is not set.",
                        account.username(), account.environmentVariable());
                continue;
            }
            jdbcTemplate.update(
                    "INSERT INTO users (username, display_name, password_hash, role, enabled, must_change_password) "
                            + "VALUES (?, ?, ?, ?, TRUE, FALSE)",
                    account.username(),
                    account.displayName(),
                    passwordEncoder.encode(account.password()),
                    account.role());
            logger.warn("Created development account '{}' with role {}.", account.username(), account.role());
            createdCount++;
        }
        if (createdCount == 0) {
            logger.warn("No development accounts were created; set one or more DEMO_*_PASSWORD variables and restart.");
        }
    }

    private record BootstrapAccount(
            String username, String displayName, String role, String environmentVariable, String password) {}
}
