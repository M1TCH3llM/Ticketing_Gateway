package com.ticketing.ticket.service;

import com.ticketing.ticket.domain.Ticket;
import com.ticketing.ticket.domain.TicketHistory;
import com.ticketing.ticket.domain.TicketStatus;

import java.util.List;
import java.util.Optional;

public interface TicketService {
    List<Ticket> findAll(TicketStatus status);
    Optional<Ticket> findById(Long id);
    Ticket create(Ticket incoming);
    Optional<Ticket> updateStatus(Long id, TicketStatus status, String changedBy, String note);
    List<TicketHistory> historyFor(Long ticketId);
    Ticket save(Ticket t);
    Optional<Ticket> approve(Long id, String manager, String note);
    Optional<Ticket> reject(Long id, String manager, String note);
}
