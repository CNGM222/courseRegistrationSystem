package com.gm222.courseregistrationsystem.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.NonNull;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Set;

@Entity
@Immutable
@Table(name = "billing_invoice", uniqueConstraints = {
        @UniqueConstraint(name = "uk_invoice_student_semester", columnNames = {"student_id", "semester_id"})
}, indexes = {
        @Index(name = "idx_invoice_semester", columnList = "semester_id"),
        @Index(name = "idx_invoice_close", columnList = "close_id")
})
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@ToString(onlyExplicitlyIncluded = true)
public class BillingInvoice {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @ToString.Include
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "schedule_id", nullable = false, unique = true)
    private Schedule schedule;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "student_id", nullable = false)
    private Student student;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "semester_id", nullable = false)
    private Semester semester;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "close_id", nullable = false)
    private RegistrationClose registrationClose;

    @Column(name = "total_amount", precision = 10, scale = 2, nullable = false)
    private BigDecimal totalAmount;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(length = 3, nullable = false, columnDefinition = "CHAR(3)")
    private String currency = "CNY";

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "DATETIME(3)")
    private LocalDateTime createdAt;

    @NonNull
    @OneToMany(mappedBy = "invoice")
    private Set<BillingInvoiceItem> billingInvoiceItems = new LinkedHashSet<>();

    @NonNull
    @OneToMany(mappedBy = "invoice")
    private Set<OutboxEvent> outboxEvents = new LinkedHashSet<>();
}
