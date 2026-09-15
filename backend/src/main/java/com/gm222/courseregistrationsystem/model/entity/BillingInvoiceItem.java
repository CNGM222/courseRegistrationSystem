package com.gm222.courseregistrationsystem.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Entity
@Immutable
@Table(name = "billing_invoice_item", uniqueConstraints = {
        @UniqueConstraint(name = "uk_invoice_item_offering", columnNames = {"invoice_id", "offering_id"})
}, indexes = {
        @Index(name = "idx_invoice_item_offering", columnList = "offering_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@ToString(onlyExplicitlyIncluded = true)
public class BillingInvoiceItem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @ToString.Include
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "invoice_id", nullable = false)
    private BillingInvoice invoice;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "offering_id", nullable = false)
    private CourseOffering offering;

    @Column(name = "course_code", length = 64, nullable = false)
    private String courseCode;

    @Column(name = "course_name", length = 200, nullable = false)
    private String courseName;

    @Column(name = "section_code", length = 32, nullable = false)
    private String sectionCode;

    @Column(name = "professor_name", length = 100, nullable = false)
    private String professorName;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "meeting_snapshot", nullable = false, columnDefinition = "JSON")
    private List<Map<String, Object>> meetingSnapshot;

    @Column(precision = 10, scale = 2, nullable = false)
    private BigDecimal amount;
}
