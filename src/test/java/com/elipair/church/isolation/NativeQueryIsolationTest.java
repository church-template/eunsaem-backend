package com.elipair.church.isolation;

import static org.assertj.core.api.Assertions.assertThat;

import com.elipair.church.TestcontainersConfiguration;
import com.elipair.church.domain.challenge.ChallengeParticipationRepository;
import com.elipair.church.domain.gallery.GalleryPhotoRepository;
import com.elipair.church.domain.media.MediaReferenceProvider;
import com.elipair.church.global.common.ContentRef;
import com.elipair.testfixture.TwoChurches;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 네이티브 쿼리의 교회 격리(#86). 네이티브 SQL에는 Hibernate 테넌트 필터가 걸리지 않으므로
 * 쿼리마다 넣은 {@code church_id} 조건이 실제로 다른 교회 행을 걸러내는지 확인한다.
 * 데이터는 교회 B에만 만들고, 교회 A에서 같은 id로 조회했을 때 아무것도 보이지 않아야 한다.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, TwoChurches.class})
class NativeQueryIsolationTest {

    @Autowired
    private TwoChurches churches;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private List<MediaReferenceProvider> referenceProviders;

    @Autowired
    private GalleryPhotoRepository photoRepository;

    @Autowired
    private ChallengeParticipationRepository participationRepository;

    private long mediaB;
    private long albumB;
    private long challengeB;

    private long insert(String sql, Object... params) {
        return jdbc.sql(sql + " returning id").params(params).query(Long.class).single();
    }

    @BeforeEach
    void seedChurchB() {
        long b = churches.bId();
        long memberB = insert(
                "insert into members(uuid, phone, name, password, created_at, church_id)"
                        + " values (gen_random_uuid(), '01055550001', '비회원', 'p', now(), ?)",
                b);
        mediaB = insert(
                "insert into media(filename, stored_path, mime_type, size, uploaded_by, created_at, church_id)"
                        + " values ('x.jpg', 'iso/native/x.jpg', 'image/jpeg', 1, ?, now(), ?)",
                memberB,
                b);
        String body = "![x](media:" + mediaB + ")";
        insert(
                "insert into sermons(title, preacher, preached_at, content, created_at, church_id)"
                        + " values ('B설교', 'p', current_date, ?, now(), ?)",
                body,
                b);
        insert("insert into notices(title, content, created_at, church_id) values ('B공지', ?, now(), ?)", body, b);
        insert(
                "insert into events(title, description, start_at, created_at, church_id)"
                        + " values ('B일정', ?, now(), now(), ?)",
                body,
                b);
        insert(
                "insert into departments(name, description, created_at, church_id) values ('B부서', ?, now(), ?)",
                body,
                b);
        albumB = insert(
                "insert into gallery_albums(title, description, created_at, church_id) values ('B앨범', ?, now(), ?)",
                body,
                b);
        insert(
                "insert into gallery_photos(album_id, media_id, sort_order, created_at, church_id)"
                        + " values (?, ?, 0, now(), ?)",
                albumB,
                mediaB,
                b);
        insert(
                "insert into bulletins(title, service_date, media_id, created_at, church_id)"
                        + " values ('B주보', current_date, ?, now(), ?)",
                mediaB,
                b);
        challengeB = insert(
                "insert into bible_challenges(title, start_book, end_book, start_date, target_days, created_at, church_id)"
                        + " values ('B통독', 1, 66, current_date, 100, now(), ?)",
                b);
        insert(
                "insert into challenge_participations(challenge_id, member_id, created_at, church_id)"
                        + " values (?, ?, now(), ?)",
                challengeB,
                memberB,
                b);
    }

    @AfterEach
    void cleanup() {
        for (String table : List.of(
                "challenge_participations",
                "bible_challenges",
                "bulletins",
                "gallery_photos",
                "gallery_albums",
                "departments",
                "events",
                "notices",
                "sermons",
                "media",
                "members")) {
            jdbc.sql("delete from " + table + " where church_id in (?, ?)")
                    .params(churches.aId(), churches.bId())
                    .update();
        }
    }

    private List<ContentRef> references(long mediaId) {
        return referenceProviders.stream()
                .flatMap(p -> p.findReferences(mediaId).stream())
                .toList();
    }

    @Test
    void 미디어_참조추적은_다른_교회의_본문과_FK를_잡지_않는다() {
        // 본문 5종(설교·공지·일정·부서·앨범) + FK 2종(갤러리 사진·주보)
        assertThat(churches.inB(() -> references(mediaB))).hasSize(7);
        assertThat(churches.inA(() -> references(mediaB))).isEmpty();
    }

    @Test
    void 앨범_대표사진은_다른_교회_앨범_id로_조회되지_않는다() {
        assertThat(churches.inB(() -> photoRepository.findThumbnails(List.of(albumB), churches.bId())))
                .hasSize(1);
        assertThat(churches.inA(() -> photoRepository.findThumbnails(List.of(albumB), churches.aId())))
                .isEmpty();
    }

    @Test
    void 참가자_명단과_건수에_다른_교회_행이_없다() {
        PageRequest page = PageRequest.of(0, 10);
        assertThat(churches.inB(() -> participationRepository.findParticipants(challengeB, 100, page, churches.bId()))
                        .getTotalElements())
                .isEqualTo(1);
        var fromA = churches.inA(() -> participationRepository.findParticipants(challengeB, 100, page, churches.aId()));
        assertThat(fromA.getContent()).isEmpty();
        assertThat(fromA.getTotalElements()).isZero();
    }
}
