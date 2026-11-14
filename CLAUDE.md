# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A **shared multi-tenant backend** for church homepage sites (#86). One app, one PostgreSQL and one Redis serve every church; isolation is a `church_id` column + Hibernate `@TenantId`, and the request's church comes from the API path prefix `/church/{slug}`. Per-church values (name, slug, frontend origin) are rows in the `churches` table; `.env` holds only platform-wide values (DB/Redis credentials, the single JWT secret). Adding a church = run `scripts/new-church.sh` on the server — no code edit, no redeploy. There is no DB-level isolation (no RLS/schemas), so isolation is a **code concern**: follow `.claude/rules/multi-church-template.md`.

The domains are largely implemented (15 packages under `domain/`). The full intended design lives in **`docs/church-backend-spec.md`** (Korean) — treat it as the authoritative blueprint. Read the relevant spec section before building or changing a feature.

Working language is **Korean**: commits, the spec, issue templates, and CodeRabbit reviews (`ko-KR`) are all in Korean.

## Project rules (`.claude/rules/`)

The sections below are the map; these files are the enforceable detail. **Consult the relevant one before implementing in that area:**

- `rbac-authorization.md` — per-permission `@PreAuthorize`, priority hierarchy guards, self-role / last-SUPER_ADMIN protection, JWT shape, path authz(SecurityConfig 매처 체인 정본).
- `persistence-conventions.md` — `BaseEntity`(+`church_id`), soft delete + partial indexes, per-church uniques, `@Version`, `updated_by` author display, uuid/id immutability.
- `api-conventions.md` — RFC 7807 error envelope + error codes, pagination envelope, raw-markdown body storage.
- `media-library.md` — central media table, `media:{id}` refs, LIKE+FK reference tracking, blocking delete.
- `multi-church-template.md` — multi-tenancy rules: `church_id` on every new table, native-query `church_id` condition, `TenantContext.runAs`, no default tenant, new church via script, deliberate non-features (no SMTP), `FileStorage` abstraction.
- `spring-boot-4.md` — SB4 starter coordinates and spec'd-but-unwired dependencies.
- `versioning-ci.md` — automation-owned version/changelog files; don't hand-edit.

## Build & test

Gradle wrapper, Java 21 toolchain.

```bash
./gradlew build            # compile + test + assemble jar
./gradlew bootRun          # run the app locally
./gradlew test             # run all tests
./gradlew clean            # remove build/

# single test class / method
./gradlew test --tests 'com.elipair.church.ChurchBackendApplicationTests'
./gradlew test --tests 'com.elipair.church.SomeTest.someMethod'
```

로컬 셋업 절차·목데이터·함정 목록은 `docs/setup-dev.md`, 새 교회 등록은 `docs/setup-new-church.md`가 정본.
비-Claude 에이전트용 진입점으로 `AGENTS.md`가 있다(이 파일과 사실이 어긋나면 안 됨).

### Spring Boot 4 notes (important)

The stack is **Spring Boot 4.0.6** on Java 21. SB4 differs from the more common SB3 in ways that bite when adding dependencies:

- The web starter is **`spring-boot-starter-webmvc`** (renamed from `spring-boot-starter-web` in SB4).
- Test dependencies use module-specific `*-test` starters (e.g. `spring-boot-starter-data-jpa-test`, `spring-boot-starter-webmvc-test`), not a single `spring-boot-starter-test`.
- springdoc-openapi (Swagger UI `/docs/swagger-ui.html`), Docker/`docker-compose`, the `${ENV}`-reading `application.yml`, and `.env.example` are all wired up. When adding any **new** dependency, verify the SB4-compatible coordinate against current docs (use Context7 for Spring Boot docs).

## Architecture (the parts that span files)

Package-by-feature under `com.elipair.church` (per spec §7):

- `global/` — cross-cutting: `config/` (Security, Redis, Swagger, Jpa), `security/` (JWT issue/verify filter, authorization, **priority-based hierarchy checks**), `exception/` (RFC 7807 global handler), `common/` (`BaseEntity`, page wrapper), `storage/` (`FileStorage` interface + `LocalFileStorage`), `viewcount/` (조회수 버퍼 → 주기 flush), `tenant/` (`TenantFilter`·`TenantContext`·`ChurchResolver` — 멀티테넌시).
- `domain/` — `auth`, `member`, `role`, `position`, `sermon`, `notice`, `event`, `department`, `tag`, `media`, `gallery`, `bulletin`, `challenge`(통독), `inquiry`(문의), `vehicle`(차량운행), `main`(통합 조회). Each splits into `controller/service/repository/entity/dto`; keep simple domains flat rather than over-nesting. Dependency direction is **domain → global, one-way**.

Cross-cutting conventions that every domain must follow consistently — get these right once in `global` and inherit:

