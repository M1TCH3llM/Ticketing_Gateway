package com.ticketing.ticket.service;

import com.ticketing.ticket.domain.Ticket;
import com.ticketing.ticket.domain.TicketHistory;
import com.ticketing.ticket.domain.TicketStatus;
import com.ticketing.ticket.repo.TicketHistoryRepository;
import com.ticketing.ticket.repo.TicketRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@Transactional
public class TicketServiceImpl implements TicketService {

    private final TicketRepository repo;
    private final TicketHistoryRepository historyRepo;

    public TicketServiceImpl(TicketRepository repo, TicketHistoryRepository historyRepo) {
        this.repo = repo;
        this.historyRepo = historyRepo;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Ticket> findAll(TicketStatus status) {
        return (status != null) ? repo.findByStatus(status) : repo.findAll();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Ticket> findById(Long id) {
        return repo.findById(id);
    }

    @Override
    public Ticket create(Ticket incoming) {
        if (incoming.getTitle() == null || incoming.getTitle().isBlank()) {
            throw new IllegalArgumentException("title is required");
        }
        if (incoming.getStatus() == null) incoming.setStatus(TicketStatus.SUBMITTED);

        var saved = repo.save(incoming);

        // history: initial creation
        historyRepo.save(
            TicketHistory.builder()
                .ticketId(saved.getId())
                .status(saved.getStatus())
                .changedBy(saved.getOpenedBy())   
                .build()
        );

        return saved;
    }

//    @Override
//    public Optional<Ticket> updateStatus(Long id, TicketStatus status, String changedBy, String note) {
//        return repo.findById(id).map(t -> {
//            t.setStatus(status);
//            var updated = repo.save(t);
//
//            // history: status change
//            historyRepo.save(
//                TicketHistory.builder()
//                    .ticketId(updated.getId())
//                    .status(updated.getStatus())
//                    .changedBy(updated.getAssignedTo()) 
//                    .note("Status changed to " + status)
//                    .build()
//            );
//
//            return updated;
//        });
//    }
    
    @Override
    public Optional<Ticket> updateStatus(Long id, TicketStatus status, String changedBy, String note) {
        return repo.findById(id).map(t -> {
            t.setStatus(status);
            var updated = repo.save(t);

            historyRepo.save(
                TicketHistory.builder()
                    .ticketId(updated.getId())
                    .status(updated.getStatus())
                    .changedBy(
                        (changedBy != null && !changedBy.isBlank())
                            ? changedBy
                            : (updated.getAssignedTo() != null ? updated.getAssignedTo() : "system")
                    )
                    .note((note != null && !note.isBlank()) ? note : ("Status changed to " + status))
                    .build()
            );

            return updated;
        });
    }

    @Transactional(readOnly = true)
    public List<TicketHistory> historyFor(Long ticketId) {
        return historyRepo.findByTicketIdOrderByChangedAtAsc(ticketId);
    }
    
    @Override
    public Ticket save(Ticket t) {
        return repo.save(t);
    }
    
    @Override
    public Optional<Ticket> approve(Long id, String manager, String note) {
        return repo.findById(id).map(t -> {
            if (t.getStatus() != TicketStatus.SUBMITTED) {
                throw new IllegalStateException("Only SUBMITTED tickets can be approved");
            }
            t.setStatus(TicketStatus.APPROVED);
            var updated = repo.save(t);

            historyRepo.save(
                TicketHistory.builder()
                    .ticketId(updated.getId())
                    .status(updated.getStatus())
                    .changedBy(manager)                           // who approved
                    .note((note == null || note.isBlank()) ? "Approved" : note)
                    .build()
            );

            return updated;
        });
    }

    @Override
    public Optional<Ticket> reject(Long id, String manager, String note) {
        return repo.findById(id).map(t -> {
            if (t.getStatus() != TicketStatus.SUBMITTED) {
                throw new IllegalStateException("Only SUBMITTED tickets can be rejected");
            }
            t.setStatus(TicketStatus.REJECTED);
            var updated = repo.save(t);

            historyRepo.save(
                TicketHistory.builder()
                    .ticketId(updated.getId())
                    .status(updated.getStatus())
                    .changedBy(manager)                           // who rejected
                    .note((note == null || note.isBlank()) ? "Rejected" : note)
                    .build()
            );

            return updated;
        });
    }

    
    
}

