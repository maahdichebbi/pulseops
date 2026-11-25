package com.pulseops.incident;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for incident history endpoints.
 * All routes are JWT-protected by SecurityConfig.
 */
@RestController
public class IncidentController {

    private final IncidentService incidentService;

    public IncidentController(IncidentService incidentService) {
        this.incidentService = incidentService;
    }

    private Long userId(Authentication auth) {
        return (Long) auth.getPrincipal();
    }

    /**
     * GET /api/v1/incidents
     * Paginated feed of all incidents across the caller's monitors (newest first).
     */
    @GetMapping("/api/v1/incidents")
    public Page<IncidentResponse> listAll(
            Authentication auth,
            @PageableDefault(size = 20) Pageable pageable) {

        return incidentService.listForUser(userId(auth), pageable);
    }

    /**
     * GET /api/v1/monitors/{id}/incidents
     * Paginated incident history for a single owned monitor (404 if not owned).
     */
    @GetMapping("/api/v1/monitors/{id}/incidents")
    public Page<IncidentResponse> listForMonitor(
            Authentication auth,
            @PathVariable Long id,
            @PageableDefault(size = 20) Pageable pageable) {

        return incidentService.listForMonitor(id, userId(auth), pageable);
    }
}
