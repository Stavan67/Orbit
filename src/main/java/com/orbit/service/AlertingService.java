package com.orbit.service;

import com.orbit.entity.ConjunctionEvent;
import com.orbit.entity.ConjunctionEvent.RiskLevel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
@ConditionalOnProperty(name = "alerting.email.enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class AlertingService {

    private final JavaMailSender mailSender;

    @Value("${alerting.email.recipients}")
    private List<String> recipients;

    @Value("${alerting.email.from:orbit-alerts@noreply.com}")
    private String fromAddress;

    @Value("${alerting.email.min-risk-level:HIGH}")
    private String minRiskLevelStr;

    @Value("${alerting.pc.threshold:1e-4}")
    private double pcAlertThreshold;

    private static final DateTimeFormatter DISPLAY_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'");

    public void evaluateAndAlert(ConjunctionEvent event) {
        if (!meetsAlertThreshold(event)) {
            return;
        }

        String subject = buildSubject(event);
        String body    = buildBody(event);

        sendToAllRecipients(subject, body);

        log.info("Alert sent for {} event: NORAD {} vs {} | TCA={}",
                event.getRiskLevel(),
                event.getPrimarySatellite().getNoradId(),
                event.getSecondarySatellite().getNoradId(),
                event.getTca());
    }

    public void evaluateAndAlertBatch(List<ConjunctionEvent> events, Integer primaryNoradId) {
        List<ConjunctionEvent> alertable = events.stream()
                .filter(this::meetsAlertThreshold)
                .toList();

        if (alertable.isEmpty()) {
            return;
        }

        long critical = alertable.stream().filter(e -> e.getRiskLevel() == RiskLevel.CRITICAL).count();
        long high     = alertable.stream().filter(e -> e.getRiskLevel() == RiskLevel.HIGH).count();

        String subject = String.format(
                "[ORBIT ALERT] %s — NORAD %d: %d CRITICAL, %d HIGH risk conjunctions found",
                critical > 0 ? "🔴 CRITICAL" : "🟠 HIGH", primaryNoradId, critical, high);

        StringBuilder body = new StringBuilder();
        body.append("Conjunction analysis has identified ").append(alertable.size())
                .append(" event(s) requiring your attention for NORAD ").append(primaryNoradId).append(".\n\n");

        body.append("SUMMARY\n");
        body.append("=======\n");
        body.append("  CRITICAL events : ").append(critical).append("\n");
        body.append("  HIGH events     : ").append(high).append("\n\n");

        body.append("EVENTS (sorted by TCA)\n");
        body.append("======================\n");

        alertable.stream()
                .sorted((a, b) -> a.getTca().compareTo(b.getTca()))
                .forEach(e -> {
                    body.append("\n[").append(e.getRiskLevel()).append("]\n");
                    appendEventDetails(body, e);
                });

        body.append("\n\nThis alert was generated automatically by the Orbit Conjunction Analysis System.\n");
        body.append("Log in to the API for full details: GET /api/conjunction/high-risk/").append(primaryNoradId);

        sendToAllRecipients(subject, body.toString());
        log.info("Batch alert sent for {} alertable events for NORAD {}", alertable.size(), primaryNoradId);
    }

    private boolean meetsAlertThreshold(ConjunctionEvent event) {
        RiskLevel level = event.getRiskLevel();
        RiskLevel minLevel = parseMinLevel();

        boolean riskMet = level == RiskLevel.CRITICAL
                || (minLevel == RiskLevel.HIGH && level == RiskLevel.HIGH);

        boolean pcMet = event.getProbabilityOfCollision() != null
                && event.getProbabilityOfCollision() >= pcAlertThreshold;

        return riskMet || pcMet;
    }

    private RiskLevel parseMinLevel() {
        try {
            return RiskLevel.valueOf(minRiskLevelStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("Invalid alerting.email.min-risk-level '{}', defaulting to HIGH", minRiskLevelStr);
            return RiskLevel.HIGH;
        }
    }

    private String buildSubject(ConjunctionEvent event) {
        String prefix = event.getRiskLevel() == RiskLevel.CRITICAL ? "🔴 CRITICAL" : "🟠 HIGH";
        return String.format(
                "[ORBIT ALERT] %s — NORAD %d vs %d | TCA %s",
                prefix,
                event.getPrimarySatellite().getNoradId(),
                event.getSecondarySatellite().getNoradId(),
                event.getTca().format(DISPLAY_FMT));
    }

    private String buildBody(ConjunctionEvent event) {
        StringBuilder sb = new StringBuilder();
        sb.append("CONJUNCTION ALERT\n");
        sb.append("=================\n\n");
        sb.append("Risk Level : ").append(event.getRiskLevel()).append("\n\n");
        appendEventDetails(sb, event);
        sb.append("\n\nThis alert was generated automatically by the Orbit Conjunction Analysis System.\n");
        return sb.toString();
    }

    private void appendEventDetails(StringBuilder sb, ConjunctionEvent event) {
        sb.append("  Primary   : ").append(event.getPrimarySatellite().getName())
                .append(" (NORAD ").append(event.getPrimarySatellite().getNoradId()).append(")\n");
        sb.append("  Secondary : ").append(event.getSecondarySatellite().getName())
                .append(" (NORAD ").append(event.getSecondarySatellite().getNoradId()).append(")\n");
        sb.append("  TCA       : ").append(event.getTca().format(DISPLAY_FMT)).append("\n");
        sb.append("  Miss Dist : ").append(String.format("%.1f m (%.3f km)",
                event.getMissDistance(), event.getMissDistance() / 1000.0)).append("\n");
        sb.append("  Rel. Vel. : ").append(String.format("%.1f m/s", event.getRelativeVelocity())).append("\n");

        if (event.getProbabilityOfCollision() != null) {
            sb.append("  Pc        : ").append(String.format("%.3e", event.getProbabilityOfCollision()));
            sb.append(Boolean.TRUE.equals(event.getCdmBased()) ? " [CDM — AUTHORITATIVE]" : " [TLE estimate]");
            sb.append("\n");
        }

        if (event.getPrimaryAltitude() != null) {
            sb.append("  Altitude  : primary=").append(String.format("%.1f km", event.getPrimaryAltitude()))
                    .append(" / secondary=").append(String.format("%.1f km", event.getSecondaryAltitude()))
                    .append("\n");
        }

        if (Boolean.TRUE.equals(event.getCdmBased())) {
            sb.append("  Source: Space-Track CDM (ID: ").append(event.getCdmId()).append(")\n");
        } else {
            sb.append("  Source: TLE-based propagation screening\n");
        }
    }

    private void sendToAllRecipients(String subject, String body) {
        if (recipients == null || recipients.isEmpty()) {
            log.warn("No email recipients configured — alert not sent. "
                    + "Set alerting.email.recipients in application.properties");
            return;
        }

        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(fromAddress);
            message.setTo(recipients.toArray(new String[0]));
            message.setSubject(subject);
            message.setText(body);
            mailSender.send(message);
        } catch (Exception e) {
            log.error("Failed to send alert email: {}", e.getMessage(), e);
        }
    }
}