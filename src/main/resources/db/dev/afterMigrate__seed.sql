-- ─────────────────────────────────────────────────────────────────────────────
-- 로컬 개발용 목데이터 시드 (dev 프로필 전용).
--
-- 동작: Flyway afterMigrate 콜백 → 매 부팅마다 V1~V12 적용 직후 실행된다.
--       application-dev.yml이 flyway.locations에 classpath:db/dev를 더할 때만 스캔된다.
--       운영(기본/prod)은 db/migration만 보므로 이 파일은 절대 실행되지 않는다.
--
-- ★ id 전략: 모든 시드 행은 9000번대 예약 블록을 쓴다.
--   - 앱이 만드는 실데이터(회원 가입, 부트스트랩 SUPER_ADMIN, 관리자 콘텐츠)는 말미 시퀀스 예약 덕에
--     10000 이상 id를 받으므로, 9000번대 고정 id는 그들과 절대 충돌하지 않는다.
--   - "id 9000~9999 = 개발 시드"로 한눈에 구분된다. 정리하려면 id 9000~9999 행만 지우면 된다
--     (id>=9000 삭제는 10000+ 실데이터까지 지우므로 금지).
--   - ON CONFLICT (id) DO NOTHING + 말미 setval(MAX)로 멱등 + 시퀀스 안전.
--
-- 권한/역할/직분 매핑: permissions·roles·role_permissions는 V2가 시드했고,
--                     SUPER_ADMIN은 이 시드의 9019(교회 대표자)다. 운영은 scripts/new-church.sh가 교회 대표자를 만든다.
-- 멀티테넌시(#86): 모든 시드 행은 첫 교회(V18이 만든 churches 첫 행)에 속한다.
--                     이 시드는 앱 회원(낮은 id)을 절대 건드리지 않고 9000+ 회원에게만 역할을 준다.
--
-- 시드 회원 공용 비밀번호: church1234!   (BCrypt cost 10 해시, 아래 상수)
-- ─────────────────────────────────────────────────────────────────────────────

-- ── 직분(positions) ──────────────────────────────────────────────────────────
INSERT INTO positions (church_id, id, name, sort_order, created_at) VALUES
    ((SELECT id FROM churches ORDER BY id LIMIT 1), 9001, '목사',   1, now()),
    ((SELECT id FROM churches ORDER BY id LIMIT 1), 9002, '전도사', 2, now()),
    ((SELECT id FROM churches ORDER BY id LIMIT 1), 9003, '장로',   3, now()),
    ((SELECT id FROM churches ORDER BY id LIMIT 1), 9004, '권사',   4, now()),
    ((SELECT id FROM churches ORDER BY id LIMIT 1), 9005, '집사',   5, now()),
    ((SELECT id FROM churches ORDER BY id LIMIT 1), 9006, '청년',   6, now())
ON CONFLICT (id) DO NOTHING;

-- ── 회원(members) ────────────────────────────────────────────────────────────
-- password = BCrypt('church1234!'). 9008(한탈퇴)은 deleted_at을 채워 소프트삭제 상태 → 표시 시 "(탈퇴한 사용자)".
INSERT INTO members (church_id, id, uuid, phone, name, password, email, position_id,
                     terms_agreed, privacy_agreed, agreed_at, created_at, updated_at, deleted_at) VALUES
    ((SELECT id FROM churches ORDER BY id LIMIT 1), 9001, '00000000-0000-0000-0000-000000009001', '01090000001', '김은혜', '$2y$10$ykNxFMLO2GyEL2DpFBr10..Zsvq.AEJ8WJFQc.WXFVM9s46mT9vnq', 'pastor.kim@dev.local',  9001, TRUE, TRUE, now(), now(), now(), NULL),
    ((SELECT id FROM churches ORDER BY id LIMIT 1), 9002, '00000000-0000-0000-0000-000000009002', '01090000002', '이믿음', '$2y$10$ykNxFMLO2GyEL2DpFBr10..Zsvq.AEJ8WJFQc.WXFVM9s46mT9vnq', 'eva.lee@dev.local',     9002, TRUE, TRUE, now(), now(), now(), NULL),
    ((SELECT id FROM churches ORDER BY id LIMIT 1), 9003, '00000000-0000-0000-0000-000000009003', '01090000003', '박소망', '$2y$10$ykNxFMLO2GyEL2DpFBr10..Zsvq.AEJ8WJFQc.WXFVM9s46mT9vnq', NULL,                    9003, TRUE, TRUE, now(), now(), NULL, NULL),
    ((SELECT id FROM churches ORDER BY id LIMIT 1), 9004, '00000000-0000-0000-0000-000000009004', '01090000004', '최사랑', '$2y$10$ykNxFMLO2GyEL2DpFBr10..Zsvq.AEJ8WJFQc.WXFVM9s46mT9vnq', NULL,                    9004, TRUE, TRUE, now(), now(), NULL, NULL),
    ((SELECT id FROM churches ORDER BY id LIMIT 1), 9005, '00000000-0000-0000-0000-000000009005', '01090000005', '정충성', '$2y$10$ykNxFMLO2GyEL2DpFBr10..Zsvq.AEJ8WJFQc.WXFVM9s46mT9vnq', NULL,                    9005, TRUE, TRUE, now(), now(), NULL, NULL),
    ((SELECT id FROM churches ORDER BY id LIMIT 1), 9006, '00000000-0000-0000-0000-000000009006', '01090000006', '강청년', '$2y$10$ykNxFMLO2GyEL2DpFBr10..Zsvq.AEJ8WJFQc.WXFVM9s46mT9vnq', 'youth.kang@dev.local',  9006, TRUE, TRUE, now(), now(), NULL, NULL),
    ((SELECT id FROM churches ORDER BY id LIMIT 1), 9007, '00000000-0000-0000-0000-000000009007', '01090000007', '윤방문', '$2y$10$ykNxFMLO2GyEL2DpFBr10..Zsvq.AEJ8WJFQc.WXFVM9s46mT9vnq', NULL,                    NULL, TRUE, TRUE, now(), now(), NULL, NULL),
    ((SELECT id FROM churches ORDER BY id LIMIT 1), 9008, '00000000-0000-0000-0000-000000009008', '01090000008', '한탈퇴', '$2y$10$ykNxFMLO2GyEL2DpFBr10..Zsvq.AEJ8WJFQc.WXFVM9s46mT9vnq', NULL,                    9005, TRUE, TRUE, now(), now(), now(), now()),
    ((SELECT id FROM churches ORDER BY id LIMIT 1), 9009, '00000000-0000-0000-0000-000000009009', '01090000009', '김지혜', '$2y$10$ykNxFMLO2GyEL2DpFBr10..Zsvq.AEJ8WJFQc.WXFVM9s46mT9vnq', NULL,                    9005, TRUE, TRUE, now(), now(), NULL, NULL),
    ((SELECT id FROM churches ORDER BY id LIMIT 1), 9010, '00000000-0000-0000-0000-000000009010', '01090000010', '이준호', '$2y$10$ykNxFMLO2GyEL2DpFBr10..Zsvq.AEJ8WJFQc.WXFVM9s46mT9vnq', 'jh.lee@dev.local',     9006, TRUE, TRUE, now(), now(), NULL, NULL),
    ((SELECT id FROM churches ORDER BY id LIMIT 1), 9011, '00000000-0000-0000-0000-000000009011', '01090000011', '박서연', '$2y$10$ykNxFMLO2GyEL2DpFBr10..Zsvq.AEJ8WJFQc.WXFVM9s46mT9vnq', NULL,                    NULL, TRUE, TRUE, now(), now(), NULL, NULL),
    ((SELECT id FROM churches ORDER BY id LIMIT 1), 9012, '00000000-0000-0000-0000-000000009012', '01090000012', '최민준', '$2y$10$ykNxFMLO2GyEL2DpFBr10..Zsvq.AEJ8WJFQc.WXFVM9s46mT9vnq', NULL,                    9005, TRUE, TRUE, now(), now(), NULL, NULL),
    ((SELECT id FROM churches ORDER BY id LIMIT 1), 9013, '00000000-0000-0000-0000-000000009013', '01090000013', '정하은', '$2y$10$ykNxFMLO2GyEL2DpFBr10..Zsvq.AEJ8WJFQc.WXFVM9s46mT9vnq', 'he.jung@dev.local',    9004, TRUE, TRUE, now(), now(), NULL, NULL),
    ((SELECT id FROM churches ORDER BY id LIMIT 1), 9014, '00000000-0000-0000-0000-000000009014', '01090000014', '강도현', '$2y$10$ykNxFMLO2GyEL2DpFBr10..Zsvq.AEJ8WJFQc.WXFVM9s46mT9vnq', NULL,                    9006, TRUE, TRUE, now(), now(), NULL, NULL),
    ((SELECT id FROM churches ORDER BY id LIMIT 1), 9015, '00000000-0000-0000-0000-000000009015', '01090000015', '조유진', '$2y$10$ykNxFMLO2GyEL2DpFBr10..Zsvq.AEJ8WJFQc.WXFVM9s46mT9vnq', NULL,                    NULL, TRUE, TRUE, now(), now(), NULL, NULL),
    ((SELECT id FROM churches ORDER BY id LIMIT 1), 9016, '00000000-0000-0000-0000-000000009016', '01090000016', '윤서준', '$2y$10$ykNxFMLO2GyEL2DpFBr10..Zsvq.AEJ8WJFQc.WXFVM9s46mT9vnq', NULL,                    NULL, TRUE, TRUE, now(), now(), NULL, NULL),
    ((SELECT id FROM churches ORDER BY id LIMIT 1), 9017, '00000000-0000-0000-0000-000000009017', '01090000017', '임채원', '$2y$10$ykNxFMLO2GyEL2DpFBr10..Zsvq.AEJ8WJFQc.WXFVM9s46mT9vnq', NULL,                    NULL, TRUE, TRUE, now(), now(), NULL, NULL),
    ((SELECT id FROM churches ORDER BY id LIMIT 1), 9018, '00000000-0000-0000-0000-000000009018', '01090000018', '한지우', '$2y$10$ykNxFMLO2GyEL2DpFBr10..Zsvq.AEJ8WJFQc.WXFVM9s46mT9vnq', NULL,                    9006, TRUE, TRUE, now(), now(), NULL, NULL),
    ((SELECT id FROM churches ORDER BY id LIMIT 1), 9019, '00000000-0000-0000-0000-000000009019', '01090000019', '대표자', '$2y$10$ykNxFMLO2GyEL2DpFBr10..Zsvq.AEJ8WJFQc.WXFVM9s46mT9vnq', 'owner@dev.local',       9001, TRUE, TRUE, now(), now(), NULL, NULL)
ON CONFLICT (id) DO NOTHING;

-- ── 회원-역할(member_roles) ─────────────────────────────────────────────────
-- 역할 id는 환경마다 다를 수 있어 이름으로 조회한다(역할 이름은 교회마다 있으므로 첫 교회로 한정). 9000+ 시드 회원에게만 부여(앱 회원 불간섭).
-- USER = 전원(가입 시 자동 부여 모사)
INSERT INTO member_roles (member_id, role_id)
SELECT v.mid, r.id FROM (VALUES (9001),(9002),(9003),(9004),(9005),(9006),(9007),(9008),
                                (9009),(9010),(9011),(9012),(9013),(9014),(9015),(9016),(9017),(9018),(9019)) v(mid)
CROSS JOIN roles r WHERE r.name = 'USER' AND r.church_id = (SELECT id FROM churches ORDER BY id LIMIT 1)
ON CONFLICT DO NOTHING;
-- ADMIN = 9001,9002 (교역자)
INSERT INTO member_roles (member_id, role_id)
SELECT v.mid, r.id FROM (VALUES (9001),(9002)) v(mid)
CROSS JOIN roles r WHERE r.name = 'ADMIN' AND r.church_id = (SELECT id FROM churches ORDER BY id LIMIT 1)
ON CONFLICT DO NOTHING;
-- SUPER_ADMIN = 9019 (교회 대표자). 예전에는 SuperAdminInitializer가 ADMIN_* env로 만들었다.
INSERT INTO member_roles (member_id, role_id)
SELECT v.mid, r.id FROM (VALUES (9019)) v(mid)
CROSS JOIN roles r WHERE r.name = 'SUPER_ADMIN' AND r.church_id = (SELECT id FROM churches ORDER BY id LIMIT 1)
ON CONFLICT DO NOTHING;
-- MEMBER = 9003~9006,9008,9009~9015,9018 (교인 승인). 9007·9016·9017은 USER만 = 미승인(승인 대기) 테스트용.
INSERT INTO member_roles (member_id, role_id)
SELECT v.mid, r.id FROM (VALUES (9003),(9004),(9005),(9006),(9008),
                                (9009),(9010),(9011),(9012),(9013),(9014),(9015),(9018)) v(mid)
CROSS JOIN roles r WHERE r.name = 'MEMBER' AND r.church_id = (SELECT id FROM churches ORDER BY id LIMIT 1)
ON CONFLICT DO NOTHING;

-- ── 태그(tags) ───────────────────────────────────────────────────────────────
INSERT INTO tags (church_id, id, name, created_at) VALUES
    ((SELECT id FROM churches ORDER BY id LIMIT 1), 9001, '주일예배', now()),
    ((SELECT id FROM churches ORDER BY id LIMIT 1), 9002, '수요예배', now()),
    ((SELECT id FROM churches ORDER BY id LIMIT 1), 9003, '새가족',   now()),
    ((SELECT id FROM churches ORDER BY id LIMIT 1), 9004, '청년부',   now()),
    ((SELECT id FROM churches ORDER BY id LIMIT 1), 9005, '선교',     now()),
    ((SELECT id FROM churches ORDER BY id LIMIT 1), 9006, '봉사',     now()),
    ((SELECT id FROM churches ORDER BY id LIMIT 1), 9007, '절기',     now()),
    ((SELECT id FROM churches ORDER BY id LIMIT 1), 9008, '교육',     now())
ON CONFLICT (id) DO NOTHING;

-- ── 미디어(media) ────────────────────────────────────────────────────────────
-- 이미지·PDF 한 테이블. 아래 시드는 전부 어딘가에서 참조 중(본문 media:{id} 또는 갤러리/주보 FK)이라
-- DELETE 시 409 MEDIA_IN_USE가 떨어진다(차단형 삭제 시연).
INSERT INTO media (church_id, id, filename, stored_path, mime_type, size, uploaded_by, created_at) VALUES
    ((SELECT id FROM churches ORDER BY id LIMIT 1), 9001, 'sermon-cover.jpg',      'dev/seed/sermon-cover.jpg',      'image/jpeg',      245678, 9001, now()),
    ((SELECT id FROM churches ORDER BY id LIMIT 1), 9002, 'easter-1.jpg',          'dev/seed/easter-1.jpg',          'image/jpeg',      512000, 9001, now()),
    ((SELECT id FROM churches ORDER BY id LIMIT 1), 9003, 'easter-2.jpg',          'dev/seed/easter-2.jpg',          'image/jpeg',      498211, 9001, now()),
    ((SELECT id FROM churches ORDER BY id LIMIT 1), 9004, 'retreat-1.jpg',         'dev/seed/retreat-1.jpg',         'image/jpeg',      631044, 9002, now()),
    ((SELECT id FROM churches ORDER BY id LIMIT 1), 9005, 'retreat-2.jpg',         'dev/seed/retreat-2.jpg',         'image/jpeg',      587322, 9002, now()),
    ((SELECT id FROM churches ORDER BY id LIMIT 1), 9006, 'christmas-1.jpg',       'dev/seed/christmas-1.jpg',       'image/jpeg',      472900, 9001, now()),
    ((SELECT id FROM churches ORDER BY id LIMIT 1), 9007, 'event-poster.png',      'dev/seed/event-poster.png',      'image/png',       820145, 9001, now()),
    ((SELECT id FROM churches ORDER BY id LIMIT 1), 9008, 'bulletin-20260608.pdf', 'dev/seed/bulletin-20260608.pdf', 'application/pdf', 1340221, 9001, now()),
    ((SELECT id FROM churches ORDER BY id LIMIT 1), 9009, 'bulletin-20260601.pdf', 'dev/seed/bulletin-20260601.pdf', 'application/pdf', 1287654, 9001, now()),
    ((SELECT id FROM churches ORDER BY id LIMIT 1), 9010, 'bulletin-20260525.pdf', 'dev/seed/bulletin-20260525.pdf', 'application/pdf', 1190876, 9002, now())
ON CONFLICT (id) DO NOTHING;

-- ── 설교(sermons) ────────────────────────────────────────────────────────────
-- 9001 본문은 media:9001을 인라인 참조한다(차단 삭제 추적 LIKE 대상).
INSERT INTO sermons (church_id, id, title, preacher, series, scripture, content, video_url, audio_url,
                     preached_at, view_count, created_at, updated_at, created_by, updated_by, deleted_at, version) VALUES
    ((SELECT id FROM churches ORDER BY id LIMIT 1), 9001, '은혜로 사는 삶',   '김은혜', '은혜 시리즈', '에베소서 2:8-9',
        E'## 본문\n\n에베소서 2장 8-9절 말씀입니다.\n\n![설교 표지](media:9001)\n\n우리는 행위가 아니라 은혜로 구원을 받았습니다.',
        'https://youtu.be/dev-seed-001', NULL, DATE '2026-06-08', 152, TIMESTAMP '2026-06-08 12:00:00', TIMESTAMP '2026-06-08 12:00:00', 9001, 9001, NULL, 0),
    ((SELECT id FROM churches ORDER BY id LIMIT 1), 9002, '믿음의 경주',     '김은혜', '은혜 시리즈', '히브리서 12:1-2',
        E'믿음의 주요 온전케 하시는 예수를 바라보며 경주합시다.',
        'https://youtu.be/dev-seed-002', NULL, DATE '2026-06-01', 98,  TIMESTAMP '2026-06-01 12:00:00', TIMESTAMP '2026-06-01 12:00:00', 9001, 9001, NULL, 0),
    ((SELECT id FROM churches ORDER BY id LIMIT 1), 9003, '소망의 닻',       '이믿음', NULL, '히브리서 6:19',
        E'이 소망은 영혼의 닻 같아서 든든하고 견고합니다.',
        NULL, NULL, DATE '2026-05-25', 76,  TIMESTAMP '2026-05-25 12:00:00', TIMESTAMP '2026-05-25 12:00:00', 9002, 9002, NULL, 0),
    ((SELECT id FROM churches ORDER BY id LIMIT 1), 9004, '사랑의 빚',       '김은혜', NULL, '로마서 13:8',
        E'피차 사랑의 빚 외에는 아무에게든지 아무 빚도 지지 맙시다.',
        NULL, NULL, DATE '2026-05-18', 64,  TIMESTAMP '2026-05-18 12:00:00', TIMESTAMP '2026-05-18 12:00:00', 9001, 9001, NULL, 0),
    ((SELECT id FROM churches ORDER BY id LIMIT 1), 9005, '성령의 인도하심', '이믿음', NULL, '로마서 8:14',
        E'무릇 하나님의 영으로 인도함을 받는 사람은 하나님의 아들입니다.',
        NULL, NULL, DATE '2026-05-11', 51,  TIMESTAMP '2026-05-11 12:00:00', TIMESTAMP '2026-05-11 12:00:00', 9002, 9002, NULL, 0)
ON CONFLICT (id) DO NOTHING;

-- ── 공지(notices) ────────────────────────────────────────────────────────────
-- 9001은 상단고정(is_pinned). 9002는 마지막 수정자(updated_by)가 탈퇴회원(9008) → 표시 시 "(탈퇴한 사용자)".
INSERT INTO notices (church_id, id, title, content, is_pinned, view_count,
                     created_at, updated_at, created_by, updated_by, deleted_at, version) VALUES
    ((SELECT id FROM churches ORDER BY id LIMIT 1), 9001, '여름 수련회 안내',          E'7월 여름 수련회 일정과 신청 방법을 안내합니다.', TRUE,  210, TIMESTAMP '2026-06-10 09:00:00', TIMESTAMP '2026-06-10 09:00:00', 9001, 9001, NULL, 0),
    ((SELECT id FROM churches ORDER BY id LIMIT 1), 9002, '주차장 공사 안내',          E'본당 주차장 보수 공사로 6월 셋째 주 이용이 제한됩니다.', FALSE, 145, TIMESTAMP '2026-06-05 10:00:00', TIMESTAMP '2026-06-09 15:30:00', 9002, 9008, NULL, 1),
    ((SELECT id FROM churches ORDER BY id LIMIT 1), 9003, '교회 창립 50주년 감사예배', E'창립 50주년 감사예배에 모든 성도님을 초대합니다.', FALSE, 132, TIMESTAMP '2026-06-03 11:00:00', TIMESTAMP '2026-06-03 11:00:00', 9001, 9001, NULL, 0),
    ((SELECT id FROM churches ORDER BY id LIMIT 1), 9004, '성가대원 모집',             E'주일 성가대에서 함께 찬양할 대원을 모집합니다.', FALSE, 87,  TIMESTAMP '2026-05-28 14:00:00', TIMESTAMP '2026-05-28 14:00:00', 9002, 9002, NULL, 0),
    ((SELECT id FROM churches ORDER BY id LIMIT 1), 9005, '주보 PDF 다운로드 안내',    E'매주 주보를 홈페이지 주보 게시판에서 PDF로 받으실 수 있습니다.', FALSE, 60, TIMESTAMP '2026-05-20 09:30:00', TIMESTAMP '2026-05-20 09:30:00', 9001, 9001, NULL, 0)
ON CONFLICT (id) DO NOTHING;

-- ── 일정/행사(events) ────────────────────────────────────────────────────────
-- 9001 본문은 media:9007을 인라인 참조. all_day=true는 종일 일정(체육대회·추수감사).
INSERT INTO events (church_id, id, title, description, location, start_at, end_at, all_day,
                    created_at, updated_at, created_by, updated_by, deleted_at, version) VALUES
    ((SELECT id FROM churches ORDER BY id LIMIT 1), 9001, '여름 성경학교',   E'어린이 여름 성경학교를 개최합니다.\n\n![포스터](media:9007)', '본당 교육관',
        TIMESTAMP '2026-07-21 09:00:00', TIMESTAMP '2026-07-25 12:00:00', FALSE, now(), now(), 9001, 9001, NULL, 0),
    ((SELECT id FROM churches ORDER BY id LIMIT 1), 9002, '전교인 체육대회', E'온 성도가 함께하는 체육대회입니다.', '시민 체육공원',
        TIMESTAMP '2026-06-21 00:00:00', TIMESTAMP '2026-06-21 00:00:00', TRUE,  now(), now(), 9001, 9001, NULL, 0),
    ((SELECT id FROM churches ORDER BY id LIMIT 1), 9003, '수요 기도회',     E'수요 저녁 기도회로 함께 모입니다.', '본당',
        TIMESTAMP '2026-06-17 19:30:00', TIMESTAMP '2026-06-17 21:00:00', FALSE, now(), now(), 9002, 9002, NULL, 0),
    ((SELECT id FROM churches ORDER BY id LIMIT 1), 9004, '청년부 여름 MT',  E'청년부 1박 2일 수련회입니다.', '강원도 수련원',
        TIMESTAMP '2026-08-01 14:00:00', TIMESTAMP '2026-08-02 16:00:00', FALSE, now(), now(), 9002, 9002, NULL, 0),
    ((SELECT id FROM churches ORDER BY id LIMIT 1), 9005, '추수감사주일',    E'한 해의 추수를 감사하는 예배입니다.', '본당',
        TIMESTAMP '2026-11-15 00:00:00', TIMESTAMP '2026-11-15 00:00:00', TRUE,  now(), now(), 9001, 9001, NULL, 0),
    ((SELECT id FROM churches ORDER BY id LIMIT 1), 9006, '성탄 칸타타',     E'성탄 전야 칸타타 연주회에 초대합니다.', '본당',
        TIMESTAMP '2026-12-24 19:00:00', TIMESTAMP '2026-12-24 21:00:00', FALSE, now(), now(), 9001, 9001, NULL, 0)
ON CONFLICT (id) DO NOTHING;

-- ── 교구/부서(departments) ──────────────────────────────────────────────────
-- 자기참조 계층: 교육부(9001)가 하위 4개를 가진다 → 교육부 DELETE 시 409(살아있는 자식 차단) 시연.
-- 자기참조 FK 때문에 최상위(부모)를 먼저, 하위(자식)를 나중에 INSERT한다.
INSERT INTO departments (church_id, id, name, description, leader, parent_id, sort_order,
                         created_at, updated_at, created_by, updated_by, deleted_at, version) VALUES
    ((SELECT id FROM churches ORDER BY id LIMIT 1), 9001, '교육부', E'다음 세대 신앙 교육을 총괄합니다.', '김은혜 목사', NULL, 1, now(), now(), 9001, 9001, NULL, 0),
    ((SELECT id FROM churches ORDER BY id LIMIT 1), 9006, '선교부', E'국내외 선교와 전도를 담당합니다.', '이믿음 전도사', NULL, 2, now(), now(), 9001, 9001, NULL, 0),
    ((SELECT id FROM churches ORDER BY id LIMIT 1), 9007, '봉사부', E'교회 안팎의 섬김과 구제를 담당합니다.', '정충성 집사', NULL, 3, now(), now(), 9001, 9001, NULL, 0)
ON CONFLICT (id) DO NOTHING;
INSERT INTO departments (church_id, id, name, description, leader, parent_id, sort_order,
                         created_at, updated_at, created_by, updated_by, deleted_at, version) VALUES
    ((SELECT id FROM churches ORDER BY id LIMIT 1), 9002, '영아부',   E'0~4세 영유아 부서입니다.', '최사랑 권사', 9001, 1, now(), now(), 9001, 9001, NULL, 0),
    ((SELECT id FROM churches ORDER BY id LIMIT 1), 9003, '유년부',   E'초등 저학년 부서입니다.', NULL,          9001, 2, now(), now(), 9001, 9001, NULL, 0),
    ((SELECT id FROM churches ORDER BY id LIMIT 1), 9004, '청소년부', E'중·고등부 부서입니다.', NULL,            9001, 3, now(), now(), 9001, 9001, NULL, 0),
    ((SELECT id FROM churches ORDER BY id LIMIT 1), 9005, '청년부',   E'대학·청년 부서입니다.', '강청년',         9001, 4, now(), now(), 9001, 9001, NULL, 0)
ON CONFLICT (id) DO NOTHING;

-- ── 갤러리 앨범(gallery_albums) ─────────────────────────────────────────────
INSERT INTO gallery_albums (church_id, id, title, description,
                            created_at, updated_at, created_by, updated_by, deleted_at, version) VALUES
    ((SELECT id FROM churches ORDER BY id LIMIT 1), 9001, '2026 부활절 연합예배', E'부활절 연합예배 현장 사진입니다.', TIMESTAMP '2026-04-06 12:00:00', TIMESTAMP '2026-04-06 12:00:00', 9001, 9001, NULL, 0),
    ((SELECT id FROM churches ORDER BY id LIMIT 1), 9002, '전교인 수련회 2025',   E'2025년 가을 전교인 수련회 사진입니다.', TIMESTAMP '2025-10-12 12:00:00', TIMESTAMP '2025-10-12 12:00:00', 9002, 9002, NULL, 0),
    ((SELECT id FROM churches ORDER BY id LIMIT 1), 9003, '성탄 축하 행사',       E'성탄 축하 행사 사진 모음입니다.', TIMESTAMP '2025-12-25 12:00:00', TIMESTAMP '2025-12-25 12:00:00', 9001, 9001, NULL, 0)
ON CONFLICT (id) DO NOTHING;

-- ── 갤러리 사진(gallery_photos) ─────────────────────────────────────────────
-- media를 FK로 재사용(앨범별 정렬 sort_order). media 9002~9006이 여기서 참조됨.
INSERT INTO gallery_photos (church_id, id, album_id, media_id, caption, sort_order, created_at) VALUES
    ((SELECT id FROM churches ORDER BY id LIMIT 1), 9001, 9001, 9002, '부활절 연합예배 1', 1, now()),
    ((SELECT id FROM churches ORDER BY id LIMIT 1), 9002, 9001, 9003, '부활절 연합예배 2', 2, now()),
    ((SELECT id FROM churches ORDER BY id LIMIT 1), 9003, 9002, 9004, '수련회 단체사진',   1, now()),
    ((SELECT id FROM churches ORDER BY id LIMIT 1), 9004, 9002, 9005, '수련회 조별모임',   2, now()),
    ((SELECT id FROM churches ORDER BY id LIMIT 1), 9005, 9003, 9006, '성탄 트리 점등',    1, now())
ON CONFLICT (id) DO NOTHING;

-- ── 주보(bulletins) ──────────────────────────────────────────────────────────
-- 각 주보는 PDF media(9008~9010)를 FK로 참조. service_date DESC 정렬.
INSERT INTO bulletins (church_id, id, title, service_date, media_id,
                       created_at, updated_at, created_by, updated_by, deleted_at, version) VALUES
    ((SELECT id FROM churches ORDER BY id LIMIT 1), 9001, '2026-06-08 주보', DATE '2026-06-08', 9008, now(), now(), 9001, 9001, NULL, 0),
    ((SELECT id FROM churches ORDER BY id LIMIT 1), 9002, '2026-06-01 주보', DATE '2026-06-01', 9009, now(), now(), 9001, 9001, NULL, 0),
    ((SELECT id FROM churches ORDER BY id LIMIT 1), 9003, '2026-05-25 주보', DATE '2026-05-25', 9010, now(), now(), 9002, 9002, NULL, 0)
ON CONFLICT (id) DO NOTHING;

-- ── 콘텐츠-태그 연결(content_tags) ──────────────────────────────────────────
-- resource_type은 ContentResourceType enum 이름(SERMON/NOTICE/EVENT/GALLERY_ALBUM) 그대로, resource_id는 9000+.
INSERT INTO content_tags (church_id, tag_id, resource_type, resource_id) VALUES
    ((SELECT id FROM churches ORDER BY id LIMIT 1), 9001, 'SERMON', 9001),
    ((SELECT id FROM churches ORDER BY id LIMIT 1), 9001, 'SERMON', 9002),
    ((SELECT id FROM churches ORDER BY id LIMIT 1), 9001, 'SERMON', 9004),
    ((SELECT id FROM churches ORDER BY id LIMIT 1), 9008, 'EVENT', 9001),
    ((SELECT id FROM churches ORDER BY id LIMIT 1), 9004, 'EVENT', 9004),
    ((SELECT id FROM churches ORDER BY id LIMIT 1), 9007, 'NOTICE', 9003),
    ((SELECT id FROM churches ORDER BY id LIMIT 1), 9007, 'GALLERY_ALBUM', 9001),
    ((SELECT id FROM churches ORDER BY id LIMIT 1), 9004, 'GALLERY_ALBUM', 9002)
ON CONFLICT DO NOTHING;

-- ── 통독 챌린지 (V13) ────────────────────────────────────────────────────────
-- 진행 중 2건: 전교인 전체(30일차) + 학생부 신약(10일차). 참여·로그는 앱에서 만들며 시드하지 않는다.
INSERT INTO bible_challenges (church_id, id, title, description, start_book, end_book, start_date, target_days,
                              created_at, created_by, version) VALUES
    ((SELECT id FROM churches ORDER BY id LIMIT 1), 9000, '2026 전교인 100일 통독', '창세기부터 요한계시록까지 함께 읽는 100일 여정입니다.',
     1, 66, CURRENT_DATE - 30, 100, now(), 9001, 0),
    ((SELECT id FROM churches ORDER BY id LIMIT 1), 9001, '학생부 신약 60일 챌린지', '여름방학 동안 신약 27권을 함께 읽어요.',
     40, 66, CURRENT_DATE - 10, 60, now(), 9001, 0)
ON CONFLICT (id) DO NOTHING;

-- ── 시퀀스 동기화 (9000블록 예약) ────────────────────────────────────────────
-- 각 IDENTITY 시퀀스를 최소 9999로 끌어올려 9000~9999 전체를 시드 전용으로 예약한다.
-- → 앱이 만드는 새 행(회원 가입·관리자 콘텐츠)은 10000+에서 시작하므로, 시드 블록을 절대 잠식하지 않는다.
-- (구버전은 setval(MAX(id))라 시드 확장 시 앱이 이미 쓴 9000+ id와 충돌했음. GREATEST로 영구 차단.)
-- GREATEST는 NULL(빈 테이블)을 무시하므로 MAX가 NULL이어도 9999로 안전하게 설정된다.
SELECT setval(pg_get_serial_sequence('positions',       'id'), GREATEST((SELECT MAX(id) FROM positions),      9999));
SELECT setval(pg_get_serial_sequence('members',         'id'), GREATEST((SELECT MAX(id) FROM members),        9999));
SELECT setval(pg_get_serial_sequence('tags',            'id'), GREATEST((SELECT MAX(id) FROM tags),           9999));
SELECT setval(pg_get_serial_sequence('media',           'id'), GREATEST((SELECT MAX(id) FROM media),          9999));
SELECT setval(pg_get_serial_sequence('sermons',         'id'), GREATEST((SELECT MAX(id) FROM sermons),        9999));
SELECT setval(pg_get_serial_sequence('notices',         'id'), GREATEST((SELECT MAX(id) FROM notices),        9999));
SELECT setval(pg_get_serial_sequence('events',          'id'), GREATEST((SELECT MAX(id) FROM events),         9999));
SELECT setval(pg_get_serial_sequence('departments',     'id'), GREATEST((SELECT MAX(id) FROM departments),    9999));
SELECT setval(pg_get_serial_sequence('gallery_albums',  'id'), GREATEST((SELECT MAX(id) FROM gallery_albums),  9999));
SELECT setval(pg_get_serial_sequence('gallery_photos',  'id'), GREATEST((SELECT MAX(id) FROM gallery_photos),  9999));
SELECT setval(pg_get_serial_sequence('bulletins',       'id'), GREATEST((SELECT MAX(id) FROM bulletins),       9999));
SELECT setval(pg_get_serial_sequence('bible_challenges',        'id'), GREATEST((SELECT MAX(id) FROM bible_challenges),        9999));
SELECT setval(pg_get_serial_sequence('challenge_participations','id'), GREATEST((SELECT MAX(id) FROM challenge_participations), 9999));
SELECT setval(pg_get_serial_sequence('challenge_reading_logs',  'id'), GREATEST((SELECT MAX(id) FROM challenge_reading_logs),   9999));
