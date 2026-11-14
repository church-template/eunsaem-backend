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
class GalleryAlbumRepositoryTest {

    @Autowired
    private GalleryAlbumRepository repository;

    @Test
    void save_populates_audit_and_version() {
        GalleryAlbum saved = repository.saveAndFlush(GalleryAlbum.create("부활절", "본문"));
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getVersion()).isZero();
    }

    @Test
    void findByIdAndDeletedAtIsNull_excludes_soft_deleted() {
        GalleryAlbum active = repository.saveAndFlush(GalleryAlbum.create("활성", "본문"));
        GalleryAlbum deleted = GalleryAlbum.create("삭제", "본문");
        deleted.softDelete();
        GalleryAlbum savedDeleted = repository.saveAndFlush(deleted);

        assertThat(repository.findByIdAndDeletedAtIsNull(active.getId())).isPresent();
        assertThat(repository.findByIdAndDeletedAtIsNull(savedDeleted.getId())).isEmpty();
    }

    @Test
    void findByIdForUpdate_returns_active_album() {
        GalleryAlbum saved = repository.saveAndFlush(GalleryAlbum.create("락대상", "본문"));
        assertThat(repository.findByIdForUpdate(saved.getId())).isPresent();
        GalleryAlbum deleted = GalleryAlbum.create("삭제", "본문");
        deleted.softDelete();
        GalleryAlbum savedDeleted = repository.saveAndFlush(deleted);
        assertThat(repository.findByIdForUpdate(savedDeleted.getId())).isEmpty();
    }

    @Test
    void findReferencesByMedia_is_boundary_safe() {
        repository.saveAndFlush(GalleryAlbum.create("42참조", "본문 ![](media:42) 끝"));
        repository.saveAndFlush(GalleryAlbum.create("420참조", "본문 ![](media:420) 끝"));

        List<GalleryAlbumRefRow> rows = repository.findReferencesByMedia("media:42($|[^0-9])", 1L);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getTitle()).isEqualTo("42참조");
    }
}
