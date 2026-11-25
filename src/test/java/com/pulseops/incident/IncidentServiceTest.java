package com.pulseops.incident;

import com.pulseops.monitor.CheckResult;
import com.pulseops.monitor.Monitor;
import com.pulseops.monitor.MonitorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IncidentServiceTest {

    @Mock private IncidentRepository incidentRepository;
    @Mock private MonitorRepository monitorRepository;
    @Mock private NotificationService notificationService;

    private IncidentService incidentService;

    @BeforeEach
    void setUp() {
        incidentService = new IncidentService(incidentRepository, monitorRepository, notificationService);
    }

    private Monitor buildMonitor(int consecutiveFailures, int threshold) {
        Monitor m = new Monitor();
        m.setUserId(10L);
        m.setName("Prod API");
        m.setUrl("https://api.example.com/health");
        m.setFailureThreshold(threshold);
        m.setConsecutiveFailures(consecutiveFailures);
        return m;
    }

    @Test
    void handleCheckResult_failureBelowThreshold_doesNotOpenIncident() {
        Monitor monitor = buildMonitor(2, 3);
        CheckResult failure = CheckResult.failure(1L, 503, 120, "HTTP 503");

        incidentService.handleCheckResult(monitor, failure);

        verifyNoInteractions(incidentRepository, notificationService);
    }

    @Test
    void handleCheckResult_failureReachesThreshold_opensIncidentAndNotifies() {
        Monitor monitor = buildMonitor(3, 3);
        CheckResult failure = CheckResult.failure(1L, 503, 120, "HTTP 503");

        when(incidentRepository.findByMonitorIdAndStatus(any(), eq(Incident.Status.OPEN)))
                .thenReturn(Optional.empty());
        when(incidentRepository.save(any(Incident.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        incidentService.handleCheckResult(monitor, failure);

        ArgumentCaptor<Incident> captor = ArgumentCaptor.forClass(Incident.class);
        verify(incidentRepository).save(captor.capture());
        Incident saved = captor.getValue();

        assertThat(saved.getStatus()).isEqualTo(Incident.Status.OPEN);
        assertThat(saved.getFailureCountAtOpen()).isEqualTo(3);
        assertThat(saved.getLastError()).isEqualTo("HTTP 503");
        verify(notificationService).notifyIncidentOpened(monitor, saved);
    }

    @Test
    void handleCheckResult_failureWhenIncidentAlreadyOpen_doesNotDuplicate() {
        Monitor monitor = buildMonitor(5, 3);
        CheckResult failure = CheckResult.failure(1L, 503, 120, "HTTP 503");
        Incident existing = Incident.open(1L, 10L, 3, "HTTP 503");

        when(incidentRepository.findByMonitorIdAndStatus(any(), eq(Incident.Status.OPEN)))
                .thenReturn(Optional.of(existing));

        incidentService.handleCheckResult(monitor, failure);

        verify(incidentRepository, never()).save(any());
        verifyNoInteractions(notificationService);
    }

    @Test
    void handleCheckResult_successWithOpenIncident_resolvesAndNotifies() {
        Monitor monitor = buildMonitor(0, 3);
        CheckResult success = CheckResult.success(1L, 200, 45);
        Incident openIncident = Incident.open(1L, 10L, 3, "HTTP 503");

        when(incidentRepository.findByMonitorIdAndStatus(any(), eq(Incident.Status.OPEN)))
                .thenReturn(Optional.of(openIncident));
        when(incidentRepository.save(any(Incident.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        incidentService.handleCheckResult(monitor, success);

        assertThat(openIncident.getStatus()).isEqualTo(Incident.Status.RESOLVED);
        assertThat(openIncident.getResolvedAt()).isNotNull();
        verify(incidentRepository).save(openIncident);
        verify(notificationService).notifyIncidentResolved(monitor, openIncident);
    }

    @Test
    void handleCheckResult_successWithNoOpenIncident_isNoOp() {
        Monitor monitor = buildMonitor(0, 3);
        CheckResult success = CheckResult.success(1L, 200, 45);

        when(incidentRepository.findByMonitorIdAndStatus(any(), eq(Incident.Status.OPEN)))
                .thenReturn(Optional.empty());

        incidentService.handleCheckResult(monitor, success);

        verify(incidentRepository, never()).save(any());
        verifyNoInteractions(notificationService);
    }
}
