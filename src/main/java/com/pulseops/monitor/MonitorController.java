package com.pulseops.monitor;

import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * REST controller for monitor CRUD.
 *
 * All endpoints are JWT-protected (enforced by SecurityConfig — anyRequest().authenticated()).
 * userId is extracted from the JWT principal set by JwtAuthenticationFilter; no DB lookup needed.
 *
 * Ownership is enforced in MonitorService.findOwned() — a monitor that doesn't belong
 * to the caller returns 404, never 403.
 */
@RestController
@RequestMapping("/api/v1/monitors")
public class MonitorController {

    private final MonitorService monitorService;

    public MonitorController(MonitorService monitorService) {
        this.monitorService = monitorService;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Extract the authenticated userId from the JWT principal (a Long, set by JwtAuthenticationFilter). */
    private Long userId(Authentication auth) {
        return (Long) auth.getPrincipal();
    }

    // ── Endpoints ─────────────────────────────────────────────────────────────

    /**
     * POST /api/v1/monitors
     * Create a new monitor. SSRF validation runs in the service.
     * Returns 201 Created with the monitor in the body.
     */
    @PostMapping
    public ResponseEntity<MonitorResponse> create(
            Authentication auth,
            @Valid @RequestBody CreateMonitorRequest req) {

        MonitorResponse created = monitorService.create(userId(auth), req);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /**
     * GET /api/v1/monitors
     * Paginated list of the caller's own monitors.
     * Default: page=0, size=20, sorted by createdAt DESC.
     */
    @GetMapping
    public Page<MonitorResponse> list(
            Authentication auth,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
            Pageable pageable) {

        return monitorService.list(userId(auth), pageable);
    }

    /**
     * GET /api/v1/monitors/{id}
     * Detail for a single monitor. Returns 404 if not found or not owned by caller.
     */
    @GetMapping("/{id}")
    public MonitorResponse getById(Authentication auth, @PathVariable Long id) {
        return monitorService.getById(id, userId(auth));
    }

    /**
     * PUT /api/v1/monitors/{id}
     * Full replacement of all config fields.
     * Re-validates SSRF in case the URL changed.
     */
    @PutMapping("/{id}")
    public MonitorResponse update(
            Authentication auth,
            @PathVariable Long id,
            @Valid @RequestBody CreateMonitorRequest req) {

        return monitorService.update(id, userId(auth), req);
    }

    /**
     * DELETE /api/v1/monitors/{id}
     * Hard delete — cascades check_results and incidents at DB level.
     * Returns 204 No Content.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(Authentication auth, @PathVariable Long id) {
        monitorService.delete(id, userId(auth));
        return ResponseEntity.noContent().build();
    }

    /**
     * POST /api/v1/monitors/{id}/pause
     * Stop scheduling checks. Open incidents remain open (spec §2.5).
     */
    @PostMapping("/{id}/pause")
    public MonitorResponse pause(Authentication auth, @PathVariable Long id) {
        return monitorService.pause(id, userId(auth));
    }

    /**
     * POST /api/v1/monitors/{id}/resume
     * Re-enable scheduling and immediately reset nextCheckAt to now()
     * so the scheduler picks up the monitor on its next tick.
     */
    @PostMapping("/{id}/resume")
    public MonitorResponse resume(Authentication auth, @PathVariable Long id) {
        return monitorService.resume(id, userId(auth));
    }
}
