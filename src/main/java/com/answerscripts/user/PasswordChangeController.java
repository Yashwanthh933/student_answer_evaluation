package com.answerscripts.user;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PasswordChangeController {

    private final UserManagementService userManagementService;

    public PasswordChangeController(UserManagementService userManagementService) {
        this.userManagementService = userManagementService;
    }

    @PostMapping("/api/auth/change-password")
    ResponseEntity<Void> changePassword(
            Authentication authentication, @RequestBody ChangePasswordRequest request) {
        userManagementService.changePassword(
                authentication.getName(), request.currentPassword(), request.newPassword());
        return ResponseEntity.noContent().build();
    }

    public record ChangePasswordRequest(String currentPassword, String newPassword) {}
}
