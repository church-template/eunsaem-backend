package com.elipair.church.global.common;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import java.time.LocalDateTime;
import lombok.Getter;
import org.hibernate.annotations.TenantId;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * 생성 시각만 담는 최상위 감사 기반 클래스(@MappedSuperclass).
 * 마스터·회원·미디어 등 "생성 시각은 필요하나 수정추적/소프트삭제/낙관락은 불필요"한 엔티티가 상속한다.
 * 감사 리스너는 서브클래스(BaseEntity)가 상속하므로, 거기 선언된 @LastModified*도 함께 처리된다.
 *
 * <p>멀티테넌시(#86): 이 클래스를 상속하는 모든 엔티티는 교회(church_id)에 속한다. {@code @TenantId}라
 * 저장 시 현재 교회로 자동 채워지고, 조회·수정·삭제에는 교회 조건이 자동으로 붙는다. 서비스 코드에서 직접 세팅하지 않는다.
 * (네이티브 쿼리에는 붙지 않는다 — 직접 {@code church_id = :churchId}를 써야 한다.)
 */
@Getter
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseTimeEntity {

    @TenantId
    @Column(name = "church_id", nullable = false, updatable = false)
    private Long churchId;

    @CreatedDate
    @Column(name = "created_at", updatable = false, nullable = false)
    private LocalDateTime createdAt;
}
