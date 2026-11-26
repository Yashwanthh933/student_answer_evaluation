package com.answerscripts.user;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class UserAccountService implements UserDetailsService {

    private final JdbcTemplate jdbcTemplate;

    public UserAccountService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        List<AccountLogin> accounts = jdbcTemplate.query(
                "SELECT username, password_hash, role, enabled, must_change_password FROM users WHERE username = ?",
                (resultSet, rowNumber) -> new AccountLogin(
                        resultSet.getString("username"),
                        resultSet.getString("password_hash"),
                        resultSet.getString("role"),
                        resultSet.getBoolean("enabled"),
                        resultSet.getBoolean("must_change_password")),
                username);
        if (accounts.isEmpty()) {
            throw new UsernameNotFoundException("User not found.");
        }

        AccountLogin account = accounts.get(0);
        return new AccountPrincipal(
                account.username(), account.passwordHash(), account.role(), account.enabled(), account.mustChangePassword());
    }

    public void recordSuccessfulLogin(String username) {
        jdbcTemplate.update(
                "UPDATE users SET last_login_at = CURRENT_TIMESTAMP WHERE username = ?",
                username);
    }

    public AccountState accountState(String username) {
        List<AccountState> accounts = jdbcTemplate.query(
                "SELECT enabled, must_change_password FROM users WHERE username = ?",
                (resultSet, rowNumber) -> new AccountState(
                        resultSet.getBoolean("enabled"),
                        resultSet.getBoolean("must_change_password")),
                username);
        return accounts.isEmpty() ? null : accounts.get(0);
    }

    private record AccountLogin(
            String username, String passwordHash, String role, boolean enabled, boolean mustChangePassword) {}

    public record AccountState(boolean enabled, boolean mustChangePassword) {}
}
