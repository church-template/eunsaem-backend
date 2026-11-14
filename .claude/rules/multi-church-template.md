# Multi-Tenancy Rules (교회 N곳 공유)

앱 1개·PostgreSQL 1개·Redis 1개가 모든 교회를 서비스한다(#86). 격리는 `church_id` 컬럼 + Hibernate `@TenantId`이고 **DB는 강제하지 않는다**(RLS·스키마 분리 없음) — 아래 규칙이 곧 격리다. See `docs/church-backend-spec.md` §2, §8, §10–§12, 설계 `docs/superpowers/specs/2026-11-12-multi-tenancy-design.md`.

## 요청의 교회

- 교회는 API 경로 접두사 `/church/{slug}`로 정한다(`churches.slug`). `TenantFilter`(`global/tenant`, 보안 필터 체인보다 앞)가 교회를 찾아 `TenantContext`에 넣고 접두사를 뗀다 → `SecurityConfig` 매처·컨트롤러 매핑은 `/api/**` 그대로 쓴다. 컨트롤러에 `/church/...` 경로를 만들지 않는다.
- 없는 slug·형식이 틀린 slug·`/church/x/<api 아님>` → `404 RESOURCE_NOT_FOUND`. `/actuator/health`·Swagger·`/error`는 교회 없이 통과.
- `TENANT_DEFAULT_SLUG`(`tenant.default-slug`)가 있으면 접두사 없는 `/api/**`를 그 교회로 처리한다(로컬·테스트·전환 기간 전용, 요청마다 INFO 로그). 비어 있으면 404, `churches`에 없는 slug면 기동 실패.

## 데이터 규칙

- **모든 새 테이블은 `church_id`를 갖는다** (`BIGINT NOT NULL REFERENCES churches(id)`). 예외는 전 교회 공용 카탈로그(`permissions`)와 양쪽 부모가 이미 교회에 묶인 순수 조인 테이블(`role_permissions`, `member_roles`)뿐이다.
- 엔티티는 `BaseTimeEntity`(→ `BaseEntity`)를 상속해 `@TenantId church_id`를 물려받는다. 기반 클래스가 없으면 `ContentTag`처럼 직접 선언한다. 서비스 코드에서 `church_id`를 직접 세팅하지 않는다(Hibernate가 채운다).
- **유니크 제약은 `(church_id, …)`** — `roles`·`positions`·`tags`의 `(church_id, name)`, `members`의 `(church_id, phone) WHERE deleted_at IS NULL`. 같은 전화번호가 교회마다 따로 가입할 수 있다. 엔티티 선언도 `@Table(uniqueConstraints = …)`로 맞춘다(`unique = true` 금지). 목록 인덱스는 `church_id`를 선두에 둔다.
- **새 네이티브 쿼리는 반드시 `church_id = :churchId` 조건을 가진다.** `@TenantId`는 네이티브 SQL을 걸러 주지 않는다. 페이지 조회면 count 쿼리에도 넣는다. `NativeQueryChurchIdTest`가 강제하며 예외는 데이터를 읽지 않는 `pg_advisory_xact_lock`뿐이다.
- **`churches`는 JPA 엔티티로 만들지 않는다.** `ChurchResolver`가 `JdbcClient`로만 읽는다(교회를 찾는 시점엔 테넌트가 없어 JPA로 읽으면 fail-closed에 걸린다).

## 테넌트 컨텍스트

- **기본 테넌트·전체를 보는 루트 테넌트는 없다. 만들지 않는다.** 교회가 정해지지 않은 DB 접근은 예외다(fail-closed).
- **요청 밖 코드(스케줄러 등)는 `TenantContext.runAs(churchId, …)`/`callAs`를 트랜잭션 바깥에서 감싼다.** 테넌트는 세션(트랜잭션)을 열 때 고정되므로, 열린 트랜잭션 안에서 바꿔도 반영되지 않는다.
- `TenantContext.set`/`clear`는 테스트 지원 전용이다(메인 코드 사용은 ArchUnit 규칙이 막는다).
- 격리 테스트는 `@Transactional` 없이 실제 HTTP 경로(MockMvc)로 돌린다.

## 교회별 값 · 환경변수

- 교회별 값(이름·slug·프론트 origin)은 `churches` 행이다. 코드에 박지 않고 `.env`에도 두지 않는다. CORS는 요청마다 그 교회의 `frontend_origin` 하나만 허용한다.
- `.env`는 플랫폼 공용 값만 담는다(DB/Redis, `JWT_SECRET` 하나, `FILE_*` 등). `CHURCH_NAME`·`CHURCH_SLUG`·`CORS_ALLOWED_ORIGIN`은 V18이 첫 교회 행을 만들 때만 읽지만 **지우지 않는다**.
- JWT access 토큰은 `cid`(교회 id)를 담고, 요청 교회와 다르면 무효다 — see [[rbac-authorization]]. Token expiry comes from `JWT_ACCESS_EXPIRY` (3600s) / `JWT_REFRESH_EXPIRY` (1209600s).
- Redis 키에 교회를 넣는다: 메인 캐시 `main::{churchId}::`, 조회수 버퍼 `view:{churchId}:{namespace}:{id}`. 새 캐시·버퍼도 같은 규칙.
- Commit `.env.example` only; `.env` stays git-ignored. Bodies store `media:{id}`, not URLs — see [[media-library]].

## Deliberate non-features (don't add them)

- **No SMTP / email verification.** Identity is confirmed by admin granting the `MEMBER` role — see [[rbac-authorization]].
- **No complex password policy** (elderly users): BCrypt + minimum length (~8) only; no forced special-char/case rules. (대신 로그인 실패 횟수 제한이 있다.)
- Required consent at signup: `termsAgreed` **and** `privacyAgreed` must both be true, else reject; record `agreed_at`. Re-consent is handled by flag reset (no separate terms-version table).
- 교회 등록 API·관리 화면, 교회를 가로지르는 계정·토큰, 운영자 상시 계정 — 만들지 않는다.

## File storage is abstracted

Upload writes to local disk via a `FileStorage` interface (`global/storage`), first impl `LocalFileStorage`, dir = `FILE_UPLOAD_DIR` mounted on a Docker named volume. 새 업로드 키는 `churches/{churchId}/yyyy/MM/{uuid}.ext`이고, 전환 전 파일은 옛 키(`yyyy/MM/…`)를 그대로 쓴다 — `stored_path`는 불투명 키로만 다룬다. Keep call sites behind the interface so a future move to S3/OCI Object Storage is an impl swap, not a domain change. `FILE_BASE_URL`은 바인딩만 되고 쓰이지 않는 죽은 설정이다.

## New church = script, zero code edits

새 교회는 운영 서버에서 `scripts/new-church.sh`를 실행해 등록한다(교회 1행 + 기본 역할 4종 + 교회 대표자 `SUPER_ADMIN`, 원자적). 코드 수정·재배포·DNS 작업이 없어야 한다 — 교회마다 코드를 고쳐야 하는 변경은 이 규칙 위반이다. 절차는 `docs/setup-new-church.md`.

- **`MEMBER`에 권한을 추가하는 마이그레이션은 `scripts/new-church.sql`의 MEMBER 권한 목록도 함께 고친다**(`NewChurchScriptTest`가 어긋남을 잡는다). `SUPER_ADMIN`·`ADMIN`은 스크립트가 전 권한을 주므로 손댈 것이 없다.
- 권한을 추가하는 마이그레이션의 기존 패턴(`WHERE r.name IN ('SUPER_ADMIN','ADMIN')`)은 모든 교회의 역할에 한 번에 적용되므로 그대로 쓴다.
