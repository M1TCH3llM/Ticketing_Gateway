package com.ticketing.ticket.repo;

import com.ticketing.ticket.domain.TicketHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TicketHistoryRepository extends JpaRepository<TicketHistory, Long> {
    List<TicketHistory> findByTicketIdOrderByChangedAtAsc(Long ticketId);
}
