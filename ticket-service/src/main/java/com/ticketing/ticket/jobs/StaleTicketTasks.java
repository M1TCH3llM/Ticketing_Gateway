package com.ticketing.ticket.jobs;

import com.ticketing.ticket.domain.Ticket;
import com.ticketing.ticket.domain.TicketStatus;
import com.ticketing.ticket.service.TicketService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;


 // Nightly maintenance:
 // Finds tickets not CLOSED and untouched for >= 7 days (based on lastTouchedAt)
 // Closes them and emits "ticket.autoclose.stale" via TicketService -> TicketEvents
 
 // NOTE: This class also enables scheduling for the app.
 
@Slf4j
@Configuration
@EnableScheduling
@RequiredArgsConstructor
public class StaleTicketTasks {

    @PersistenceContext
    private final EntityManager em;

    private final TicketService ticketService;

    /**
     * Run every day at 02:30 server time.
     * CRON format: second minute hour day of month . day . day of week
     */
    @Transactional
    @Scheduled(cron = "0 30 2 * * *")
    public void closeUntouchedTickets() {
        Instant cutoff = Instant.now().minus(7, ChronoUnit.DAYS);

        // JPQL to avoid adding a new repository method (single-file change)
        List<Ticket> stale = em.createQuery(
                "select t from Ticket t " +
                "where t.status <> :closed and t.lastTouchedAt <= :cutoff", Ticket.class)
            .setParameter("closed", TicketStatus.CLOSED)
            .setParameter("cutoff", cutoff)
            .getResultList();

        if (stale.isEmpty()) {
            log.debug("StaleTicketTasks: no tickets to auto-close (cutoff={})", cutoff);
            return;
        }

        log.info("StaleTicketTasks: auto-closing {} ticket(s) untouched since <= {}", stale.size(), cutoff);

        // Delegate close + history + JMS to service (which already publishes autoCloseStale)
        for (Ticket t : stale) {
            try {
                ticketService.autoCloseStale(t); // marks CLOSED, saves, and emits event
            } catch (Exception e) {
                log.warn("StaleTicketTasks: failed to auto-close ticket id={}: {}", t.getId(), e.getMessage());
            }
        }
    }
}
