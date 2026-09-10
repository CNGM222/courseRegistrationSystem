package com.gm222.courseregistrationsystem.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Entity
@Table(name = "professors")
@Setter
@Getter
@NoArgsConstructor
@AllArgsConstructor
@ToString
@EqualsAndHashCode
@EntityListeners(AuditingEntityListener.class)
public class Professor {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 一对一关联账号表，外键account_id唯一
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", unique = true, nullable = false)
    private Account account;

    @Column(length = 100)
    private String name;

    @Column(name = "birth_date")
    private LocalDateTime birthDate;

    @Column(length = 20)
    private String status;      // ACTIVE / INACTIVE

    @Column(length = 100)
    private String department;

    @Column(name = "ssn_ciphertext", columnDefinition = "VARBINARY(512)")
    private byte[] ssnCiphertext; // 社保号加密密文

    @Column(name = "ssn_fingerprint", columnDefinition = "BINARY(32)", unique = true)
    private byte[] ssnFingerprint; // 社保号HMAC指纹，用于查重

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt; // 逻辑删除标记时间

    @Version
    private Long version; // 乐观锁并发版本号
}
