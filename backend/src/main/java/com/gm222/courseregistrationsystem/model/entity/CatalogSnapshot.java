package com.gm222.courseregistrationsystem.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Entity
@Table(name = "catalog_snapshot", indexes = {
        @Index(name = "idx_snapshot_semester_status", columnList = "semester_id,status")
})
@Setter
@Getter
@NoArgsConstructor
@AllArgsConstructor
@ToString(onlyExplicitlyIncluded = true)
@EntityListeners(AuditingEntityListener.class)
public class CatalogSnapshot {
    @ToString.Include
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 多对一关联学期表，外键semester_id
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "semester_id", nullable = false)
    private Semester semester;

    @Column(name = "source_version", length = 128)
    private String sourceVersion; // 外部源版本标识

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "content_hash", length = 64, columnDefinition = "CHAR(64)")
    private String contentHash; // 课程目录内容摘要哈希

    @Column(length = 16, nullable = false)
    private String status = "STAGING"; // 状态：STAGING / PUBLISHED / REJECTED

    @CreatedDate
    @Column(name = "fetched_at", nullable = false, updatable = false, columnDefinition = "DATETIME(3)")
    private LocalDateTime fetchedAt; // 从外部源获取时间

    @Column(name = "verified_at", columnDefinition = "DATETIME(3)")
    private LocalDateTime verifiedAt; // 最近完整校验时间

    @Column(name = "published_at", columnDefinition = "DATETIME(3)")
    private LocalDateTime publishedAt; // 正式发布时间

    @Column(name = "source_kind", length = 32, nullable = false)
    private String sourceKind; // 数据源类型：LEGACY / MOCK

    @Column(name = "last_error", length = 500)
    private String lastError; // 脱敏后的错误摘要信息
}
