package com.elipair.church.domain.gallery;

import com.elipair.church.domain.media.MediaReferenceProvider;
import com.elipair.church.global.common.ContentRef;
import com.elipair.church.global.tenant.TenantContext;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 앨범 본문(description) media:{id} 참조 추적(스펙 §5.10 SPI). ContentRef.type="gallery_album".
 * soft-deleted 앨범 제외. 경계 안전: media:42가 media:420/421에 오탐되지 않는다.
 */
@Component
class GalleryAlbumReferenceProvider implements MediaReferenceProvider {

    private final GalleryAlbumRepository repository;

    GalleryAlbumReferenceProvider(GalleryAlbumRepository repository) {
        this.repository = repository;
    }

    @Override
    public List<ContentRef> findReferences(long mediaId) {
        String pattern = "media:" + mediaId + "($|[^0-9])";
        return repository.findReferencesByMedia(pattern, TenantContext.require()).stream()
                .map(row -> new ContentRef("gallery_album", row.getId(), row.getTitle()))
                .toList();
    }
}
