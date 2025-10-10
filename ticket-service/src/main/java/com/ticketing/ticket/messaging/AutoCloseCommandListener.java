package com.ticketing.ticket.messaging;

import com.ticketing.ticket.domain.Ticket;
import com.ticketing.ticket.domain.TicketStatus;
import com.ticketing.ticket.service.TicketService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Listens for a command style message to auto close a ticket.
 * Expected payload: { "id": <Long>, "reason": "<String optional>" }
 *
 * Flow:
 *  close the ticket via TicketService (writes history)
 *  emit "ticket.autoclose.afterResolved" so notificationservice emails the requester
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AutoCloseCommandListener {

    private final TicketService ticketService;
    private final TicketEvents events;

    /**
     * Backward/interop command queue (your NotificationService used to send to this):
     *   destination: "ticket.autoclose"
     */
    @JmsListener(destination = "ticket.autoclose")
    public void onAutoCloseCommand(Map<String, Object> cmd) {
        Long id = asLong(cmd.get("id"));
        String reason = str(cmd.get("reason"));
        if (id == null) {
            log.warn("ticket.autoclose received without id: {}", cmd);
            return;
        }

        ticketService.findById(id).ifPresentOrElse(t -> {
            if (t.getStatus() == TicketStatus.CLOSED) {
                log.info("AutoClose: ticket {} already CLOSED; skipping", id);
                // still notify requester so they get an email, but mark that it was already closed
                publishAfterResolved(t, "(already closed)");
                return;
            }

            try {
                ticketService.close(id, "system", reason == null ? "Auto-closed" : ("Auto-closed: " + reason));
                // reload to capture updatedAt
                ticketService.findById(id).ifPresent(closed -> publishAfterResolved(closed, reason));
                log.info("AutoClose: ticket {} closed", id);
            } catch (Exception ex) {
                log.warn("AutoClose: failed to close ticket {}: {}", id, ex.getMessage());
            }
        }, () -> log.warn("AutoClose: ticket {} not found", id));
    }

    /* ========= helpers ========= */

    private void publishAfterResolved(Ticket t, String reason) {
        Map<String, Object> p = new HashMap<>();
        p.put("ticketId", t.getId());
        p.put("title", t.getTitle());
        p.put("openedBy", t.getOpenedBy());
        // We don’t rely on a dedicated resolvedAt column; use updatedAt as a safe fallback.
        p.put("resolvedAt", t.getUpdatedAt() != null ? t.getUpdatedAt().toString() : null);
        p.put("autoClosedAt", Instant.now().toString());
        if (reason != null && !reason.isBlank()) p.put("reason", reason);

        // Notification service listens to this to send the “auto closed after resolution” email
        events.sendSafely("ticket.autoclose.afterResolved", p);
    }

    private static Long asLong(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.longValue();
        try { return Long.parseLong(String.valueOf(o)); } catch (Exception ex) { return null; }
    }
    private static String str(Object o) { return o == null ? null : String.valueOf(o); }
}
