package com.elipair.church.domain.sermon;

import com.elipair.church.domain.media.MediaReferenceProvider;
import com.elipair.church.global.common.ContentRef;
import com.elipair.church.global.tenant.TenantContext;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 본문 media:{id} 참조 추적의 첫 구현(스펙 §5.10 SPI). MediaService가 빈으로 주입받아 합집합에 더한다.
 * ContentRef.type은 소문자 "sermon" — 미디어 참조 API 계약 값(스펙 §5.10, MediaServiceTest).
 * 경계 안전: media:42 뒤에 숫자가 오면 매칭하지 않아 42가 420/421에 오탐되지 않는다.
 */
@Component
class SermonReferenceProvider implements MediaReferenceProvider {

    private final SermonRepository repository;

    SermonReferenceProvider(SermonRepository repository) {
        this.repository = repository;
    }

    @Override
    public List<ContentRef> findReferences(long mediaId) {
        String pattern = "media:" + mediaId + "($|[^0-9])";
        return repository.findReferencesByMedia(pattern, TenantContext.require()).stream()
                .map(row -> new ContentRef("sermon", row.getId(), row.getTitle()))
                .toList();
    }
}
