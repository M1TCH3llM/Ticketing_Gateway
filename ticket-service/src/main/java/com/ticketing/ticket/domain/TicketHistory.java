package com.ticketing.ticket.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "ticket_history")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class TicketHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long ticketId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TicketStatus status;       

    @Column(length = 100)
    private String changedBy;           

    @Column(nullable = false, updatable = false)
    private Instant changedAt;

    @Column(length = 255)
    private String note;                 

    @PrePersist
    void onCreate() {
        if (changedAt == null) changedAt = Instant.now();
    }
}
