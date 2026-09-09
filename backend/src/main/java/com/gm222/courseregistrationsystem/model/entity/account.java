package com.gm222.courseregistrationsystem.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;

import java.time.LocalDateTime;

@Entity
@Table(name = "accounts")
@Setter
@Getter
@NoArgsConstructor(force = true)
@AllArgsConstructor
@ToString
@EqualsAndHashCode
public class account {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private final long id;                      //Primary Key

    @Column(unique = true,nullable = false,length = 64)
    private final String username;              //NOT NULL,Unique Key,采用统一大小写规则

    @Column(nullable = false,length = 255)
    private final String password_hash;         //NOT NULL,BCrypt摘要

    @Column(length = 16)
    private final String role;                  //STUDENT/PROFESSOR/REGISTRAR

    private final boolean enabled;              //是否允许登录
    private final int failed_attempts;          //登录失败限制
    private final LocalDateTime locked_until;   //锁定到期
    private final long version;                 //版本

    @Column(updatable = false) @CreatedDate
    private final LocalDateTime created_at;     //创建时间

    @LastModifiedDate
    private final LocalDateTime updated_at;     //更新时间
}
