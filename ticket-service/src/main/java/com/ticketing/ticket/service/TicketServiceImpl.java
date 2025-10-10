package com.ticketing.ticket.service;

import com.ticketing.ticket.domain.Ticket;
import com.ticketing.ticket.domain.TicketHistory;
import com.ticketing.ticket.domain.TicketStatus;
import com.ticketing.ticket.messaging.TicketEvents;
import com.ticketing.ticket.repo.TicketHistoryRepository;
import com.ticketing.ticket.repo.TicketRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
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
        return Comparator
                .comparing(Ticket::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())).reversed()
                .thenComparing(Ticket::getId, Comparator.nullsLast(Comparator.naturalOrder())).reversed();
    }

    // -----------------------------------------------------------------------
    // Queries
    // -----------------------------------------------------------------------

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

    @Transactional(readOnly = true)
    public List<TicketHistory> historyFor(Long ticketId) {
        return historyRepo.findByTicketIdOrderByChangedAtAsc(ticketId);
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

    // -----------------------------------------------------------------------
    // Commands
    // -----------------------------------------------------------------------

    @Override
    public Ticket create(Ticket incoming) {
        if (incoming.getTitle() == null || incoming.getTitle().isBlank()) {
            throw new IllegalArgumentException("title is required");
        }
        if (incoming.getStatus() == null) incoming.setStatus(TicketStatus.SUBMITTED);

        var saved = repo.save(incoming);

        // history
        historyRepo.save(TicketHistory.builder()
                .ticketId(saved.getId())
                .status(saved.getStatus())
                .changedBy(saved.getOpenedBy())
                .note("Created")
                .build());

        // JMS: ticket.created
        events.ticketCreated(commonPayload(saved));

        return saved;
    }

    @Override
    public Optional<Ticket> updateStatus(Long id, TicketStatus status, String changedBy, String note) {
        return repo.findById(id).map(t -> {
            // Apply transition (+touch)
            if (status == TicketStatus.RESOLVED) {
                t.markResolved();
            } else if (status == TicketStatus.CLOSED) {
                t.markClosed();
            } else if (status == TicketStatus.REOPENED) {
                t.markReopened();
            } else {
                t.setStatus(status);
                t.touch();
            }

            var updated = repo.save(t);

            // History
            historyRepo.save(TicketHistory.builder()
                    .ticketId(updated.getId())
                    .status(updated.getStatus())
                    .changedBy(actorFor(updated, changedBy))
                    .note((note != null && !note.isBlank()) ? note : ("Status changed to " + status))
                    .build());

            // Messaging: on RESOLVED -> email + delayed autoclose
            if (status == TicketStatus.RESOLVED) {
                Instant resolvedAt = (updated.getResolvedAt() != null) ? updated.getResolvedAt() : Instant.now();

                events.resolved(
                        updated.getId(),
                        updated.getTitle(),
                        updated.getOpenedBy(),
                        (note == null || note.isBlank()) ? null : note, // resolutionDetails
                        null, // pdfPath (optional later)
                        null, // pdfName
                        actorFor(updated, changedBy),
                        resolvedAt
                );

                // Schedule the delayed command to close after 5 days if still not CLOSED
                events.scheduleAutoCloseAfterResolved(
                        updated.getId(),
                        updated.getTitle(),
                        updated.getOpenedBy(),
                        resolvedAt
                );
            }

            return updated;
        });
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
            t.touch();
            var updated = repo.save(t);

            historyRepo.save(TicketHistory.builder()
                    .ticketId(updated.getId())
                    .status(updated.getStatus())
                    .changedBy(nonBlankOr(manager, "system"))
                    .note((note == null || note.isBlank()) ? "Approved" : note)
                    .build());

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
            t.touch();
            var updated = repo.save(t);

            String reason = (note == null || note.isBlank()) ? "Rejected" : note;

            historyRepo.save(TicketHistory.builder()
                    .ticketId(updated.getId())
                    .status(updated.getStatus())
                    .changedBy(nonBlankOr(manager, "system"))
                    .note(reason)
                    .build());

            // JMS: notify requester with reason
            events.rejected(
                    updated.getId(),
                    updated.getTitle(),
                    updated.getOpenedBy(),
                    reason,
                    nonBlankOr(manager, "system")
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
            t.markClosed();
            var updated = repo.save(t);

            historyRepo.save(TicketHistory.builder()
                    .ticketId(updated.getId())
                    .status(updated.getStatus())
                    .changedBy(nonBlankOr(by, "system"))
                    .note((note == null || note.isBlank()) ? "Closed" : note)
                    .build());

            return updated;
        });
    }

    @Override
    public Optional<Ticket> reopen(Long id, String by, String note) {
        return repo.findById(id).map(t -> {
            if (t.getStatus() != TicketStatus.RESOLVED && t.getStatus() != TicketStatus.CLOSED) {
                throw new IllegalStateException("Only RESOLVED or CLOSED tickets can be reopened");
            }
            t.markReopened();
            var updated = repo.save(t);

            historyRepo.save(TicketHistory.builder()
                    .ticketId(updated.getId())
                    .status(updated.getStatus())
                    .changedBy(nonBlankOr(by, "system"))
                    .note((note == null || note.isBlank()) ? "Reopened" : note)
                    .build());

            return updated;
        });
    }

    // -----------------------------------------------------------------------
    // Patch fields (title/description/assignedTo)
    // -----------------------------------------------------------------------

    @Override
    public Optional<Ticket> updateFields(Long id, Ticket patch, String changedBy, String note) {
        return repo.findById(id).map(t -> {
            List<String> changed = new ArrayList<>();
            String prevAssigned = t.getAssignedTo();

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

            if (!changed.isEmpty()) t.touch();

            var updated = repo.save(t);

            String historyNote = (note != null && !note.isBlank())
                    ? note
                    : (changed.isEmpty() ? "Edited" : "Edited: " + String.join(", ", changed));

            historyRepo.save(TicketHistory.builder()
                    .ticketId(updated.getId())
                    .status(updated.getStatus())
                    .changedBy(actorFor(updated, changedBy))
                    .note(historyNote)
                    .build());

            // If assignment changed, publish assignment email event once (after save)
            if (!Objects.equals(prevAssigned, updated.getAssignedTo()) && updated.getAssignedTo() != null) {
                events.assignment(
                        updated.getId(),
                        updated.getTitle(),
                        updated.getOpenedBy(),
                        updated.getAssignedTo(),
                        actorFor(updated, changedBy)
                );
            }

            return updated;
        });
    }

    // -----------------------------------------------------------------------
    // Auto-close stale (7 days untouched) — called by your scheduler
    // -----------------------------------------------------------------------

    @Override
    public void autoCloseStale(Ticket t) {
        if (t.getStatus() == TicketStatus.CLOSED) return; // already closed

        t.markClosed();
        var closed = repo.save(t);

        historyRepo.save(TicketHistory.builder()
                .ticketId(closed.getId())
                .status(closed.getStatus())
                .changedBy("system")
                .note("Auto-closed: untouched > 7 days")
                .build());

        // Notify requester
        Instant lastTouched = (closed.getLastTouchedAt() != null) ? closed.getLastTouchedAt() : closed.getUpdatedAt();
        events.autoCloseStale(
                closed.getId(),
                closed.getTitle(),
                closed.getOpenedBy(),
                Instant.now(),
                lastTouched
        );
    }

    // -----------------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------------

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

    private static String nonBlankOr(String s, String def) {
        return (s != null && !s.isBlank()) ? s : def;
    }

    private static String actorFor(Ticket t, String changedBy) {
        if (changedBy != null && !changedBy.isBlank()) return changedBy;
        if (t.getAssignedTo() != null && !t.getAssignedTo().isBlank()) return t.getAssignedTo();
        return "system";
    }
}

