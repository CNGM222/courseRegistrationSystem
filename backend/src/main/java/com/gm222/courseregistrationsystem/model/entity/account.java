package com.gm222.courseregistrationsystem.model.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class account {
    private final long id;                      //Primary Key
    private final String username;              //NOT NULL,Unique Key,采用统一大小写规则
    private final String password_hash;         //NOT NULL,BCrypt摘要
    private final String role;                  //STUDENT/PROFESSOR/REGISTRAR
    private final boolean enabled;              //是否允许登录
    private final int failed_attempts;          //登录失败限制
    private final LocalDateTime locked_until;   //锁定到期
    private final long version;                 //版本
    private final LocalDateTime created_at;     //创建时间
    private final LocalDateTime updated_at;     //更新时间

    public account(LocalDateTime created_at, boolean enabled, int failed_attempts, long id, LocalDateTime locked_until, String password_hash, String role, LocalDateTime updated_at, String username, long version) {
        this.created_at = created_at;
        this.enabled = enabled;
        this.failed_attempts = failed_attempts;
        this.id = id;
        this.locked_until = locked_until;
        this.password_hash = password_hash;
        this.role = role;
        this.updated_at = updated_at;
        this.username = username;
        this.version = version;
    }
}
