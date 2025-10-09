package com.ticketing.ticket.messaging;

import com.ticketing.ticket.service.TicketService;
import jakarta.jms.JMSException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class AutocloseListener {

    private static final Logger log = LoggerFactory.getLogger(AutocloseListener.class);
    private final TicketService tickets;

    public AutocloseListener(TicketService tickets) {
        this.tickets = tickets;
    }

    @JmsListener(destination = "ticket.autoclose")
    public void onAutoclose(Map<String, Object> payload) throws JMSException {
        try {
            long id = toLong(payload.get("id"));
            String reason = String.valueOf(payload.getOrDefault("reason", "Resolved>5d"));
            log.info("Received autoclose for ticket #{} (reason={})", id, reason);

            tickets.close(id, "system", "Auto-closed: " + reason)
                   .ifPresentOrElse(
                       t -> log.info("Ticket #{} closed by autoclose.", id),
                       () -> log.warn("Ticket #{} not found for autoclose.", id)
                   );

        } catch (Exception e) {
            log.error("Failed to process ticket.autoclose payload={}", payload, e);
        }
    }

    private long toLong(Object o) {
        if (o instanceof Number n) return n.longValue();
        return Long.parseLong(String.valueOf(o));
    }
}
