package com.gm222.courseregistrationsystem.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "outbox_event", uniqueConstraints = {
        @UniqueConstraint(name = "uk_outbox_invoice_type", columnNames = {"invoice_id", "event_type"})
}, indexes = {
        @Index(name = "idx_outbox_due", columnList = "state,next_attempt_at"),
        @Index(name = "idx_outbox_lease", columnList = "state,locked_until")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@ToString(onlyExplicitlyIncluded = true)
public class OutboxEvent extends AuditedEntity {
    // Assigned before persistence so the frozen payload can contain the same event ID.
    @Id
    @ToString.Include
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "event_id", length = 36, nullable = false, updatable = false, columnDefinition = "CHAR(36)")
    private String eventId = UUID.randomUUID().toString();

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "invoice_id", nullable = false, updatable = false)
    private BillingInvoice invoice;

    @Column(name = "event_type", length = 32, nullable = false, updatable = false)
    private String eventType = "INVOICE_FINALIZED";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, updatable = false, columnDefinition = "JSON")
    private Map<String, Object> payload;

    @Column(length = 16, nullable = false)
    private String state = "PENDING";

    @Column(nullable = false)
    private Integer attempts = 0;

    @Column(name = "next_attempt_at", nullable = false, columnDefinition = "DATETIME(3)")
    private LocalDateTime nextAttemptAt;

    @Column(name = "locked_until", columnDefinition = "DATETIME(3)")
    private LocalDateTime lockedUntil;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "claim_token", length = 36, columnDefinition = "CHAR(36)")
    private String claimToken;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "delivered_at", columnDefinition = "DATETIME(3)")
    private LocalDateTime deliveredAt;

    @PrePersist
    void initializeNextAttemptAt() {
        if (nextAttemptAt == null) {
            nextAttemptAt = getCreatedAt();
        }
    }
}
