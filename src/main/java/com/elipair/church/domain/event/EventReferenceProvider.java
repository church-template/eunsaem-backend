package com.elipair.church.domain.event;

import com.elipair.church.domain.media.MediaReferenceProvider;
import com.elipair.church.global.common.ContentRef;
import com.elipair.church.global.tenant.TenantContext;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 본문(description) media:{id} 참조 추적(스펙 §5.10 SPI). MediaService가 빈으로 주입받아 합집합에 더한다.
 * ContentRef.type은 소문자 "event" — 미디어 참조 API 계약 값(스펙 §5.10 UNION). soft-deleted 일정은 제외(자기 치유).
 * 경계 안전: media:42 뒤에 숫자가 오면 매칭하지 않아 42가 420/421에 오탐되지 않는다.
 */
@Component
class EventReferenceProvider implements MediaReferenceProvider {

    private final EventRepository repository;

    EventReferenceProvider(EventRepository repository) {
        this.repository = repository;
    }

    @Override
    public List<ContentRef> findReferences(long mediaId) {
        String pattern = "media:" + mediaId + "($|[^0-9])";
        return repository.findReferencesByMedia(pattern, TenantContext.require()).stream()
                .map(row -> new ContentRef("event", row.getId(), row.getTitle()))
                .toList();
    }
}
