package com.answerscripts.auth;

import com.answerscripts.user.UserAccountService;
import com.answerscripts.user.AccountStateFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.SecurityContextHolderFilter;

@Configuration
public class SecurityConfiguration {

    @Bean
    SecurityFilterChain applicationSecurity(HttpSecurity http, UserAccountService userAccountService) throws Exception {
        return http
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/", "/actuator/health").permitAll()
                        .requestMatchers("/csrf").hasAnyRole("SCANNER", "EXAM_CONTROLLER", "SUPER_ADMIN")
                        .requestMatchers("/browse.html", "/submissions.html", "/api/submission-browser/**")
                        .hasAnyRole("SCANNER", "EXAM_CONTROLLER", "SUPER_ADMIN")
                        .requestMatchers("/upload.html", "/api/submissions", "/api/submissions/**")
                        .hasRole("SCANNER")
                        .requestMatchers("/question-paper.html", "/api/question-papers", "/api/question-papers/**")
                        .hasAnyRole("EXAM_CONTROLLER", "SUPER_ADMIN")
                        .requestMatchers("/exams.html", "/api/exams", "/api/exams/**")
                        .hasAnyRole("EXAM_CONTROLLER", "SUPER_ADMIN")
                        .requestMatchers("/settings.html", "/api/settings", "/api/settings/**")
                        .hasRole("SUPER_ADMIN")
                        .requestMatchers("/users.html", "/api/users", "/api/users/**")
                        .hasRole("SUPER_ADMIN")
                        .requestMatchers("/rubric.html", "/api/rubrics", "/api/rubrics/**")
                        .hasAnyRole("EXAM_CONTROLLER", "SUPER_ADMIN")
                        .requestMatchers("/transcription.html", "/api/transcriptions", "/api/transcriptions/**")
                        .hasRole("SUPER_ADMIN")
                        .requestMatchers("/api/marking/**").hasRole("SUPER_ADMIN")
                        .requestMatchers("/change-password.html", "/api/auth/change-password")
                        .authenticated()
                        .anyRequest().authenticated())
                .formLogin(form -> form
                        .successHandler((request, response, authentication) -> {
                            userAccountService.recordSuccessfulLogin(authentication.getName());
                            UserAccountService.AccountState account =
                                    userAccountService.accountState(authentication.getName());
                            if (account != null && account.mustChangePassword()) {
                                response.sendRedirect("/change-password.html");
                                return;
                            }
                            boolean superAdmin = authentication.getAuthorities().stream()
                                    .anyMatch(authority -> authority.getAuthority().equals("ROLE_SUPER_ADMIN"));
                            boolean examController = authentication.getAuthorities().stream()
                                    .anyMatch(authority -> authority.getAuthority().equals("ROLE_EXAM_CONTROLLER"));
                            String destination = superAdmin ? "/settings.html"
                                    : examController ? "/question-paper.html" : "/upload.html";
                            response.sendRedirect(destination);
                        })
                        .permitAll())
                .addFilterAfter(new AccountStateFilter(userAccountService), SecurityContextHolderFilter.class)
                .build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
