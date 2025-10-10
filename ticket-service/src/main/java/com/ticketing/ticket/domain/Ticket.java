package com.ticketing.ticket.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "tickets")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class Ticket {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 180)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "attachment_name")
    private String attachmentName;

    @Column(name = "attachment_type")
    private String attachmentType;

    @Column(name = "attachment_path")
    private String attachmentPath;

    @Column(name = "attachment_size")
    private Long attachmentSize;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TicketStatus status = TicketStatus.SUBMITTED;

    // who opened / who is assigned (simple for now; can be FK to Employee later)
    @Column(length = 100)
    private String openedBy;

    @Column(length = 100)
    private String assignedTo;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    /** Updated whenever there is any activity on the ticket (status change, comment, assign, etc.). */
    @Column(nullable = false)
    private Instant lastTouchedAt;

    /** Set when status first becomes RESOLVED; cleared if REOPENED. Used for the 5-day auto-close. */
    private Instant resolvedAt;

    @PrePersist
    void onCreate() {
        var now = Instant.now();
        createdAt = now;
        updatedAt = now;
        lastTouchedAt = now;
        if (status == null) status = TicketStatus.SUBMITTED;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
        // Intentionally NOT auto-updating lastTouchedAt here.
        // Services should call touch() only when there is real activity.
    }

    /** Call this in service methods whenever there is user/system activity on the ticket. */
    public void touch() {
        this.lastTouchedAt = Instant.now();
    }

    /** Transition helpers (optional but make services cleaner). */
    public void markInProgress() {
        this.status = TicketStatus.IN_PROGRESS;
        touch();
    }

    public void markResolved() {
        this.status = TicketStatus.RESOLVED;
        this.resolvedAt = Instant.now();
        touch();
    }

    public void markClosed() {
        this.status = TicketStatus.CLOSED;
        touch();
    }

    public void markReopened() {
        this.status = TicketStatus.REOPENED;
        this.resolvedAt = null; // resolution no longer current
        touch();
    }
}

