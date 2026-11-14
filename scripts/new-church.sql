-- 새 교회 등록(#86). 직접 실행하지 말고 scripts/new-church.sh로 실행한다(값 검증·확인·비밀번호 입력을 맡는다).
--
-- 만드는 것: churches 1행, 기본 역할 4종, 역할별 권한 연결, 교회 대표자 회원 1명(SUPER_ADMIN).
-- 전부 하나의 DO 블록 안이라 원자적이다 — 중간에 실패하면 아무것도 남지 않는다.
--
-- 값은 psql 변수로 받는다: name, slug, origin, phone, owner_name, pw.
-- DO 블록 안에서는 psql 변수가 치환되지 않으므로 세션 설정(nc.*)으로 넘긴다.
--
-- ★ 권한을 추가하는 마이그레이션을 쓰면 이 파일의 MEMBER 권한 목록도 같이 고친다
--   (NewChurchScriptTest가 첫 교회의 역할별 권한과 비교해 어긋남을 잡는다).

CREATE EXTENSION IF NOT EXISTS pgcrypto;

SELECT set_config('nc.name', :'name', false),
       set_config('nc.slug', :'slug', false),
       set_config('nc.origin', :'origin', false),
       set_config('nc.phone', :'phone', false),
       set_config('nc.owner_name', :'owner_name', false),
       set_config('nc.pw', :'pw', false);

DO $$
DECLARE
    v_name   TEXT := current_setting('nc.name');
    v_slug   TEXT := current_setting('nc.slug');
    v_origin TEXT := current_setting('nc.origin');
    v_phone  TEXT := regexp_replace(current_setting('nc.phone'), '\D', '', 'g');  -- 숫자만 정규화 저장
    v_owner  TEXT := current_setting('nc.owner_name');
    v_pw     TEXT := current_setting('nc.pw');
    v_church BIGINT;
    v_member BIGINT;
BEGIN
    IF v_slug !~ '^[a-z0-9-]{1,40}$' THEN
        RAISE EXCEPTION 'slug 형식이 올바르지 않습니다(소문자·숫자·하이픈, 1~40자): %', v_slug;
    END IF;
    IF v_name = '' OR v_owner = '' OR v_origin = '' OR v_origin = '*' THEN
        RAISE EXCEPTION '교회 이름·대표자 이름·프론트 주소는 비울 수 없고, 프론트 주소는 *일 수 없습니다';
    END IF;
    IF v_phone = '' THEN
        RAISE EXCEPTION '대표자 전화번호에 숫자가 없습니다';
    END IF;
    IF length(v_pw) < 8 THEN
        RAISE EXCEPTION '임시 비밀번호는 8자 이상이어야 합니다';
    END IF;

    INSERT INTO churches (name, slug, frontend_origin)
    VALUES (v_name, v_slug, v_origin)
    RETURNING id INTO v_church;

    -- 기본 역할 4종 (V2 시드와 같은 값)
    INSERT INTO roles (name, priority, is_system, description, created_at, church_id) VALUES
        ('SUPER_ADMIN', 1000, TRUE,  '최고 관리자', now(), v_church),
        ('ADMIN',        900, TRUE,  '관리자',      now(), v_church),
        ('MEMBER',       100, FALSE, '교인',        now(), v_church),
        ('USER',           0, TRUE,  '일반 사용자', now(), v_church);

    -- SUPER_ADMIN·ADMIN = 전 권한
    INSERT INTO role_permissions (role_id, permission_id)
    SELECT r.id, p.id
    FROM roles r CROSS JOIN permissions p
    WHERE r.church_id = v_church AND r.name IN ('SUPER_ADMIN', 'ADMIN');

    -- MEMBER(승인 교인) = 회원전용 열람·참여 권한
    INSERT INTO role_permissions (role_id, permission_id)
    SELECT r.id, p.id
    FROM roles r
    JOIN permissions p ON p.name IN ('GALLERY_VIEW', 'SERMON_VIEW', 'CHALLENGE_PARTICIPATE', 'VEHICLE_APPLY')
    WHERE r.church_id = v_church AND r.name = 'MEMBER';

    -- 교회 대표자 = 그 교회의 SUPER_ADMIN. 비밀번호는 BCrypt cost 10(앱의 BCryptPasswordEncoder와 같다).
    INSERT INTO members (uuid, phone, name, password, terms_agreed, privacy_agreed, agreed_at, created_at, church_id)
    VALUES (gen_random_uuid(), v_phone, v_owner, crypt(v_pw, gen_salt('bf', 10)), TRUE, TRUE, now(), now(), v_church)
    RETURNING id INTO v_member;

    INSERT INTO member_roles (member_id, role_id)
    SELECT v_member, r.id
    FROM roles r
    WHERE r.church_id = v_church AND r.name IN ('SUPER_ADMIN', 'USER');
END $$;

-- 세션에 남은 값(임시 비밀번호 포함)을 지운다.
SELECT set_config('nc.pw', '', false), set_config('nc.phone', '', false);
