package com.gm222.courseregistrationsystem.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Entity
@Table(name = "catalog_snapshot")
@Setter
@Getter
@NoArgsConstructor
@AllArgsConstructor
@ToString
@EqualsAndHashCode
@EntityListeners(AuditingEntityListener.class)
public class CatalogSnapshot {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 多对一关联学期表，外键semester_id
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "semester_id", nullable = false)
    private Semester semester;

    @Column(name = "source_version", length = 128)
    private String sourceVersion; // 外部源版本标识

    @Column(name = "content_hash", columnDefinition = "CHAR(64)")
    private String contentHash; // 课程目录内容摘要哈希

    @Column(length = 16, nullable = false)
    private String status; // 状态：STAGING / PUBLISHED / REJECTED

    @Column(name = "fetched_at")
    private LocalDateTime fetchedAt; // 从外部源获取时间

    @Column(name = "verified_at")
    private LocalDateTime verifiedAt; // 最近完整校验时间

    @Column(name = "published_at")
    private LocalDateTime publishedAt; // 正式发布时间

    @Column(name = "source_kind", length = 32)
    private String sourceKind; // 数据源类型：LEGACY / MOCK

    @Column(name = "last_error", length = 500)
    private String lastError; // 脱敏后的错误摘要信息
}
