package com.gm222.courseregistrationsystem.model.entity;

import ch.qos.logback.classic.joran.sanity.IfNestedWithinSecondPhaseElementSC;
import ch.qos.logback.core.LifeCycleManager;
import ch.qos.logback.core.util.StringUtil;
import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Entity
@Table(name = "semester")
@Setter
@Getter
@NoArgsConstructor
@AllArgsConstructor
@ToString
@EqualsAndHashCode
@EntityListeners(AuditingEntityListener.class)
public class Semester {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 32,unique = true)
    private String code;

    @Column(length = 100)
    private String name;

    @Column(name = "start_date")
    private LocalDateTime startDate;

    @Column(name = "end_date")
    private LocalDateTime endDate;

    @Column(name = "registration_start")
    private LocalDateTime registrationStart;

    @Column(name = "registration_end")
    private LocalDateTime registrationEnd;

    @Column(name = "add_drop_end")
    private LocalDateTime addDropEnd;

    @Column(length = 16)
    private String status;          // PREPARATION / OPEN / CLOSED

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "active_snapshot_id")
    private CatalogSnapshot activeSnapshot;

    @Column(columnDefinition = "CHAR(3)")
    private byte[] currency;

    private LocalDateTime closed_at;

    @Version
    private Long version;
}
