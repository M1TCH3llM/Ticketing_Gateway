package com.ticketing.ticket.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jms.JmsException;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class TicketEvents {
    private static final Logger log = LoggerFactory.getLogger(TicketEvents.class);
    private final JmsTemplate jms;

    public TicketEvents(JmsTemplate jms) {
        this.jms = jms;
    }

    public void ticketCreated(Map<String, Object> payload) {
        sendSafely("ticket.created", payload);
    }

    public void ticketResolved(Map<String, Object> payload) {
        sendSafely("ticket.resolved", payload);
    }

    public void requestAutoclose(Map<String, Object> payload) {
        sendSafely("ticket.autoclose", payload);
    }

    private void sendSafely(String destination, Map<String, Object> payload) {
        try {
            jms.convertAndSend(destination, payload);
            log.info("JMS sent to {} payload={}", destination, payload);
        } catch (JmsException e) {
            log.warn("JMS send failed to {}: {} (will not block ticket flow)", destination, e.getMessage());
        }
    }
}
