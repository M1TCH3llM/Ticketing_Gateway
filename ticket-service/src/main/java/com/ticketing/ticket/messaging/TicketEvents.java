package com.ticketing.ticket.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jms.JmsException;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Thin wrapper around JmsTemplate with:
 *  - explicit channels for assignment, rejection, resolution, and auto-close variants
 *  - safe send with logging (won’t break ticket flow if broker is down)
 *  - delayed send helper for “RESOLVED > 5 days” auto-close
 *
 * NOTE on payloads:
 * We use plain Map payloads so Spring will create a MapMessage. Stick to primitives/Strings.
 * Use ISO-8601 strings for timestamps (Instant.toString()).
 */
@Component
public class TicketEvents {
    private static final Logger log = LoggerFactory.getLogger(TicketEvents.class);
    private final JmsTemplate jms;

    // Destinations (queues or topics depending on your broker config)
    public static final String TICKET_CREATED               = "ticket.created";
    public static final String TICKET_ASSIGNED              = "ticket.assigned";
    public static final String TICKET_REJECTED              = "ticket.rejected";
    public static final String TICKET_RESOLVED              = "ticket.resolved";
    public static final String TICKET_AUTOCLOSE_STALE       = "ticket.autoclose.stale";         // untouched > 7 days (cron)
    public static final String TICKET_AUTOCLOSE_AFTER_RESOLVED = "ticket.autoclose.afterResolved"; // resolved > 5 days (delayed JMS)

    public TicketEvents(JmsTemplate jms) {
        this.jms = jms;
    }

    // ---- existing simple sends (kept for compatibility) ----
    public void ticketCreated(Map<String, Object> payload) { sendSafely(TICKET_CREATED, payload); }
    public void ticketResolved(Map<String, Object> payload) { sendSafely(TICKET_RESOLVED, payload); }

    /** Generic autoclose (legacy). Prefer the specific methods below. */
    public void requestAutoclose(Map<String, Object> payload) {
    	sendSafely("ticket.autoclose", payload);
    }

    // ---- New, explicit helpers for your five messaging cases ----

    /** Email to IT/Admin for assignment. */
    public void assignment(Long ticketId, String title, String openedBy, String assignedTo, String actor) {
        Map<String, Object> p = base(ticketId, title, openedBy);
        p.put("type", "ASSIGNED");
        p.put("assignedTo", nz(assignedTo));
        p.put("actor", nz(actor));
        p.put("at", Instant.now().toString());
        sendSafely(TICKET_ASSIGNED, p);
    }

    /** Email to user with rejection reason. */
    public void rejected(Long ticketId, String title, String openedBy, String reason, String actor) {
        Map<String, Object> p = base(ticketId, title, openedBy);
        p.put("type", "REJECTED_WITH_REASON");
        p.put("reason", nz(reason));
        p.put("actor", nz(actor));
        p.put("at", Instant.now().toString());
        sendSafely(TICKET_REJECTED, p);
    }

    /** Email to user with resolution details and (optionally) an attached PDF. */
    public void resolved(Long ticketId, String title, String openedBy,
                         String resolutionDetails, String pdfPath, String pdfName,
                         String actor, Instant resolvedAt) {
        Map<String, Object> p = base(ticketId, title, openedBy);
        p.put("type", "RESOLVED");
        if (resolutionDetails != null) p.put("resolutionDetails", resolutionDetails);
        if (pdfPath != null)          p.put("pdfPath", pdfPath);
        if (pdfName != null)          p.put("pdfName", pdfName);
        p.put("actor", nz(actor));
        p.put("resolvedAt", (resolvedAt != null ? resolvedAt : Instant.now()).toString());
        sendSafely(TICKET_RESOLVED, p);
    }

    /**
     * CRON path: ticket untouched for > 7 days → close now and notify.
     * Call this after your cron changes status to CLOSED.
     */
    public void autoCloseStale(Long ticketId, String title, String openedBy, Instant autoClosedAt, Instant lastTouchedAt) {
        Map<String, Object> p = base(ticketId, title, openedBy);
        p.put("autoClosedAt", (autoClosedAt != null ? autoClosedAt : Instant.now()).toString());
        if (lastTouchedAt != null) p.put("lastTouchedAt", lastTouchedAt.toString());
        p.put("reason", "UNTOUCHED_7_DAYS");
        sendSafely(TICKET_AUTOCLOSE_STALE, p);
    }

    /**
     * JMS-delayed path: when marking RESOLVED, schedule a message for 5 days later.
     * Consumer will auto-close if still not CLOSED, then notify.
     *
     * If resolvedAt is in the past, we shorten the delay accordingly (never below zero).
     */
    public void scheduleAutoCloseAfterResolved(Long ticketId, String title, String openedBy, Instant resolvedAt) {
        Instant resAt = (resolvedAt != null ? resolvedAt : Instant.now());
        long delayMs = Math.max(0L, Duration.between(Instant.now(), resAt.plus(Duration.ofDays(5))).toMillis());

        Map<String, Object> p = base(ticketId, title, openedBy);
        p.put("resolvedAt", resAt.toString());
        p.put("reason", "RESOLVED_5_DAYS_NOT_CLOSED");

        sendSafelyWithDelay("ticket.autoclose", p, delayMs);
    }

    // ---- internals ----

    private Map<String, Object> base(Long ticketId, String title, String openedBy) {
        Map<String, Object> p = new HashMap<>();
        p.put("ticketId", ticketId);
        if (title != null)    p.put("title", title);
        if (openedBy != null) p.put("openedBy", openedBy);
        return p;
    }

    private String nz(String s) { return s == null ? "" : s; }

    public void sendSafely(String destination, Map<String, Object> payload) {
        try {
            jms.convertAndSend(destination, payload);
            log.info("JMS sent to {} payload={}", destination, payload);
        } catch (JmsException e) {
            log.warn("JMS send failed to {}: {} (non-blocking)", destination, e.getMessage());
        }
    }

    /**
     * Send with a per-message delivery delay (JMS 2.0). We temporarily set the template’s delay.
     * Works with brokers that honor JMSDeliveryDelay (e.g., ActiveMQ Artemis/5.x with proper config).
     */
    private void sendSafelyWithDelay(String destination, Map<String, Object> payload, long delayMs) {
        try {
            jms.convertAndSend(destination, payload, msg -> {
                // ActiveMQ Classic scheduled delivery:
                // requires schedulerSupport=true on the broker
                msg.setLongProperty("AMQ_SCHEDULED_DELAY", Math.max(0L, delayMs));
                return msg;
            });
            log.info("JMS (AMQ_SCHEDULED_DELAY {} ms) sent to {} payload={}", delayMs, destination, payload);
        } catch (JmsException e) {
            log.warn("JMS delayed send failed to {}: {} (non-blocking)", destination, e.getMessage());
            // as a safety, try immediate send so the flow isn’t lost
            sendSafely(destination, payload);
        }
    }
}
