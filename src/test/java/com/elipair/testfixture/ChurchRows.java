package com.elipair.testfixture;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 격리 테스트용: 한 교회에 종류별로 행을 하나씩 넣는다(#86). 테넌트 필터를 거치지 않도록 JdbcClient로 직접 넣고
 * church_id를 명시한다. 제목·이름에는 {@code marker}가 들어가 응답 본문에서 어느 교회 행인지 가려낼 수 있다.
 */
public record ChurchRows(
        long churchId,
        String marker,
        long memberId,
        UUID memberUuid,
        long positionId,
        long roleId,
        long tagId,
        long mediaId,
        long sermonId,
        long noticeId,
        long eventId,
        long departmentId,
        long albumId,
        long photoId,
        long bulletinId,
        long challengeId,
        long inquiryId,
        long vehicleRunId) {

    /** church_id를 가진 테이블(자식 → 부모 순). 스냅샷·정리에 쓴다. */
    public static final List<String> TABLES = List.of(
            "vehicle_requests",
            "vehicle_runs",
            "inquiries",
            "challenge_reading_logs",
            "challenge_participations",
            "bible_challenges",
            "bulletins",
            "gallery_photos",
            "gallery_albums",
            "departments",
            "events",
            "notices",
            "sermons",
            "content_tags",
            "media",
            "tags",
            "members",
            "positions",
            "roles");

    private static long insert(JdbcClient jdbc, String sql, Object... params) {
        return jdbc.sql(sql + " returning id").params(params).query(Long.class).single();
    }

    public static ChurchRows insertAll(JdbcClient jdbc, long churchId, String marker) {
        UUID memberUuid = UUID.randomUUID();
        long position = insert(
                jdbc,
                "insert into positions(name, sort_order, created_at, church_id) values (?, 1, now(), ?)",
                marker + "-직분",
                churchId);
        long member = insert(
                jdbc,
                "insert into members(uuid, phone, name, password, terms_agreed, privacy_agreed, created_at, church_id)"
                        + " values (?, ?, ?, 'hash', true, true, now(), ?)",
                memberUuid,
                "0107" + String.format("%07d", Math.floorMod(memberUuid.hashCode(), 10_000_000)),
                marker + "-회원",
                churchId);
        long role = insert(
                jdbc,
                "insert into roles(name, priority, is_system, description, created_at, church_id)"
                        + " values (?, 500, false, '격리', now(), ?)",
                marker + "-ROLE",
                churchId);
        long tag = insert(
                jdbc, "insert into tags(name, created_at, church_id) values (?, now(), ?)", marker + "-태그", churchId);
        long media = insert(
                jdbc,
                "insert into media(filename, stored_path, mime_type, size, uploaded_by, created_at, church_id)"
                        + " values (?, ?, 'image/jpeg', 1, ?, now(), ?)",
                marker + ".jpg",
                "iso/" + UUID.randomUUID() + ".jpg",
                member,
                churchId);
        long sermon = insert(
                jdbc,
                "insert into sermons(title, preacher, preached_at, content, created_at, church_id)"
                        + " values (?, 'p', current_date, 'x', now(), ?)",
                marker + "-설교",
                churchId);
        long notice = insert(
                jdbc,
                "insert into notices(title, content, created_at, church_id) values (?, 'x', now(), ?)",
                marker + "-공지",
                churchId);
        long event = insert(
                jdbc,
                "insert into events(title, description, start_at, created_at, church_id)"
                        + " values (?, 'x', now() + interval '1 day', now(), ?)",
                marker + "-일정",
                churchId);
        long department = insert(
                jdbc,
                "insert into departments(name, description, created_at, church_id) values (?, 'x', now(), ?)",
                marker + "-부서",
                churchId);
        long album = insert(
                jdbc,
                "insert into gallery_albums(title, description, created_at, church_id) values (?, 'x', now(), ?)",
                marker + "-앨범",
                churchId);
        long photo = insert(
                jdbc,
                "insert into gallery_photos(album_id, media_id, sort_order, created_at, church_id)"
                        + " values (?, ?, 0, now(), ?)",
                album,
                media,
                churchId);
        long bulletin = insert(
                jdbc,
                "insert into bulletins(title, service_date, media_id, created_at, church_id)"
                        + " values (?, current_date, ?, now(), ?)",
                marker + "-주보",
                media,
                churchId);
        long challenge = insert(
                jdbc,
                "insert into bible_challenges(title, start_book, end_book, start_date, target_days, created_at, church_id)"
                        + " values (?, 1, 66, current_date, 100, now(), ?)",
                marker + "-통독",
                churchId);
        long inquiry = insert(
                jdbc,
                "insert into inquiries(name, phone, content, privacy_agreed_at, created_at, church_id)"
                        + " values (?, '01000000000', 'x', now(), now(), ?)",
                marker + "-문의",
                churchId);
        long run = insert(
                jdbc,
                "insert into vehicle_runs(departs_at, created_at, church_id)"
                        + " values (now() + interval '1 day', now(), ?)",
                churchId);
        return new ChurchRows(
                churchId,
                marker,
                member,
                memberUuid,
                position,
                role,
                tag,
                media,
                sermon,
                notice,
                event,
                department,
                album,
                photo,
                bulletin,
                challenge,
                inquiry,
                run);
    }

    /** 격리 테스트가 만든 행을 지운다. 교회의 기본 역할 4종(TwoChurches가 복사한 것)은 남긴다. */
    public static void deleteAll(JdbcClient jdbc, long... churchIds) {
        for (long churchId : churchIds) {
            jdbc.sql("delete from member_roles where member_id in (select id from members where church_id = ?)")
                    .param(churchId)
                    .update();
            for (String table : TABLES) {
                String extra =
                        table.equals("roles") ? " and name not in ('SUPER_ADMIN', 'ADMIN', 'MEMBER', 'USER')" : "";
                jdbc.sql("delete from " + table + " where church_id = ?" + extra)
                        .param(churchId)
                        .update();
            }
        }
    }

    /** 한 교회의 모든 행 내용을 요약한 값. 요청 전후로 비교해 "아무것도 바뀌지 않았다"를 확인한다. */
    public static String snapshot(JdbcClient jdbc, long churchId) {
        StringBuilder all = new StringBuilder();
        for (String table : TABLES) {
            all.append(table)
                    .append('=')
                    .append(jdbc.sql("select coalesce(md5(string_agg(t::text, '|' order by t::text)), '-') from "
                                    + table + " t where church_id = ?")
                            .param(churchId)
                            .query(String.class)
                            .single())
                    .append(';');
        }
        all.append("member_roles=")
                .append(jdbc.sql("select coalesce(md5(string_agg(mr::text, '|' order by mr::text)), '-')"
                                + " from member_roles mr join members m on m.id = mr.member_id where m.church_id = ?")
                        .param(churchId)
                        .query(String.class)
                        .single());
        all.append(";role_permissions=")
                .append(jdbc.sql("select coalesce(md5(string_agg(rp::text, '|' order by rp::text)), '-')"
                                + " from role_permissions rp join roles r on r.id = rp.role_id where r.church_id = ?")
                        .param(churchId)
                        .query(String.class)
                        .single());
        return all.toString();
    }
}
