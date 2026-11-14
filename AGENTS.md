# AGENTS.md — AI 에이전트 진입점

교회 홈페이지용 **공유형 멀티테넌트 백엔드**(Spring Boot 4.0.x · Java 21 · PostgreSQL 16 · Redis 7 · Flyway).
앱·DB·Redis 하나를 모든 교회가 공유한다(#86) — 격리는 `church_id` 컬럼 + Hibernate `@TenantId`,
요청의 교회는 API 경로 접두사 `/c/{slug}`. 교회별 값은 `churches` 테이블 행이고 `.env`는 플랫폼 공용 값만 담는다.
전체 설계 정본: `docs/church-backend-spec.md`(한국어).

작업 언어는 **한국어**: 커밋 `<type> : <설명>`(콜론 앞 공백, feat/fix/refactor/docs/test/chore/perf/ci),
커밋·푸시는 요청받았을 때만.

## 시작하기

| 하려는 일 | 읽을 문서 |
|---|---|
| 로컬에서 앱 실행·개발 | `docs/setup-dev.md` |
| 새 교회 등록 | `docs/setup-new-church.md` |
| 도메인 기능 구현 | `docs/church-backend-spec.md` 해당 § + 아래 규칙 파일 |
| 운영 DB 점검 | `docs/db-remote-access.md` |

## 핵심 불변식 (요약 — 상세는 `.claude/rules/`의 각 파일이 정본)

- **멀티테넌시**: 19개 테이블에 `church_id`(`BaseTimeEntity`의 `@TenantId`, `ContentTag`는 직접 선언;
  `permissions`·`role_permissions`·`member_roles`는 없음). 새 테이블은 `church_id` 필수, 유니크는 `(church_id, …)`.
  **네이티브 쿼리는 자동 필터되지 않는다 — `church_id = :churchId` 필수**(`NativeQueryChurchIdTest`).
  교회 없는 DB 접근은 예외(fail-closed, 기본·루트 테넌트 금지). 요청 밖 코드는 `TenantContext.runAs`를
  트랜잭션 **바깥**에서. `churches`는 JPA 엔티티 금지(`JdbcClient`). → `multi-church-template.md`
- **RBAC**: 직분(position)과 권한은 독립 축. 인가는 항상 권한 단위
  `@PreAuthorize("hasAuthority('SERMON_WRITE')")` — role·직분으로 검사 금지. 역할 priority 계층 가드
  (부여는 strictly-below, 역할 수정은 at-or-below, 역할의 priority를 정할 때(생성·변경)는 strictly-below, 관리자가 다른 회원을 수정·직분 지정·비밀번호 초기화할 때는
  대상 최고 priority ≤ 요청자 maxPriority). 역할·직분과 "마지막 SUPER_ADMIN" 집계는 교회 단위. → `rbac-authorization.md`
- **경로 인가**: `/api/admin/**` 인증+메서드 권한 · `/api/gallery/**`·`/api/bible-challenges/**`·`/api/sermons/**`·`/api/vehicle-runs/**`
  회원전용(각 `GALLERY_VIEW`/`CHALLENGE_PARTICIPATE`/`SERMON_VIEW`/`VEHICLE_APPLY`) · 나머지 public(`/api/main` 포함, 의도적).
  교회 접두사 `/c/{slug}`는 `TenantFilter`가 매처보다 먼저 떼므로 매처·컨트롤러 경로는 `/api/**` 그대로.
  정본: `SecurityConfig` 매처 체인. → `rbac-authorization.md`
- **JWT**: `sub`=member uuid(BIGINT id 금지), payload는 평탄화된 permissions+maxPriority+`cid`(교회 id).
  `cid`가 요청 교회와 다르거나 없으면 401 `INVALID_TOKEN`. `JWT_SECRET`은 플랫폼 전체에 하나. → `rbac-authorization.md`
- **영속성**: soft delete(`deleted_at`) 전면 + 모든 목록 인덱스는 partial(`WHERE deleted_at IS NULL`),
  전화번호 유니크는 `(church_id, phone)` partial, 낙관락 `@Version`(충돌 409), 작성자 표시는 `updated_by`. → `persistence-conventions.md`
- **API**: RFC 7807 단일 에러 envelope + 정해진 errorCode 표, 목록은 `{content, page{...}}` 페이지 envelope,
  본문 markdown은 raw 저장. → `api-conventions.md`
- **미디어**: 이미지·PDF는 중앙 `media` 테이블 하나, 본문 참조는 `media:{id}` 문자열(URL 금지),
  삭제는 참조 있으면 409 차단. → `media-library.md`
- **SB4**: web 스타터는 `spring-boot-starter-webmvc`, 테스트는 모듈별 `*-test`. → `spring-boot-4.md`

## 명령어

```bash
./gradlew build     # 컴파일+전체 테스트(Testcontainers — Docker 필요)+jar
./gradlew test --tests 'com.elipair.church.SomeTest.someMethod'
docker compose up -d --build    # 로컬 전체 기동(상세·시드·검증: docs/setup-dev.md)
```

## 하지 말 것

- `version.yml`·`build.gradle`의 `version`·`CHANGELOG.*` 수동 편집(자동화 소유 — `versioning-ci.md`)
- `.github/workflows/PROJECT-COMMON-*`·`.github/scripts/*` 수정(템플릿 관리)
- SMTP·복잡한 비밀번호 정책·교회 등록 API 추가(의도된 비기능 — `multi-church-template.md`)
- `church_id` 없는 새 테이블·네이티브 쿼리, 기본·루트 테넌트, 메인 코드의 `TenantContext.set/clear`(`multi-church-template.md`)
- 교회별 실값(도메인 등) 하드코딩 — 교회별 값은 `churches` 행, 시크릿은 `.env`
