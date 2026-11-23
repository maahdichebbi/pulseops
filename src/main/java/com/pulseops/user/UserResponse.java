package com.pulseops.user;

import java.time.Instant;

public record UserResponse(
        Long id,
        String email,
        Instant createdAt
) {}
