package com.elipair.church.domain.role;

import com.elipair.church.global.common.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 역할(권한 묶음 + priority 위계). created_at만 갖고 soft-delete/version 없는 마스터 데이터라
 * BaseTimeEntity를 상속하고 물리 삭제한다(스펙 §3.2). API 생성 역할은 is_system=false 고정.
 */
@Entity
@Table(
        name = "roles",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uq_roles_church_name",
                        columnNames = {"church_id", "name"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Role extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(nullable = false)
    private int priority;

    @Column(name = "is_system", nullable = false)
    private boolean isSystem;

    @Column(length = 255)
    private String description;

    // 응답 순서를 name ASC로 고정(Set 반환 순서 불안정 방지). 삭제 시 role_permissions는 DB CASCADE.
    @ManyToMany(fetch = FetchType.LAZY)
    @OrderBy("name ASC")
    @JoinTable(
            name = "role_permissions",
            joinColumns = @JoinColumn(name = "role_id"),
            inverseJoinColumns = @JoinColumn(name = "permission_id"))
    private Set<Permission> permissions = new LinkedHashSet<>();

    private Role(String name, int priority, String description) {
        this.name = name;
        this.priority = priority;
        this.isSystem = false;
        this.description = description;
    }

    /** API 생성용 — is_system은 항상 false(시스템 역할은 시드로만 생성). */
    public static Role create(String name, int priority, String description) {
        return new Role(name, priority, description);
    }

    /** 각 인자 null은 미변경(부분 수정). */
    public void update(String name, Integer priority, String description) {
        if (name != null) {
            this.name = name;
        }
        if (priority != null) {
            this.priority = priority;
        }
        if (description != null) {
            this.description = description;
        }
    }

    /** 권한 전체 교체(PUT 시맨틱). */
    public void replacePermissions(Collection<Permission> next) {
        this.permissions.clear();
        this.permissions.addAll(next);
    }
}
