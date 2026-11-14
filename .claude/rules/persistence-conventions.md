# Persistence Conventions

JPA/PostgreSQL rules shared by every domain. See `docs/church-backend-spec.md` §3, §5, §6.

## BaseEntity (`global/common`)

Editable content entities extend a `@MappedSuperclass` `BaseEntity` carrying:

- `created_at`, `updated_at` — JPA Auditing (`@EnableJpaAuditing`).
- `created_by`, `updated_by` — FK → members (audit).
- `deleted_at` — soft delete.
- `version` — optimistic lock.
- `church_id` — tenant column, Hibernate `@TenantId`. It lives one level up in `BaseTimeEntity` (which `BaseEntity` extends), so every entity inherits it; `ContentTag` has no base class and declares it directly. Hibernate fills and filters it — never set it in service code.

Don't redeclare these per entity; inherit them so audit/soft-delete/locking/tenancy stay consistent. Every new table needs `church_id` (exception: global catalogs like `permissions`) — see [[multi-church-template]].

## Soft delete everywhere

- Delete = set `deleted_at`, never physical delete (except media files, see [[media-library]]).
- Every read filters `deleted_at IS NULL` (the `church_id` filter is added by Hibernate on top).
- **All list/lookup indexes are partial**: `... WHERE deleted_at IS NULL`. This keeps deleted rows out of the index. List indexes **lead with `church_id`** (e.g. `(church_id, preached_at DESC) WHERE deleted_at IS NULL`).
- **Unique constraints are per church**: `(church_id, name)` for roles/positions/tags; `members.phone` is a **partial unique index** `(church_id, phone) WHERE deleted_at IS NULL` — a recycled carrier number can re-register after withdrawal, and the same phone can sign up separately in each church. Still global: `members.uuid`, `media.stored_path`, `permissions.name`.
- **Native queries are not tenant-filtered.** Every `nativeQuery = true` must carry `church_id = :churchId` (count query included); `NativeQueryChurchIdTest` enforces it.

## Identity is immutable

- `members.id` (BIGINT, internal FK/join) and `members.uuid` (external id, JWT sub) never change. Ids are unique across all churches (shared sequences); `church_id` is immutable too.
- `phone` / `name` / `email` / `password` are mutable; all FKs reference `id`, so changing them preserves every link.
- Phone is stored **digits-only normalized** (e.g. `01012345678`); normalize before lookup and uniqueness checks.

## Optimistic locking

Editable content (sermon, notice, event, department, gallery album, bulletin) uses `@Version`. On conflict, surface `409 OPTIMISTIC_LOCK_CONFLICT` (see [[api-conventions]]) — never let a late save silently overwrite.

## Author display policy

- Content has both `created_by` (original) and `updated_by` (last editor); set `updated_by` to the current requester on every edit.
- **Displayed author = `updated_by`**, not `created_by`. (A withdrawn author's posts self-heal when someone edits them.)
- If that member is soft-deleted, render `"(탈퇴한 사용자)"` — keep the FK intact, just mask the name.

## Tags are polymorphic — integrity is the app's job

`content_tags(tag_id, resource_type, resource_id)` is a single tag pool shared across domains (one pool per church). The DB cannot enforce FK integrity on `resource_id`, so:

- Validate the target resource exists when creating a link.
- On content soft-delete, clean up its `content_tags` rows.
