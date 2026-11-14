-- 멀티테넌시(#86): 19개 테이블에 church_id를 추가하고, 유니크 제약과 목록 인덱스를 교회 단위로 바꾼다.
-- 기존 행은 전부 첫 교회(V18이 만든 id=1)가 된다. permissions(전 교회 공용 카탈로그)와
-- 순수 조인 테이블(role_permissions, member_roles)은 대상이 아니다.
--
-- DEFAULT 1은 일부러 남긴다. 문제가 생겨 이전 이미지로 되돌렸을 때, church_id를 모르는 옛 코드의 INSERT가
-- 첫 교회로 들어가게 하려는 것이다(이미지 교체만으로 되돌리기, 데이터 유실 없음).
-- 전환이 끝나면 V20이 DEFAULT를 제거한다.

DO $$
BEGIN
    IF (SELECT count(*) FROM churches) <> 1 OR (SELECT min(id) FROM churches) <> 1 THEN
        RAISE EXCEPTION 'churches 첫 행이 id=1 하나가 아닙니다';
    END IF;
END $$;

ALTER TABLE positions ADD COLUMN church_id BIGINT NOT NULL DEFAULT 1 REFERENCES churches (id);
ALTER TABLE roles ADD COLUMN church_id BIGINT NOT NULL DEFAULT 1 REFERENCES churches (id);
ALTER TABLE members ADD COLUMN church_id BIGINT NOT NULL DEFAULT 1 REFERENCES churches (id);
ALTER TABLE tags ADD COLUMN church_id BIGINT NOT NULL DEFAULT 1 REFERENCES churches (id);
ALTER TABLE content_tags ADD COLUMN church_id BIGINT NOT NULL DEFAULT 1 REFERENCES churches (id);
ALTER TABLE media ADD COLUMN church_id BIGINT NOT NULL DEFAULT 1 REFERENCES churches (id);
ALTER TABLE sermons ADD COLUMN church_id BIGINT NOT NULL DEFAULT 1 REFERENCES churches (id);
ALTER TABLE notices ADD COLUMN church_id BIGINT NOT NULL DEFAULT 1 REFERENCES churches (id);
ALTER TABLE events ADD COLUMN church_id BIGINT NOT NULL DEFAULT 1 REFERENCES churches (id);
ALTER TABLE departments ADD COLUMN church_id BIGINT NOT NULL DEFAULT 1 REFERENCES churches (id);
ALTER TABLE gallery_albums ADD COLUMN church_id BIGINT NOT NULL DEFAULT 1 REFERENCES churches (id);
ALTER TABLE gallery_photos ADD COLUMN church_id BIGINT NOT NULL DEFAULT 1 REFERENCES churches (id);
ALTER TABLE bulletins ADD COLUMN church_id BIGINT NOT NULL DEFAULT 1 REFERENCES churches (id);
ALTER TABLE bible_challenges ADD COLUMN church_id BIGINT NOT NULL DEFAULT 1 REFERENCES churches (id);
ALTER TABLE challenge_participations ADD COLUMN church_id BIGINT NOT NULL DEFAULT 1 REFERENCES churches (id);
ALTER TABLE challenge_reading_logs ADD COLUMN church_id BIGINT NOT NULL DEFAULT 1 REFERENCES churches (id);
ALTER TABLE inquiries ADD COLUMN church_id BIGINT NOT NULL DEFAULT 1 REFERENCES churches (id);
ALTER TABLE vehicle_runs ADD COLUMN church_id BIGINT NOT NULL DEFAULT 1 REFERENCES churches (id);
ALTER TABLE vehicle_requests ADD COLUMN church_id BIGINT NOT NULL DEFAULT 1 REFERENCES churches (id);

-- ── 유니크 제약: 전역 → 교회 단위 ──────────────────────────────────────────────
-- 역할·직분·태그 이름과 활성 전화번호는 교회 안에서만 유일하면 된다(같은 번호로 교회마다 따로 가입 가능).
-- 유지: uq_members_uuid, uq_media_stored_path, uq_permissions_name, 참여·신청 부분 유니크(부모 id가 전역 유일).
ALTER TABLE positions DROP CONSTRAINT uq_positions_name, ADD CONSTRAINT uq_positions_church_name UNIQUE (church_id, name);
ALTER TABLE roles     DROP CONSTRAINT uq_roles_name,     ADD CONSTRAINT uq_roles_church_name     UNIQUE (church_id, name);
ALTER TABLE tags      DROP CONSTRAINT tags_name_key,     ADD CONSTRAINT uq_tags_church_name      UNIQUE (church_id, name);
DROP INDEX uq_members_phone_active;
CREATE UNIQUE INDEX uq_members_church_phone_active ON members (church_id, phone) WHERE deleted_at IS NULL;

-- ── 목록 인덱스: church_id를 선두로 재생성(부분 조건 유지) ─────────────────────
-- 부모 id로만 찾는 인덱스(gallery_photos, bulletins.media_id, content_tags, member_roles, 참여·신청)는
-- 부모 id가 전 교회에 걸쳐 유일하므로 그대로 둔다.
DROP INDEX idx_positions_sort_order;
CREATE INDEX idx_positions_sort_order ON positions (church_id, sort_order);
DROP INDEX idx_roles_priority;
CREATE INDEX idx_roles_priority ON roles (church_id, priority);
DROP INDEX idx_media_mime_created;
CREATE INDEX idx_media_mime_created ON media (church_id, mime_type, created_at DESC);
DROP INDEX idx_sermons_preached_at;
CREATE INDEX idx_sermons_preached_at ON sermons (church_id, preached_at DESC) WHERE deleted_at IS NULL;
DROP INDEX idx_notices_pinned_created;
CREATE INDEX idx_notices_pinned_created ON notices (church_id, is_pinned DESC, created_at DESC) WHERE deleted_at IS NULL;
DROP INDEX idx_events_start_at;
CREATE INDEX idx_events_start_at ON events (church_id, start_at) WHERE deleted_at IS NULL;
DROP INDEX idx_departments_sort_order;
CREATE INDEX idx_departments_sort_order ON departments (church_id, sort_order) WHERE deleted_at IS NULL;
DROP INDEX idx_gallery_albums_created_at;
CREATE INDEX idx_gallery_albums_created_at ON gallery_albums (church_id, created_at DESC) WHERE deleted_at IS NULL;
DROP INDEX idx_bulletins_service_date;
CREATE INDEX idx_bulletins_service_date ON bulletins (church_id, service_date DESC) WHERE deleted_at IS NULL;
DROP INDEX idx_bible_challenges_start_date;
CREATE INDEX idx_bible_challenges_start_date ON bible_challenges (church_id, start_date DESC) WHERE deleted_at IS NULL;
DROP INDEX idx_inquiries_created;
CREATE INDEX idx_inquiries_created ON inquiries (church_id, created_at DESC) WHERE deleted_at IS NULL;
DROP INDEX idx_inquiries_pending;
CREATE INDEX idx_inquiries_pending ON inquiries (church_id, created_at DESC) WHERE deleted_at IS NULL AND completed_at IS NULL;
DROP INDEX idx_vehicle_runs_departs_at;
CREATE INDEX idx_vehicle_runs_departs_at ON vehicle_runs (church_id, departs_at) WHERE deleted_at IS NULL;
