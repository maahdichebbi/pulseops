package com.pulseops.incident;

import com.pulseops.monitor.Monitor;
import com.pulseops.user.User;
import com.pulseops.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock private JavaMailSender mailSender;
    @Mock private NotificationLogRepository notificationLogRepository;
    @Mock private UserRepository userRepository;

    private NotificationService notificationService;

    @BeforeEach
    void setUp() {
        // Inject no-op sleeper so retry tests run instantaneously
        notificationService = new NotificationService(
                mailSender, notificationLogRepository, userRepository,
                "alerts@pulseops.local", millis -> {});
    }

    private Monitor buildMonitor() {
        Monitor m = new Monitor();
        m.setUserId(10L);
        m.setName("Prod API");
        m.setUrl("https://api.example.com/health");
        m.setFailureThreshold(3);
        return m;
    }

    private User buildUser() {
        User u = new User();
        u.setEmail("owner@example.com");
        u.setPasswordHash("hashed");
        return u;
    }

    @Test
    void notifyIncidentOpened_succeedsFirstAttempt_logsSent() {
        when(userRepository.findById(10L)).thenReturn(Optional.of(buildUser()));
        Incident incident = Incident.open(1L, 10L, 3, "HTTP 503");

        notificationService.notifyIncidentOpened(buildMonitor(), incident);

        verify(mailSender, times(1)).send(any(SimpleMailMessage.class));

        ArgumentCaptor<NotificationLog> captor = ArgumentCaptor.forClass(NotificationLog.class);
        verify(notificationLogRepository).save(captor.capture());
        NotificationLog log = captor.getValue();

        assertThat(log.getStatus()).isEqualTo(NotificationLog.DeliveryStatus.SENT);
        assertThat(log.getAttempts()).isEqualTo(1);
        assertThat(log.getEventType()).isEqualTo(NotificationLog.EventType.INCIDENT_OPENED);
        assertThat(log.getRecipientEmail()).isEqualTo("owner@example.com");
    }

    @Test
    void notifyIncidentOpened_failsTwiceThenSucceeds_logsSentWith3Attempts() {
        when(userRepository.findById(10L)).thenReturn(Optional.of(buildUser()));
        doThrow(new MailSendException("SMTP busy"))
                .doThrow(new MailSendException("SMTP busy"))
                .doNothing()
                .when(mailSender).send(any(SimpleMailMessage.class));

        Incident incident = Incident.open(1L, 10L, 3, "HTTP 503");

        notificationService.notifyIncidentOpened(buildMonitor(), incident);

        verify(mailSender, times(3)).send(any(SimpleMailMessage.class));

        ArgumentCaptor<NotificationLog> captor = ArgumentCaptor.forClass(NotificationLog.class);
        verify(notificationLogRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(NotificationLog.DeliveryStatus.SENT);
        assertThat(captor.getValue().getAttempts()).isEqualTo(3);
    }

    @Test
    void notifyIncidentOpened_failsAll3Attempts_logsFailedWithoutThrowing() {
        when(userRepository.findById(10L)).thenReturn(Optional.of(buildUser()));
        doThrow(new MailSendException("SMTP down"))
                .when(mailSender).send(any(SimpleMailMessage.class));

        Incident incident = Incident.open(1L, 10L, 3, "HTTP 503");

        // Must NOT throw
        notificationService.notifyIncidentOpened(buildMonitor(), incident);

        verify(mailSender, times(3)).send(any(SimpleMailMessage.class));

        ArgumentCaptor<NotificationLog> captor = ArgumentCaptor.forClass(NotificationLog.class);
        verify(notificationLogRepository).save(captor.capture());
        NotificationLog log = captor.getValue();

        assertThat(log.getStatus()).isEqualTo(NotificationLog.DeliveryStatus.FAILED);
        assertThat(log.getAttempts()).isEqualTo(3);
        assertThat(log.getErrorMessage()).contains("SMTP down");
    }
}
