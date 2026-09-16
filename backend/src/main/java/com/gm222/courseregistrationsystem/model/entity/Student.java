package com.gm222.courseregistrationsystem.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.jspecify.annotations.NonNull;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Set;

@Entity
@Table(name = "students")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@ToString(onlyExplicitlyIncluded = true)
public class Student extends AuditedEntity {
    @ToString.Include
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 一对一关联账号表，外键account_id唯一
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", unique = true, nullable = false)
    private Account account;

    @Column(length = 100, nullable = false)
    private String name; // 学生姓名

    @Column(length = 20, nullable = false)
    private String status = "ACTIVE"; // ACTIVE / INACTIVE / GRADUATED

    @Column(name = "birth_date", nullable = false)
    private LocalDate birthDate; // 出生日期

    @Column(name = "graduation_date")
    private LocalDate graduationDate; // 毕业日期（可空）

    @Column(name = "ssn_ciphertext", columnDefinition = "VARBINARY(512)")
    private byte[] ssnCiphertext; // 社保号加密内容

    @Column(name = "ssn_fingerprint", columnDefinition = "BINARY(32)", unique = true)
    private byte[] ssnFingerprint; // 社保号HMAC指纹，用于查重

    @Column(name = "deleted_at", columnDefinition = "DATETIME(3)")
    private LocalDateTime deletedAt; // 逻辑删除时间

    @Version
    @Column(nullable = false)
    private Long version; // 乐观锁版本号

    @NonNull
    @OneToMany(mappedBy = "student")
    private Set<BillingInvoice> billingInvoices = new LinkedHashSet<>();

    @NonNull
    @OneToMany(mappedBy = "student")
    private Set<Schedule> schedules = new LinkedHashSet<>();
}
