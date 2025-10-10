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

@Slf4j
@Component
@RequiredArgsConstructor
public class AutocloseListener {

    private final TicketService ticketService;
    private final TicketEvents events;

    @JmsListener(destination = "ticket.autoclose")
    public void onAutoclose(Map<String, Object> payload) {
        try {
            Long id = firstLong(payload, "ticketId", "id"); // accept either key
            String reason = str(payload.get("reason"));
            if (id == null) {
                log.warn("ticket.autoclose missing id; payload={}", payload);
                return;
            }

            ticketService.findById(id).ifPresentOrElse(t -> {
                if (t.getStatus() == TicketStatus.CLOSED) {
                    log.info("AutoClose: ticket {} already CLOSED; notifying only.", id);
                    publishAfterResolved(t, "(already closed)");
                    return;
                }
                try {
                    ticketService.close(id, "system",
                            (reason == null || reason.isBlank()) ? "Auto-closed"
                                                                 : "Auto-closed: " + reason);
                    // reload to capture updatedAt after close
                    ticketService.findById(id).ifPresent(closed -> publishAfterResolved(closed, reason));
                    log.info("AutoClose: ticket {} closed by listener", id);
                } catch (Exception ex) {
                    log.warn("AutoClose: failed to close ticket {}: {}", id, ex.getMessage());
                }
            }, () -> log.warn("AutoClose: ticket {} not found", id));

        } catch (Exception e) {
            log.error("Failed to process ticket.autoclose payload={}\n", payload, e);
        }
    }

    private void publishAfterResolved(Ticket t, String reason) {
        Map<String, Object> p = new HashMap<>();
        p.put("ticketId", t.getId());
        p.put("title", t.getTitle());
        p.put("openedBy", t.getOpenedBy());
        p.put("resolvedAt", t.getUpdatedAt() != null ? t.getUpdatedAt().toString() : null);
        p.put("autoClosedAt", Instant.now().toString());
        if (reason != null && !reason.isBlank()) p.put("reason", reason);
        events.sendSafely("ticket.autoclose.afterResolved", p);
    }

    /* -------- helpers -------- */
    private static Long firstLong(Map<String, Object> m, String... keys) {
        for (String k : keys) {
            Object v = m.get(k);
            if (v instanceof Number n) return n.longValue();
            if (v != null) {
                String s = String.valueOf(v).trim();
                if (!s.isEmpty() && !"null".equalsIgnoreCase(s)) {
                    try { return Long.parseLong(s); } catch (NumberFormatException ignore) {}
                }
            }
        }
        return null;
    }
    private static String str(Object o) { return o == null ? null : String.valueOf(o); }
}