- **Multi-tenancy (`church_id` + `@TenantId`)**: 19 tables carry `church_id` (declared in `BaseTimeEntity`, and directly in `ContentTag`); `permissions`/`role_permissions`/`member_roles` do not. `TenantFilter` runs **before** the security chain, resolves `/church/{slug}` to a church and strips the prefix (unknown slug → 404); un-prefixed `/api/**` works only when `TENANT_DEFAULT_SLUG` is set (local/tests/cut-over). **Fail-closed**: a DB access with no church throws — there is no default/root tenant. Code outside a request (schedulers) wraps work in `TenantContext.runAs(churchId, …)` **outside** the transaction. **Native queries are not filtered** — each must carry `church_id = :churchId` (`NativeQueryChurchIdTest` enforces it). Uniques are per church (`(church_id, name)`, `(church_id, phone)`), and new tables need `church_id`. `churches` is never a JPA entity (`JdbcClient` only). Redis cache/view-count keys and new upload keys carry the church id.
- **RBAC is Discord-style and per-permission.** Position (직분: 목사/장로…) and Role/Permission are *independent* axes — a high position grants no authority. Authorization checks a flattened **permission** (`@PreAuthorize("hasAuthority('SERMON_WRITE')")`); roles are just bundles of permissions. Roles carry a numeric `priority` with two distinct guards: **granting/revoking a role to a member** requires the target priority to be **strictly below** your `maxPriority` (same level **blocked** — only a higher role delegates/strips a lower one; the top `SUPER_ADMIN` is therefore seed/DB-only, never grantable via API), while **modifying/deleting the role itself** allows **at or below** (same level allowed). **Setting a role's priority** (creating a role, or changing it to a new value) again requires **strictly below** — otherwise a low role could be granted and then raised to the requester's own level. `is_system` roles are immutable, you cannot change your own roles, and the last `SUPER_ADMIN` (counted per church) is protected. Admin actions on **another member** (profile update, position change, password reset) require the target's top priority **at or below** your `maxPriority`, else `403`. Roles/positions are per church; the church owner is that church's `SUPER_ADMIN` (created by `scripts/new-church.sh`).
- **JWT** `sub` = member `uuid` (never the BIGINT id); payload carries flattened `permissions` + `maxPriority`, not roles, plus `cid` (church id) — a token whose `cid` differs from the request's church (or is missing) is `401 INVALID_TOKEN`. One `JWT_SECRET` for the whole platform. Refresh tokens and the logout blacklist live in Redis. Identity (uuid/id) is immutable while phone/name/email/password are mutable — so JWT-embedded values lag until the next refresh; for live values read `GET /api/members/me`.
- **Soft delete everywhere** (`deleted_at`). All list-query indexes are **partial** (`WHERE deleted_at IS NULL`). Phone uniqueness is also a partial unique index, per church — `(church_id, phone)` (lets a recycled carrier number re-register, and the same phone sign up separately in each church).
- **Optimistic locking** (`@Version`) on editable content; version conflicts return `409 OPTIMISTIC_LOCK_CONFLICT`.
- **Errors** use a single RFC 7807 envelope (`errorCode`/`title`/`status`/`detail`/`instance`) from a `@RestControllerAdvice`. **Lists** use the `{content, page:{size,number,totalElements,totalPages}}` envelope; list cards omit body `content`.
- **Author display = `updated_by`** (last editor), not the original author — so a withdrawn author's posts self-heal when edited; show "(탈퇴한 사용자)" when that member is soft-deleted.
- **Central media library**: images/PDFs all live in one `media` table. Bodies reference them as the literal string `media:{id}` inside markdown (not URLs), so bodies stay domain-independent across churches. Reference tracking is body `LIKE '%media:{id}%'` UNION gallery/bulletin FK; deletion is **blocking** (409 + reference list if in use). Markdown is stored raw (TEXT); rendering/sanitizing is the frontend's job.
- **Identifier naming**: code-facing keys are **English** (permission names like `SERMON_WRITE`, role names like `ADMIN`); user-facing data is **Korean** (position/tag names, titles, `roles.description`).
- **Path authorization**: `/api/admin/**` 인증+메서드 권한, `/api/gallery/**` `GALLERY_VIEW`, `/api/bible-challenges/**` `CHALLENGE_PARTICIPATE`, `/api/sermons/**` `SERMON_VIEW`(회원전용), `/api/vehicle-runs/**` `VEHICLE_APPLY`(회원전용), 나머지 `/api/**` public(`/api/main` 포함 — 의도적). 교회 접두사 `/church/{slug}`는 `TenantFilter`가 매처보다 먼저 떼므로 매처·컨트롤러 경로는 `/api/**` 그대로다. 정본은 `SecurityConfig` 매처 체인과 `.claude/rules/rbac-authorization.md`의 표.

## Versioning & CI — do not hand-edit (SUH-DEVOPS-TEMPLATE)

This repo is wired to the SUH-DevOps template. **`version.yml` is the version source of truth** and `.github/scripts/version_manager.sh` syncs it with `build.gradle`'s `version`.

- **Pushing to `main` auto-bumps the patch version** (`PROJECT-COMMON-VERSION-CONTROL`) and commits it with `[skip ci]`. **Do not manually bump** `version.yml` or the `build.gradle` version, and don't hand-edit `CHANGELOG.*` — the workflows own those files.
- Treat `.github/workflows/PROJECT-COMMON-*` and `.github/scripts/*` as template-managed; don't modify them as part of feature work.
- Publishing to GitHub Packages happens on push to the **`deploy`** branch or a `v*.*.*` tag; changelog automation runs on PRs targeting `deploy`.

## Commit convention

`<type> : <description>` — 콜론 앞 공백은 저장소 히스토리 관행이다 (types: `feat`, `fix`, `refactor`, `docs`, `test`, `chore`, `perf`, `ci`). Commit in Korean to match history. Commit/push only when asked.
