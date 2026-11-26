package com.answerscripts.user;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class AccountStateFilter extends OncePerRequestFilter {

    private final UserAccountService userAccountService;

    public AccountStateFilter(UserAccountService userAccountService) {
        this.userAccountService = userAccountService;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof AccountPrincipal principal) {
            UserAccountService.AccountState account = userAccountService.accountState(authentication.getName());
            if (account == null || !account.enabled()) {
                SecurityContextHolder.clearContext();
                HttpSession session = request.getSession(false);
                if (session != null) {
                    session.invalidate();
                }
                filterChain.doFilter(request, response);
                return;
            }
            if (account.mustChangePassword() && principal.mustChangePassword()
                    && !allowedDuringPasswordChange(request.getServletPath())) {
                response.sendRedirect(request.getContextPath() + "/change-password.html");
                return;
            }
        }
        filterChain.doFilter(request, response);
    }

    private boolean allowedDuringPasswordChange(String path) {
        return path.equals("/change-password.html")
                || path.equals("/api/auth/change-password")
                || path.equals("/csrf")
                || path.equals("/logout");
    }
}
