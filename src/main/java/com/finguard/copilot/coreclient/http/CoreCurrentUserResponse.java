package com.finguard.copilot.coreclient.http;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.finguard.copilot.coreclient.port.CurrentUser;

record CoreCurrentUserResponse(String username, List<String> roles) {

    private static final Set<String> CORE_ROLE_CODES = Set.of("ADMIN", "REVIEWER");

    CurrentUser toPort() {
        Set<String> mappedRoles = new LinkedHashSet<>(roles);
        if (!CORE_ROLE_CODES.containsAll(mappedRoles)) {
            throw new IllegalArgumentException("roles must use Core role codes");
        }
        return new CurrentUser(username, mappedRoles);
    }
}
