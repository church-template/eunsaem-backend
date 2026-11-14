# RBAC & Authorization

Discord-style dynamic RBAC. See `docs/church-backend-spec.md` §3–§4 for the full model. These are the invariants that must hold in every authorization path.

## Two independent axes — never couple them

- **Position (직분)** and **Role/Permission** are separate. A high position grants **zero** authority. Never derive permissions from `position_id`.
- Authorization decisions are made **per Permission**, never per Role. Roles are just bundles.

```java
@PreAuthorize("hasAuthority('SERMON_WRITE')")   // correct — check the permission
// NOT hasRole('ADMIN'), NOT "if position == 목사"
```

- DB stores permission/role names **without** any `ROLE_`/authority prefix. Apply the prefix only at the Spring Security mapping layer.

## Priority hierarchy guard (escalation prevention)

Three priority rules, **split by operation**:

- **Grant/revoke a role to a member** — target `priority` must be **strictly below** the requester's `maxPriority` (same level **blocked**). Only a strictly-higher role delegates/strips a lower one; a peer cannot. The top role (`SUPER_ADMIN`) has nothing above it → **never grantable via API; seed/DB only**. Equal-or-higher → `403`. (Validator: `validateGrantable`, used by `MemberRoleService`.)
- **Modify the role itself** — `PATCH /roles/{id}`, `DELETE /roles/{id}`, `PUT /roles/{id}/permissions` — target `priority` must be **at or below** the requester's `maxPriority` (same level allowed). Strictly higher → `403`. (Validator: `validateAssignable` via `validateMutable`.)

- **Set a role's priority** — `POST /roles` and a `PATCH /roles/{id}` that changes `priority` — the new `priority` must be **strictly below** the requester's `maxPriority` (same level **blocked**). Otherwise the grant rule is bypassable: grant a low role, then raise it to your own level to mint a peer admin. Re-sending the unchanged value is not a change. Equal-or-higher → `403`. (Validator: `validateNewPriority`, used by `RoleService`.)
- **Admin action on another member** — `PATCH /api/admin/members/{uuid}`, `PUT …/position`, `POST …/reset-password` — the target member's top `priority` must be **at or below** the requester's `maxPriority` (same level allowed). Strictly higher → `403`. `MEMBER_MANAGE` alone is not enough: an `ADMIN` must not reset a `SUPER_ADMIN`'s password or change its phone. Self-service (`/api/members/me`) is not subject to this. (Validator: `validateAssignable`.)

Additional hard stops:

- `is_system = true` roles: reject modify/delete outright, regardless of priority.
- **Self-protection**: a requester cannot grant/revoke their **own** roles.
- **Last SUPER_ADMIN**: if only one SUPER_ADMIN exists **in that church**, reject its revoke/demote/delete.

Roles and positions are **per church** (`(church_id, name)` unique): each church has its own `SUPER_ADMIN`/`ADMIN`/`MEMBER`/`USER` rows, and every count/lookup is scoped to the request's church. `permissions` is the one global catalog. The church owner is that church's `SUPER_ADMIN`, created by `scripts/new-church.sh`; the operator keeps no standing app account — see [[multi-church-template]].

## JWT contents

- `sub` = member **`uuid`** (never the BIGINT `id`).
- Carry **flattened `permissions`** + `maxPriority` — not roles.
- Access tokens carry **`cid`** (church id). A token whose `cid` differs from the request's church — or has no `cid` — is treated as invalid (`401 INVALID_TOKEN`). Refresh tokens have no `cid`; a refresh at another church fails because the member lookup is tenant-filtered.
- One `JWT_SECRET` for the whole platform (not per church).
- Token reflects issue-time values; role/permission changes apply on next refresh. For live authority decisions on sensitive screens, read DB (`GET /api/members/me`), not the token.

## Path authorization — SecurityConfig 매처 체인이 정본

아래 표는 교회 접두사 `/church/{slug}`를 **뗀 뒤의** 경로다. `TenantFilter`가 보안 필터 체인보다 앞에서 접두사를 떼므로 매처는 접두사를 모르고, `/church/{slug}/api/admin/**`도 2번 규칙으로 보호된다. 접두사 뒤가 `/api/`가 아니면(`/church/x/actuator/…`) 404.

매처는 **선언 순서대로 선순위 매칭**된다 (`global/config/SecurityConfig.securityFilterChain`):

| 순서 | Path | Rule |
|---|---|---|
| 1 | swagger(`/v3/api-docs`, `/v3/api-docs/**`, `/docs/swagger-ui/**`, `/docs/swagger-ui.html`) · `/error` · `/actuator/health` | permitAll |
| 2 | `/api/admin/**` | 인증 필수 + 세부 권한은 메서드 `@PreAuthorize` (예: `SERMON_WRITE`) |
| 3 | `/api/gallery/**` | `GALLERY_VIEW` (승인 교인 전용) |
| 4 | `/api/bible-challenges/**` | `CHALLENGE_PARTICIPATE` (승인 교인 전용) |
| 5 | `/api/sermons/**` | `SERMON_VIEW` (승인 교인 전용 — 2026-07 #53 전환) |
| 6 | `/api/vehicle-runs/**` | `VEHICLE_APPLY` (승인 교인 전용) |
| 7 | 나머지 `/api/**` (공지·행사·주보·부서·문의·메인 등) | public |

- `/api/main`(통합 조회)은 **의도적으로 public 유지** — 설교 카드가 홈에 노출돼도 상세(`/api/sermons/{id}`) 클릭은 차단된다(#53 설계 결정).
- `MEMBER` role = 승인 교인 — `GALLERY_VIEW`·`SERMON_VIEW`·`CHALLENGE_PARTICIPATE`·`VEHICLE_APPLY` 보유(V2·V13·V15·V16 시드). 가입 직후 기본 `USER`와 익명은 회원전용 경로에서 차단. `MEMBER` 부여가 곧 교인 승인 절차다(이메일 인증 대체).
- 새 회원전용 경로를 추가할 땐 `anyRequest().permitAll()` **앞에** 매처를 넣고, 이 표와 CLAUDE.md 요약도 함께 갱신할 것.

## Identifier language

Code-facing keys are **English** (`SERMON_WRITE`, `ADMIN`, `MEMBER`); user-facing labels are **Korean** (`roles.description`, position/tag names). Check English `name` in code, show Korean `description` to humans.
