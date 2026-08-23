package com.finguard.copilot.coreclient.port;

import java.util.Set;

public record CurrentUser(String username, Set<String> roles) {

    public CurrentUser {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("username must not be blank");
        }
        if (roles == null || roles.isEmpty()
                || roles.stream().anyMatch(role -> role == null || role.isBlank())) {
            throw new IllegalArgumentException("roles must contain non-blank values");
        }
        roles = Set.copyOf(roles);
    }
}
