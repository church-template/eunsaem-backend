package com.elipair.church.domain.gallery;

import static org.assertj.core.api.Assertions.assertThat;

import com.elipair.church.TestcontainersConfiguration;
import com.elipair.church.global.config.JpaConfig;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TestcontainersConfiguration.class, JpaConfig.class})
@TestPropertySource(properties = {"spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop"})
class GalleryPhotoRepositoryTest {

    @Autowired
    private GalleryAlbumRepository albumRepository;

    @Autowired
    private GalleryPhotoRepository photoRepository;

    private Long albumId;

    private Long newAlbum(String title) {
        return albumRepository.saveAndFlush(GalleryAlbum.create(title, "본문")).getId();
    }

    @Test
    void findMaxSortOrder_minus_one_when_empty_then_max() {
        albumId = newAlbum("A");
        assertThat(photoRepository.findMaxSortOrder(albumId)).isEqualTo(-1);
        photoRepository.saveAndFlush(GalleryPhoto.create(albumId, 1L, 0));
        photoRepository.saveAndFlush(GalleryPhoto.create(albumId, 2L, 1));
        assertThat(photoRepository.findMaxSortOrder(albumId)).isEqualTo(1);
    }

    @Test
    void findByAlbumId_orders_by_sort_then_id() {
        albumId = newAlbum("A");
        photoRepository.saveAndFlush(GalleryPhoto.create(albumId, 10L, 1));
        photoRepository.saveAndFlush(GalleryPhoto.create(albumId, 11L, 0));

        List<Long> mediaIds = photoRepository.findByAlbumIdOrderBySortOrderAscIdAsc(albumId).stream()
                .map(GalleryPhoto::getMediaId)
                .toList();
        assertThat(mediaIds).containsExactly(11L, 10L); // sort_order 0 먼저
    }

    @Test
    void deleteByAlbumId_removes_all_links() {
        albumId = newAlbum("A");
        photoRepository.saveAndFlush(GalleryPhoto.create(albumId, 1L, 0));
        photoRepository.saveAndFlush(GalleryPhoto.create(albumId, 2L, 1));

        photoRepository.deleteByAlbumId(albumId);

        assertThat(photoRepository.findByAlbumIdOrderBySortOrderAscIdAsc(albumId))
                .isEmpty();
    }

    @Test
    void thumbnails_returns_first_photo_per_album() {
        Long a1 = newAlbum("A1");
        Long a2 = newAlbum("A2");
        photoRepository.saveAndFlush(GalleryPhoto.create(a1, 100L, 1));
        photoRepository.saveAndFlush(GalleryPhoto.create(a1, 101L, 0)); // a1 대표
        photoRepository.saveAndFlush(GalleryPhoto.create(a2, 200L, 0)); // a2 대표

        List<AlbumThumbnailRow> rows = photoRepository.findThumbnails(List.of(a1, a2), 1L);

        assertThat(rows).hasSize(2);
        assertThat(rows).anySatisfy(r -> {
            assertThat(r.getAlbumId()).isEqualTo(a1);
            assertThat(r.getMediaId()).isEqualTo(101L);
        });
    }

    @Test
    void counts_returns_photo_count_per_album() {
        Long a1 = newAlbum("A1");
        photoRepository.saveAndFlush(GalleryPhoto.create(a1, 1L, 0));
        photoRepository.saveAndFlush(GalleryPhoto.create(a1, 2L, 1));

        List<AlbumPhotoCountRow> rows = photoRepository.countByAlbumIds(List.of(a1));
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getCount()).isEqualTo(2L);
    }

    @Test
    void findReferencesByMediaId_surfaces_album_and_excludes_deleted_album() {
        Long live = newAlbum("라이브앨범");
        photoRepository.saveAndFlush(GalleryPhoto.create(live, 42L, 0));
        photoRepository.saveAndFlush(GalleryPhoto.create(live, 42L, 1)); // 같은 media 중복 → DISTINCT 1건

        List<GalleryPhotoRefRow> rows = photoRepository.findReferencesByMediaId(42L, 1L);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getTitle()).isEqualTo("라이브앨범");

        // 앨범이 soft-deleted면 조인 필터로 제외
        GalleryAlbum dead = GalleryAlbum.create("죽은앨범", "본문");
        dead.softDelete();
        Long deadId = albumRepository.saveAndFlush(dead).getId();
        photoRepository.saveAndFlush(GalleryPhoto.create(deadId, 77L, 0));
        assertThat(photoRepository.findReferencesByMediaId(77L, 1L)).isEmpty();
    }
}
