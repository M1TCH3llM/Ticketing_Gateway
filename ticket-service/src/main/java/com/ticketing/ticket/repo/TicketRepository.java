package com.ticketing.ticket.repo;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.ticketing.ticket.domain.Ticket;
import com.ticketing.ticket.domain.TicketStatus;

public interface TicketRepository extends JpaRepository<Ticket, Long>{
	List<Ticket> findByStatus(TicketStatus status);

}
