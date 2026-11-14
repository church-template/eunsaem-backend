package com.elipair.church.domain.tag;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.TenantId;

/** 콘텐츠 ↔ 태그 다형 연결(순수 조인, 복합키 외 컬럼 없음). */
@Entity
@Table(name = "content_tags")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ContentTag {

    @EmbeddedId
    private ContentTagId id;

    /** 교회(#86). 기반 클래스가 없어 직접 선언한다 — 저장 시 현재 교회로 자동 채워진다. */
    @TenantId
    @Column(name = "church_id", nullable = false, updatable = false)
    private Long churchId;

    public ContentTag(ContentTagId id) {
        this.id = id;
    }
}
