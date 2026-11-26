package com.answerscripts.user;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
public class UserManagementController {

    private final UserManagementService userManagementService;

    public UserManagementController(UserManagementService userManagementService) {
        this.userManagementService = userManagementService;
    }

    @GetMapping
    List<UserManagementService.UserView> listUsers() {
        return userManagementService.listUsers();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    UserManagementService.UserView createUser(
            Authentication authentication, @RequestBody CreateUserRequest request) {
        return userManagementService.createUser(
                authentication.getName(),
                request.username(),
                request.displayName(),
                request.role(),
                request.initialPassword());
    }

    @PostMapping("/enabled")
    ResponseEntity<Void> setEnabled(Authentication authentication, @RequestBody EnabledRequest request) {
        userManagementService.setEnabled(authentication.getName(), request.username(), request.enabled());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/reset-password")
    ResponseEntity<Void> resetPassword(
            Authentication authentication, @RequestBody ResetPasswordRequest request) {
        userManagementService.resetPassword(
                authentication.getName(), request.username(), request.temporaryPassword());
        return ResponseEntity.noContent().build();
    }

    public record CreateUserRequest(String username, String displayName, String role, String initialPassword) {}

    public record EnabledRequest(String username, boolean enabled) {}

    public record ResetPasswordRequest(String username, String temporaryPassword) {}
}
