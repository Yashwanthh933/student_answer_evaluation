package com.answerscripts.user;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class UserManagementService {

    private static final int MINIMUM_PASSWORD_LENGTH = 10;
    private static final List<String> ROLES = List.of("SUPER_ADMIN", "EXAM_CONTROLLER", "SCANNER");

    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;

    public UserManagementService(JdbcTemplate jdbcTemplate, PasswordEncoder passwordEncoder) {
        this.jdbcTemplate = jdbcTemplate;
        this.passwordEncoder = passwordEncoder;
    }

    public List<UserView> listUsers() {
        return jdbcTemplate.query("""
                SELECT u.id, u.username, u.display_name, u.role, u.enabled, u.must_change_password,
                       creator.username AS created_by_username, u.created_at, u.last_login_at
                FROM users AS u
                LEFT JOIN users AS creator ON creator.id = u.created_by
                ORDER BY u.username
                """, (resultSet, rowNumber) -> new UserView(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("username"),
                resultSet.getString("display_name"),
                resultSet.getString("role"),
                resultSet.getBoolean("enabled"),
                resultSet.getBoolean("must_change_password"),
                resultSet.getString("created_by_username"),
                resultSet.getTimestamp("created_at").toInstant().toString(),
                resultSet.getTimestamp("last_login_at") == null
                        ? null
                        : resultSet.getTimestamp("last_login_at").toInstant().toString()));
    }

    @Transactional
    public UserView createUser(
            String actorUsername, String username, String displayName, String role, String initialPassword) {
        String cleanUsername = requiredText(username, "Username", 100);
        String cleanDisplayName = requiredText(displayName, "Display name", 200);
        String cleanRole = role == null ? "" : role.trim().toUpperCase(Locale.ROOT);
        if (!ROLES.contains(cleanRole)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a valid user role.");
        }
        requirePassword(initialPassword);
        UUID actorId = userId(actorUsername);
        if (Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM users WHERE username = ?)", Boolean.class, cleanUsername))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That username is already in use.");
        }

        jdbcTemplate.update("""
                INSERT INTO users (username, display_name, password_hash, role, enabled,
                                   must_change_password, created_by)
                VALUES (?, ?, ?, ?, TRUE, TRUE, ?)
                """, cleanUsername, cleanDisplayName, passwordEncoder.encode(initialPassword), cleanRole, actorId);
        audit(actorId, "USER_CREATED", cleanUsername);
        return findUser(cleanUsername);
    }

    @Transactional
    public void setEnabled(String actorUsername, String targetUsername, boolean enabled) {
        UUID actorId = userId(actorUsername);
        List<UUID> enabledSuperAdmins = jdbcTemplate.query(
                "SELECT id FROM users WHERE role = 'SUPER_ADMIN' AND enabled = TRUE ORDER BY id FOR UPDATE",
                (resultSet, rowNumber) -> resultSet.getObject("id", UUID.class));
        List<TargetAccount> targets = jdbcTemplate.query(
                "SELECT id, role, enabled FROM users WHERE username = ? FOR UPDATE",
                (resultSet, rowNumber) -> new TargetAccount(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("role"),
                        resultSet.getBoolean("enabled")),
                targetUsername);
        if (targets.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found.");
        }
        TargetAccount target = targets.get(0);
        if (target.enabled() == enabled) {
            return;
        }
        if (!enabled && target.role().equals("SUPER_ADMIN") && enabledSuperAdmins.size() <= 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The last enabled Super Admin cannot be disabled.");
        }

        jdbcTemplate.update("UPDATE users SET enabled = ? WHERE id = ?", enabled, target.id());
        audit(actorId, enabled ? "USER_ENABLED" : "USER_DISABLED", targetUsername);
    }

    @Transactional
    public void resetPassword(String actorUsername, String targetUsername, String temporaryPassword) {
        requirePassword(temporaryPassword);
        UUID actorId = userId(actorUsername);
        UUID targetId = findTargetId(targetUsername);
        jdbcTemplate.update(
                "UPDATE users SET password_hash = ?, must_change_password = TRUE WHERE id = ?",
                passwordEncoder.encode(temporaryPassword),
                targetId);
        audit(actorId, "PASSWORD_RESET", targetUsername);
    }

    @Transactional
    public void changePassword(String username, String currentPassword, String newPassword) {
        requirePassword(newPassword);
        List<PasswordAccount> accounts = jdbcTemplate.query(
                "SELECT id, password_hash FROM users WHERE username = ? AND enabled = TRUE FOR UPDATE",
                (resultSet, rowNumber) -> new PasswordAccount(
                        resultSet.getObject("id", UUID.class), resultSet.getString("password_hash")),
                username);
        if (accounts.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "The account is not enabled.");
        }
        PasswordAccount account = accounts.get(0);
        if (currentPassword == null || !passwordEncoder.matches(currentPassword, account.passwordHash())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The current password is incorrect.");
        }
        jdbcTemplate.update(
                "UPDATE users SET password_hash = ?, must_change_password = FALSE WHERE id = ?",
                passwordEncoder.encode(newPassword),
                account.id());
        audit(account.id(), "PASSWORD_CHANGED", username);
    }

    private UserView findUser(String username) {
        return jdbcTemplate.queryForObject("""
                SELECT u.id, u.username, u.display_name, u.role, u.enabled, u.must_change_password,
                       creator.username AS created_by_username, u.created_at, u.last_login_at
                FROM users AS u
                LEFT JOIN users AS creator ON creator.id = u.created_by
                WHERE u.username = ?
                """, (resultSet, rowNumber) -> new UserView(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("username"),
                resultSet.getString("display_name"),
                resultSet.getString("role"),
                resultSet.getBoolean("enabled"),
                resultSet.getBoolean("must_change_password"),
                resultSet.getString("created_by_username"),
                resultSet.getTimestamp("created_at").toInstant().toString(),
                resultSet.getTimestamp("last_login_at") == null
                        ? null
                        : resultSet.getTimestamp("last_login_at").toInstant().toString()),
                username);
    }

    private UUID findTargetId(String username) {
        List<UUID> ids = jdbcTemplate.query(
                "SELECT id FROM users WHERE username = ? FOR UPDATE",
                (resultSet, rowNumber) -> resultSet.getObject("id", UUID.class),
                username);
        if (ids.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found.");
        }
        return ids.get(0);
    }

    private UUID userId(String username) {
        List<UUID> ids = jdbcTemplate.query(
                "SELECT id FROM users WHERE username = ? AND enabled = TRUE",
                (resultSet, rowNumber) -> resultSet.getObject("id", UUID.class),
                username);
        if (ids.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "The signed-in account is not enabled.");
        }
        return ids.get(0);
    }

    private void audit(UUID actorId, String action, String targetUsername) {
        jdbcTemplate.update(
                "INSERT INTO user_management_audit (id, actor_user_id, action, target_username) VALUES (?, ?, ?, ?)",
                UUID.randomUUID(), actorId, action, targetUsername);
    }

    private String requiredText(String value, String label, int maximumLength) {
        String cleanValue = value == null ? "" : value.trim();
        if (cleanValue.isEmpty() || cleanValue.length() > maximumLength) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, label + " is required and must be at most " + maximumLength + " characters.");
        }
        return cleanValue;
    }

    private void requirePassword(String password) {
        if (password == null || password.isBlank() || password.length() < MINIMUM_PASSWORD_LENGTH) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Passwords must be at least 10 characters long.");
        }
    }

    public record UserView(
            UUID id,
            String username,
            String displayName,
            String role,
            boolean enabled,
            boolean mustChangePassword,
            String createdByUsername,
            String createdAt,
            String lastLoginAt) {}

    private record TargetAccount(UUID id, String role, boolean enabled) {}

    private record PasswordAccount(UUID id, String passwordHash) {}
}
