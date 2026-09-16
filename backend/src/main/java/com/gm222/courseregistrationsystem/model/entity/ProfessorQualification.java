package com.gm222.courseregistrationsystem.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Entity
@Table(name = "professor_qualification", indexes = {
        @Index(name = "idx_qualification_course", columnList = "course_id")
})
@IdClass(ProfessorQualificationId.class)
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@ToString(onlyExplicitlyIncluded = true)
public class ProfessorQualification {
    @EmbeddedId
    private ProfessorQualificationId id;

    @Id
    @ToString.Include
    @Column(name = "professor_id", nullable = false)
    private Long professorId;

    @Id
    @ToString.Include
    @Column(name = "course_id", nullable = false)
    private Long courseId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "professor_id", nullable = false, insertable = false, updatable = false)
    private Professor professor;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "course_id", nullable = false, insertable = false, updatable = false)
    private Course course;

    @Column(name = "source_ref", length = 128)
    private String sourceRef;

    @CreatedDate
    @Column(name = "imported_at", nullable = false, updatable = false, columnDefinition = "DATETIME(3)")
    private LocalDateTime importedAt;
}
