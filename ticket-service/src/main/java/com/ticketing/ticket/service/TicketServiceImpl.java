package com.ticketing.ticket.service;

import com.ticketing.ticket.domain.Ticket;
import com.ticketing.ticket.domain.TicketHistory;
import com.ticketing.ticket.domain.TicketStatus;
import com.ticketing.ticket.messaging.TicketEvents;
import com.ticketing.ticket.repo.TicketHistoryRepository;
import com.ticketing.ticket.repo.TicketRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
@Transactional
public class TicketServiceImpl implements TicketService {

    private final TicketRepository repo;
    private final TicketHistoryRepository historyRepo;
    private final TicketEvents events;

    public TicketServiceImpl(TicketRepository repo,
                             TicketHistoryRepository historyRepo,
                             TicketEvents events) {
        this.repo = repo;
        this.historyRepo = historyRepo;
        this.events = events;
    }

    private static Comparator<Ticket> defaultSort() {
        // Prefer createdAt desc if present, otherwise id desc
        return Comparator
            .comparing(Ticket::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())).reversed()
            .thenComparing(Ticket::getId, Comparator.nullsLast(Comparator.naturalOrder())).reversed();
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
                .note("Created")
                .build()
        );

        // JMS: ticket.created
        events.ticketCreated(commonPayload(saved));

        return saved;
    }

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

            // JMS: ticket.resolved when entering RESOLVED
            if (status == TicketStatus.RESOLVED) {
                Map<String, Object> payload = commonPayload(updated);
                payload.put("resolvedAt", updated.getUpdatedAt() != null ? updated.getUpdatedAt().toString() : null);
                if (note != null && !note.isBlank()) payload.put("note", note);
                events.ticketResolved(payload);
            }

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
                    .changedBy(manager)
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
                    .changedBy(manager)
                    .note((note == null || note.isBlank()) ? "Rejected" : note)
                    .build()
            );

            return updated;
        });
    }

    @Override
    public Optional<Ticket> close(Long id, String by, String note) {
        return repo.findById(id).map(t -> {
            if (t.getStatus() == TicketStatus.CLOSED) {
                throw new IllegalStateException("Ticket already CLOSED");
            }
            t.setStatus(TicketStatus.CLOSED);
            var updated = repo.save(t);
            historyRepo.save(
                TicketHistory.builder()
                    .ticketId(updated.getId())
                    .status(updated.getStatus())
                    .changedBy(by)
                    .note((note == null || note.isBlank()) ? "Closed" : note)
                    .build()
            );
            return updated;
        });
    }

    @Override
    public Optional<Ticket> reopen(Long id, String by, String note) {
        return repo.findById(id).map(t -> {
            if (t.getStatus() != TicketStatus.RESOLVED && t.getStatus() != TicketStatus.CLOSED) {
                throw new IllegalStateException("Only RESOLVED or CLOSED tickets can be reopened");
            }
            t.setStatus(TicketStatus.REOPENED);
            var updated = repo.save(t);
            historyRepo.save(
                TicketHistory.builder()
                    .ticketId(updated.getId())
                    .status(updated.getStatus())
                    .changedBy(by)
                    .note((note == null || note.isBlank()) ? "Reopened" : note)
                    .build()
            );
            return updated;
        });
    }

    @Transactional(readOnly = true)
    @Override
    public List<Ticket> findActive() {
        var list = repo.findByStatusNot(TicketStatus.CLOSED);
        list.sort(defaultSort());
        return list;
    }

    @Transactional(readOnly = true)
    @Override
    public List<Ticket> findHistory() {
        var list = repo.findByStatus(TicketStatus.CLOSED);
        list.sort(defaultSort());
        return list;
    }

    // ---- Simple field patch (title/description/assignedTo) with history ----
    @Override
    public Optional<Ticket> updateFields(Long id, Ticket patch, String changedBy, String note) {
        return repo.findById(id).map(t -> {
            List<String> changed = new ArrayList<>();

            if (patch.getTitle() != null && !Objects.equals(patch.getTitle(), t.getTitle())) {
                t.setTitle(patch.getTitle());
                changed.add("title");
            }
            if (patch.getDescription() != null && !Objects.equals(patch.getDescription(), t.getDescription())) {
                t.setDescription(patch.getDescription());
                changed.add("description");
            }
            if (patch.getAssignedTo() != null && !Objects.equals(patch.getAssignedTo(), t.getAssignedTo())) {
                t.setAssignedTo(patch.getAssignedTo());
                changed.add("assignedTo");
            }

            var updated = repo.save(t);

            String historyNote = (note != null && !note.isBlank())
                    ? note
                    : (changed.isEmpty() ? "Edited" : "Edited: " + String.join(", ", changed));

            historyRepo.save(
                TicketHistory.builder()
                    .ticketId(updated.getId())
                    .status(updated.getStatus())
                    .changedBy(
                        (changedBy != null && !changedBy.isBlank())
                            ? changedBy
                            : (updated.getAssignedTo() != null ? updated.getAssignedTo() : "system")
                    )
                    .note(historyNote)
                    .build()
            );

            return updated;
        });
    }

    // ---- shared payload for JMS ----
    private Map<String, Object> commonPayload(Ticket t) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", t.getId());
        m.put("title", t.getTitle());
        m.put("status", t.getStatus() != null ? t.getStatus().name() : null);
        m.put("openedBy", t.getOpenedBy());
        m.put("assignedTo", t.getAssignedTo());
        m.put("createdAt", t.getCreatedAt() != null ? t.getCreatedAt().toString() : null);
        m.put("updatedAt", t.getUpdatedAt() != null ? t.getUpdatedAt().toString() : null);
        m.put("description", t.getDescription());
        return m;
    }
}

