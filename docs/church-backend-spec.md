# 교회 홈페이지 백엔드 명세서

여러 교회가 **함께 쓰는 백엔드**다. 앱·DB·Redis 하나를 모든 교회가 공유하고(§2 멀티테넌시), 교회별 차이(이름·slug·프론트 주소)는 `churches` 테이블의 행으로 둔다. 새 교회는 코드 수정·재배포 없이 등록 스크립트 한 번으로 끝나야 한다(§12).

---

## 1. 기술 스택

| 영역 | 기술 |
|---|---|
| 언어/프레임워크 | Java 21, Spring Boot 4.0.6 |
| 보안 | Spring Security + JWT |
| DB | PostgreSQL 16 |
| 캐시/세션 | Redis 7 |
| 빌드 | Gradle |
| API 문서 | Swagger UI (springdoc-openapi, OpenAPI 3) |
| 배포 | Docker / docker compose (postgres, redis, backend 컨테이너) |

프론트엔드(Next.js)는 교회별 별도 프로젝트이며 본 명세 범위 밖이다. 백엔드는 REST API만 제공한다.

**API 문서화(Swagger):** `springdoc-openapi`를 적용해 모든 엔드포인트를 Swagger UI로 노출한다(`/swagger-ui.html`, OpenAPI 스펙 `/v3/api-docs`). 각 컨트롤러·DTO에 `@Operation`, `@Schema` 등으로 설명·예시를 기술하고, 공통 에러 응답(RFC 7807)과 인증 헤더(Bearer)도 문서에 반영한다. 운영 환경에서는 Swagger UI 노출 여부를 설정(`.env`/프로파일)으로 토글할 수 있게 한다(공개 서버에 무방비 노출 방지).

---

## 2. 멀티테넌시 정책 (중요)

- **앱 1개·PostgreSQL 1개·Redis 1개를 모든 교회가 공유한다**(공유형 멀티테넌시, #86). 교회별 DB·배포 인스턴스는 두지 않는다.
- **격리는 `church_id` 컬럼 + Hibernate `@TenantId`**다. 19개 테이블에 `church_id`가 있고(`BaseTimeEntity`에 선언, `ContentTag`는 직접 선언) JPA 조회·저장에 자동 적용된다. 전 교회 공용 카탈로그 `permissions`와 순수 조인 테이블 `role_permissions`·`member_roles`에는 없다. DB 레벨 격리(RLS·스키마 분리)는 없다 — 격리는 코드와 격리 테스트가 지킨다.
- **요청의 교회는 API 경로 접두사 `/church/{slug}`로 정한다**(`churches.slug`). `TenantFilter`가 보안 필터 체인보다 앞에서 접두사를 떼므로 경로 인가 규칙과 컨트롤러 매핑은 `/api/**` 그대로다. 등록되지 않은 slug는 `404 RESOURCE_NOT_FOUND`. `/actuator/health`·Swagger·`/error`는 교회 없이 통과한다.
- `TENANT_DEFAULT_SLUG`가 설정돼 있으면 접두사 없는 `/api/**` 요청을 그 교회로 처리한다(로컬·테스트·첫 교회 전환 기간 전용). 비어 있으면 404, `churches`에 없는 slug면 기동 실패.
- **fail-closed**: 교회가 정해지지 않은 DB 접근은 예외다. 기본 교회나 전체를 보는 루트 테넌트는 없다. 요청 밖 코드(스케줄러)는 `TenantContext.runAs(churchId, …)`를 트랜잭션 **바깥**에서 감싼다.
- **네이티브 쿼리는 자동으로 걸러지지 않는다.** 반드시 `church_id = :churchId` 조건을 넣는다(`NativeQueryChurchIdTest`가 강제).
- **회원·역할·직분·태그는 교회별로 독립**이다. 유니크 제약은 `(church_id, …)`이고, 같은 전화번호로 교회마다 따로 가입할 수 있다. id(시퀀스)는 전 교회에 걸쳐 유일하다.
- `churches`(id·name·slug·frontend_origin·created_at)는 JPA 엔티티가 아니며 `JdbcClient`로만 읽는다. 새 교회는 `scripts/new-church.sh`로 등록한다(§12).
- 플랫폼 공용 값(DB·Redis 접속 정보, JWT 시크릿 등)은 환경변수로 주입하고, 교회별 값은 `churches` 행에 둔다. 어느 쪽도 코드에 하드코딩하지 않는다.

---

## 3. 도메인 권한 모델 (RBAC, Discord 스타일 동적 권한)

### 3.1 핵심 원칙
- **직분(Position)** 과 **권한(Role/Permission)** 은 완전히 독립된 별개의 축이다. 직분이 높다고 권한이 자동 부여되지 않는다.
- 직분: 목사, 권사, 장로, 학생, 교사 등. **테이블 데이터로 관리**하여 코드 수정 없이 추가/삭제 가능.
- 권한: 역할(Role)에 권한(Permission)을 조립하는 방식. 관리자가 **런타임에 역할을 생성**하고 권한을 조합할 수 있다.
- 인가 판단은 **권한(Permission) 단위**로 한다. 역할은 권한을 묶는 중간 그릇일 뿐이다.
- 역할에 **priority(위계)** 가 있다. **위임/박탈**(회원에게 역할 부여/회수)은 자기보다 **엄격히 낮은** priority만 가능(동급 차단 — 상위 역할만 하위를 위임). **역할 정의 수정·삭제**는 자기 이하 priority까지 가능(같은 레벨 허용). 단 **역할의 priority를 정할 때**(생성, 또는 다른 값으로 변경)는 자기보다 **엄격히 낮아야** 한다 — 낮은 역할을 준 뒤 자기 등급까지 올려 동급 관리자를 만드는 우회를 막는다.

### 3.2 테이블

**members**
| 컬럼 | 타입 | 비고 |
|---|---|---|
| id | BIGINT PK | 내부용, FK·조인 (성능) |
| uuid | UUID UNIQUE | 외부 노출용 식별자 (API 경로, JWT sub). 가입 시 백엔드 자동 발급 |
| phone | VARCHAR | 로그인 ID. 숫자만 정규화 저장(예: 01012345678). 변경 가능 |
| password | VARCHAR | BCrypt 해시 |
| name | VARCHAR | 표시용 (식별/로그인에 미사용) |
| email | VARCHAR | **선택 입력**, nullable. 인증 안 함 |
| terms_agreed | BOOLEAN | 이용약관 동의 (필수, 가입 시 true 아니면 거부) |
| privacy_agreed | BOOLEAN | 개인정보 수집·이용 동의 (필수, 가입 시 true 아니면 거부) |
| agreed_at | TIMESTAMP | 약관 동의 시각 |
| position_id | BIGINT FK → positions | nullable. 직분 삭제 시 `ON DELETE SET NULL`(직분 없는 상태로) |
| created_at | TIMESTAMP | |
| deleted_at | TIMESTAMP | nullable, soft delete |

- `phone`의 유일성은 **교회 단위 부분 유니크 인덱스** `(church_id, phone) WHERE deleted_at IS NULL`로 건다. 탈퇴 회원의 번호가 통신사에서 재활용돼 새 사람이 가입해도 충돌하지 않고, 같은 번호로 교회마다 따로 가입할 수 있다.
- 아래 표들은 `church_id`(§2)를 생략했다. `permissions`·`role_permissions`·`member_roles`를 뺀 모든 테이블에 `church_id BIGINT NOT NULL FK → churches`가 있다.
- 내부 FK(member_roles, gallery_photos.created_by 등)는 모두 `id`(BIGINT)로 참조한다. 그래서 phone·name·email·password를 모두 바꿔도 회원 정체성(uuid/id)과 모든 연결이 그대로 유지된다.
- 이메일 인증(SMTP)은 사용하지 않는다. 본인 확인은 관리자 승인으로 대체한다(아래 4장 참고).
- **약관 동의:** 가입 시 필수 동의 2종(`terms_agreed`, `privacy_agreed`)이 모두 true여야 가입이 성립한다. 하나라도 false면 가입 거부. 동의 시각을 `agreed_at`에 기록해 추후 동의 사실을 증명할 수 있게 한다.
- **비밀번호 정책:** 일반 비밀번호(BCrypt 해시 저장). 고령 사용자를 고려해 특수문자·대소문자 강제 같은 복잡한 규칙은 두지 않고, 최소 길이 검증(예: 8자 이상) 정도만 적용한다.

**positions** (직분 — 데이터로 추가)
| 컬럼 | 타입 | 비고 |
|---|---|---|
| id | BIGINT PK | |
| name | VARCHAR | 목사/권사/장로/학생/교사… 교회 안에서 유일 `(church_id, name)` |
| sort_order | INT | 표시 순서 |
| created_at | TIMESTAMP | |

- 직분은 선택값이다. 회원의 `position_id`가 NULL이면 "직분 없음"을 의미하므로 별도 기본 직분 행은 두지 않는다.
- 직분 삭제 시 해당 직분을 가진 회원의 `position_id`는 `ON DELETE SET NULL`로 자동 해제된다(회원·콘텐츠에 영향 없음).

**roles** (역할 — 관리자가 동적 생성)
| 컬럼 | 타입 | 비고 |
|---|---|---|
| id | BIGINT PK | |
| name | VARCHAR | SUPER_ADMIN, ADMIN, 콘텐츠관리자… (ROLE_ 접두사 없이 저장). 교회 안에서 유일 `(church_id, name)` |
| priority | INT | 숫자 높을수록 상위 |
| is_system | BOOLEAN | true면 삭제/수정 불가 (SUPER_ADMIN 등 보호) |
| description | VARCHAR | |
| created_at | TIMESTAMP | |

**permissions** (권한 — 코드/시드로 고정)
| 컬럼 | 타입 | 비고 |
|---|---|---|
| id | BIGINT PK | |
| name | VARCHAR UNIQUE | SERMON_WRITE 등 |
| description | VARCHAR | |

**role_permissions** (역할 ↔ 권한, 다대다)
| 컬럼 | 타입 |
|---|---|
| role_id | BIGINT FK → roles |
| permission_id | BIGINT FK → permissions |
| PK | (role_id, permission_id) |

**member_roles** (회원 ↔ 역할, 다대다)
| 컬럼 | 타입 |
|---|---|
| member_id | BIGINT FK → members |
| role_id | BIGINT FK → roles |
| PK | (member_id, role_id) |

### 3.3 시드 데이터

permissions (고정):
```
SERMON_WRITE     설교 등록/수정/삭제
NOTICE_WRITE     공지 등록/수정/삭제
EVENT_WRITE      일정 등록/수정/삭제
DEPT_WRITE       교구/부서 등록/수정/삭제
MEMBER_MANAGE    회원 조회/관리
ROLE_MANAGE      역할·권한 관리
POSITION_MANAGE  직분 관리
MEDIA_MANAGE     미디어 업로드/조회/삭제
TAG_MANAGE       태그 추가/수정/삭제
GALLERY_WRITE    갤러리 업로드/수정/삭제
GALLERY_VIEW     갤러리 조회 (회원 전용 열람)
BULLETIN_WRITE   주보 업로드/수정/삭제
INQUIRY_MANAGE   문의 조회/완료체크/삭제
```

roles (초기):
```
SUPER_ADMIN  priority 1000  is_system true   (모든 permission 보유)
ADMIN        priority 900   is_system true
MEMBER       priority 100   is_system false  (승인된 교인 — GALLERY_VIEW 보유)
USER         priority 0     is_system true   (가입 시 자동 부여, 갤러리 열람 불가)
```

`MEMBER`("교인") 역할은 회원 전용 콘텐츠(갤러리 등) 열람을 위한 역할이다. 누구나 가입하면 `USER`가 되지만, 관리자가 승인한 교인에게 `MEMBER` 역할을 부여해야 `GALLERY_VIEW`가 생겨 갤러리를 볼 수 있다. 단순 가입자(외부인)는 차단된다. "교인"은 직분(position)이 아니라 역할(role)로 관리한다 — 열람 가부는 인가 문제이기 때문이다.

기본 역할 4종은 **교회마다 따로** 있다(첫 교회는 마이그레이션 시드, 이후 교회는 `scripts/new-church.sh`가 생성). 교회의 최초 SUPER_ADMIN은 등록 스크립트가 만드는 **교회 대표자** 계정이다 — 기동 시 환경변수로 관리자를 만들던 부트스트랩은 없다. 운영자는 앱에 상시 계정을 두지 않는다. 이후 권한 부여는 API로 하고, SUPER_ADMIN 추가만 DB 직접 수정으로 한다.

---

## 4. 인증·인가 동작

### 4.1 회원가입/로그인

**회원가입** — 고령 사용자를 고려해 최소 정보만 받는다. 이메일 인증·SMTP 없음.
- 입력: `phone`(전화번호), `name`(이름), `password`(비밀번호), 약관 동의(`termsAgreed`, `privacyAgreed` 둘 다 필수). `email`은 선택.
- 필수 동의 2종이 true가 아니면 가입을 거부하고, 성립 시 `agreed_at`에 동의 시각 기록.
- 백엔드가 `uuid`를 자동 발급(`UUID.randomUUID()`)하여 저장.
- 가입 직후엔 `USER` 역할만 부여된 **미승인 상태**. 갤러리 등 회원 전용 기능은 못 본다.
- 관리자가 회원 목록에서 본인(교인)임을 확인하고 `MEMBER` 역할을 부여하면 정식 교인이 된다. 이것이 이메일 인증을 대체하는 본인 확인 절차다(서로 아는 사이라는 교회 특성 활용).

**로그인**
- 입력: `phone` + `password` 두 개만. 이름은 사용하지 않는다(고유하지 않으므로).
- 서버 처리: phone 정규화(숫자만) → `WHERE phone = ? AND deleted_at IS NULL` 조회(요청 교회 안에서만 — `church_id` 조건은 자동) → BCrypt 비밀번호 대조 → 성공 시 uuid·권한으로 JWT 발급.
- **시도 제한**: 실패가 `(교회, 전화번호, IP)`당 15분에 10회, 또는 IP당 15분에 100회를 넘으면 `429 RATE_LIMIT_EXCEEDED`. Redis 장애 시에는 통과시킨다(fail-open).
- 실패 응답은 "전화번호 없음"과 "비밀번호 불일치"를 구분하지 않고 동일한 401로 반환(가입 여부 노출 방지).
- 로그인 시 JWT(Access) 발급, Refresh Token은 Redis에 저장.
- 로그아웃 시 토큰을 Redis 블랙리스트에 등록.
- 토큰 만료: **Access 1시간, Refresh 14일** (`.env`의 `JWT_ACCESS_EXPIRY`/`JWT_REFRESH_EXPIRY`로 주입).

**회원 정보 변경 시 정체성 유지**
- phone·name·email·password를 바꿔도 uuid/id가 불변이라 회원 정체성과 모든 데이터 연결이 그대로 유지된다.
- 단, JWT는 발급 시점 정보(name·permissions 등)를 담으므로, 변경 사항은 **다음 토큰 갱신(refresh/재로그인) 시 반영**된다. Access 만료가 1시간이라 최대 1시간 내 반영. 정확성이 필요한 화면은 토큰 값 대신 `GET /api/members/me`로 DB 최신값을 사용한다.
- 권한·역할 변경도 동일하게 다음 토큰 갱신 시 적용. 즉시 반영이 필요하면 해당 회원 토큰을 Redis 블랙리스트로 강제 만료(선택).

### 4.2 JWT payload
역할이 아니라 **펼쳐진 권한 목록**을 담는다. `sub`는 uuid(BIGINT id 아님).
```json
{
  "sub": "a3f8c2e1-...",
  "name": "홍길동",
  "position": "장로",
  "permissions": ["SERMON_WRITE", "NOTICE_WRITE"],
  "maxPriority": 900,
  "cid": 1
}
```
- `cid`는 토큰을 발급한 교회의 id다(access 토큰에만 있다). 요청 교회(`/church/{slug}`)와 `cid`가 다르거나 `cid`가 없으면 무효 토큰으로 본다(`401 INVALID_TOKEN`) — 한 교회의 토큰은 다른 교회에서 통하지 않는다.
- refresh 토큰은 `cid`가 없다. 다른 교회 접두사로 갱신하면 회원 조회가 그 교회 범위라 "회원 없음"으로 실패한다.
- JWT 시크릿(`JWT_SECRET`)은 플랫폼 전체에 하나다.

### 4.3 인가 방식
- 메서드 단위로 권한 검증: `@PreAuthorize("hasAuthority('SERMON_WRITE')")`.
- Spring Security 매핑 시 권한명에 `ROLE_` 또는 권한 접두사를 코드에서 부여(DB엔 접두사 없이 저장).

**경로 인가 규칙 (3분법)** — "조회는 모두 공개"가 아니다. 다음 세 갈래로 명시한다.
- `/api/admin/**` → 해당 쓰기/관리 권한 필요(예: `SERMON_WRITE`).
- `/api/gallery/**` → 로그인 + `GALLERY_VIEW` 필요(회원 전용 조회. 공개가 아님).
- 그 외 `/api/**` 조회 → 공개.
- 이 규칙은 교회 접두사 `/church/{slug}`를 뗀 뒤의 경로에 적용된다(§2). `/church/{slug}/api/admin/**`도 똑같이 보호된다.

**위계 검증 (priority 기반)** — 권한 상승(escalation) 차단을 위해 아래 모든 작업에 동일 적용한다.
- 회원에게 역할 부여/회수(위임/박탈): 대상 역할의 priority가 요청자 maxPriority보다 **엄격히 낮아야** 한다(동급 차단 — 상위 역할만 하위를 위임/박탈). 동급이거나 초과면 거부(403). 최상위 SUPER_ADMIN은 위에 아무것도 없어 API 위임 불가 — 시드/DB로만 구성.
- 역할 자체 수정(`PATCH /roles/{id}`), 삭제(`DELETE /roles/{id}`), 권한 변경(`PUT /roles/{id}/permissions`): 대상 역할의 priority가 요청자 maxPriority **이하여야** 한다. 자기보다 높은 역할은 건드릴 수 없다(같은 레벨까지 허용, is_system 여부와 무관하게 추가 검증).
- `is_system=true` 역할은 삭제/수정 자체를 거부.
- 관리자가 **다른 회원**을 바꾸는 작업(정보 수정·직분 지정·비밀번호 초기화): 대상 회원의 최고 priority가 요청자 maxPriority **이하여야** 한다(동급 허용). 초과면 거부(403) — `ADMIN`이 `SUPER_ADMIN`의 비밀번호를 초기화하거나 전화번호를 바꿀 수 없다. 본인 정보 수정(`/api/members/me`)은 대상이 아니다.

**자기 역할 보호 (C-2)**
- 요청자는 **자기 자신의 역할을 변경·회수할 수 없다**(실수로 권한을 잃는 것 방지). 본인 대상 역할 부여/회수 요청은 거부.
- **마지막 SUPER_ADMIN 보호**: 그 교회에 SUPER_ADMIN이 1명뿐이면 그 역할의 회수·강등·삭제를 거부한다(교회에 최고 관리자가 사라지는 것 방지). 집계는 교회 단위다.

### 4.4 식별자 네이밍 규칙 (영어 키 vs 한글 표시)
- **코드가 인가 키로 쓰는 값은 영어**로 둔다: permission name(`SERMON_WRITE`), role name(`ADMIN`, `MEMBER`). 코드에서 `hasAuthority('...')`로 직접 참조되므로 인코딩·협업 안정성을 위해 영어 식별자를 쓴다.
- **사용자에게 보이는 데이터는 한글**: position name(목사·장로), tag name(예배·선교), 공지·설교 제목/본문 등.
- 역할을 화면에 한글로 보여주려면 `roles.description`(한글)을 표시용으로 쓴다. 코드는 영어 `name`으로 검사, 사람에겐 한글 `description` 노출.

---

## 5. API 명세

규칙:
- 공개 조회(`GET /api/...`)는 인증 불필요. 쓰기/관리(`/api/admin/...`)는 해당 permission 필요.
- **모든 목록 조회는 페이지네이션을 적용**한다. 응답은 아래 공통 형식을 따른다.
- 페이징/정렬 파라미터 표준화: `?page=0&size=10&sort=createdAt,desc`. `size`로 개수를 조절해 홈 맛보기(size=3)와 상세 페이지(size=10 등)를 같은 API로 처리한다.
- 삭제는 soft delete(`deleted_at`) 우선, 조회 시 필터링.
- 토큰 발급 응답(`login`, `refresh`)은 토큰을 `tokens` 객체로 묶어 반환.

### 작성자·수정자 표시 정책
- 콘텐츠(설교·공지·갤러리·주보)는 `created_by`(최초 작성자)와 `updated_by`(마지막 수정자) 두 FK를 둔다. 수정 시 `updated_by`를 현재 요청자로 갱신.
- **목록/상세에 표시하는 "작성자"는 `updated_by`(마지막 수정자)를 기준으로 한다.** 작성자가 탈퇴(soft delete)해 표시가 곤란할 때, 누군가 그 글을 수정하면 자연스럽게 마지막 수정자로 갱신되어 "알 수 없음"이 해소되는 효과를 노린 정책이다.
- 표시 시 해당 회원이 탈퇴 상태(`deleted_at` 존재)면 이름 대신 "(탈퇴한 사용자)"로 표기. FK 자체는 유지되어 데이터 연결은 끊기지 않는다.

### 동시 수정 방지 (낙관적 락)
- 수정 가능한 콘텐츠(설교·공지·이벤트·부서·갤러리 앨범·주보)에 `version`(BIGINT, JPA `@Version`) 컬럼을 둔다.
- 두 관리자가 같은 리소스를 동시에 수정할 때, 나중 저장이 먼저 저장을 조용히 덮어쓰는 것을 막는다. 버전 충돌 시 `409 Conflict`(errorCode `OPTIMISTIC_LOCK_CONFLICT`)로 응답하고, 클라이언트는 최신본을 다시 불러 재시도한다.

### 공통 목록 응답 형식 (페이지네이션)
```json
{
  "content": [ { "...": "도메인 항목" } ],
  "page": {
    "size": 10,
    "number": 0,
    "totalElements": 42,
    "totalPages": 5
  }
}
```
- `content`: 현재 페이지 항목 배열(목록은 `content` 본문 제외, 카드용 메타만).
- `page.size`: 페이지당 개수, `page.number`: 현재 페이지(0-base), `page.totalElements`: 전체 건수, `page.totalPages`: 전체 페이지 수.
- 홈 화면은 각 도메인을 `size=3` 등으로 호출해 맛보기로 노출하고, 도메인 전용 페이지는 `size=10` 등으로 페이지네이션한다. 또는 홈 통합은 `GET /api/main`이 도메인별 최신 N건을 묶어 반환한다.

### 공통 에러 응답 형식 (RFC 7807 Problem Details)
모든 실패 응답은 전역 예외 핸들러(`@RestControllerAdvice`)에서 동일한 형식으로 매핑한다.
```json
{
  "errorCode": "INVALID_INPUT_VALUE",
  "title": "유효하지 않은 입력값",
  "status": 400,
  "detail": "입력값이 유효성 검사를 통과하지 못했습니다.",
  "instance": "/api/auth/login"
}
```
- `errorCode`: 코드용 식별자(영문 대문자 스네이크). 클라이언트 분기용.
- `title`: 사용자 표시용 한글 요약.
- `status`: HTTP 상태 코드.
- `detail`: 구체적 설명(필요 시 필드별 오류 배열을 `errors`로 추가 가능).
- `instance`: 오류가 난 요청 경로.

주요 매핑 예:
| 상황 | status | errorCode |
|---|---|---|
| 유효성 검사 실패 | 400 | INVALID_INPUT_VALUE |
| 인증 실패(로그인 불일치) | 401 | AUTHENTICATION_FAILED |
| 토큰 만료/무효 | 401 | INVALID_TOKEN |
| 권한 없음 | 403 | ACCESS_DENIED |
| 리소스 없음 | 404 | RESOURCE_NOT_FOUND |
| 미디어 삭제 차단(참조 존재) | 409 | MEDIA_IN_USE (참조 목록을 `references`로 동봉) |
| 동시 수정 충돌(낙관적 락) | 409 | OPTIMISTIC_LOCK_CONFLICT |
| 중복(전화번호 등) | 409 | DUPLICATE_RESOURCE |

### 본문 콘텐츠 저장 정책 (마크다운)
- 설교 `content`, 공지 `content`, 부서 `description` 등 본문 필드는 **마크다운 문자열을 원본 그대로 저장**한다. DB 타입은 `TEXT`.
- 서버는 마크다운을 HTML로 변환하지 않는다. 원본 마크다운을 저장·전달만 한다(관심사 분리). 동일 원본을 웹/모바일/검색 인덱싱 등 여러 표현으로 재사용할 수 있다.
- **렌더링과 새니타이즈(sanitize)는 프론트엔드 책임**이다. 프론트는 마크다운 → HTML 변환 후 화면에 표시하기 전에 반드시 새니타이즈(예: DOMPurify)를 거친다.
- **raw HTML은 비허용을 기본**으로 한다. 마크다운 변환 단계에서 raw HTML을 비활성화하여 저장형 XSS 공격면을 줄인다.
- 서버 저장 시에도 최소 검증(길이 제한 등)은 두되, 마크다운 원본의 순수성을 위해 HTML 새니타이즈는 프론트 렌더 단계에서 수행한다.
- 목록 조회는 본문(`content`)을 제외하고 카드용 메타데이터(제목·태그·조회수·작성일·작성자 등)만 내린다. 별도 요약(summary) 필드는 두지 않으며, 상세 조회에서만 `content`를 반환한다.
- **본문 내 이미지는 전체 URL이 아니라 미디어 id로 참조**한다. 표준 `![](url)` 대신 `![](media:{id})` 형태(예: `![설명](media:42)`)로 저장한다. 본문에 도메인이 박히지 않으므로 교회마다 도메인이 달라도(템플릿) 본문 데이터는 불변이다.
- 프론트는 렌더 직전 `media:{id}`를 실제 접근 URL(`<API 베이스>/church/<slug>/api/media/{id}`)로 치환한 뒤 새니타이즈하여 표시한다. 이 치환은 기존 마크다운 렌더·새니타이즈 파이프라인에 정규식 한 단계로 흡수된다.
- **설계 노트(트레이드오프):** 미디어-콘텐츠 관계는 별도 연결 테이블이 아니라 본문 텍스트 내부의 `media:{id}` 문자열로 표현된다. 즉 이 관계는 정규화된 행이 아니라 비정규화된 텍스트 안에 존재한다. 이는 마크다운 본문 어느 위치에나 이미지를 자유롭게 삽입하기 위한 의도된 선택이며(노션·깃허브 등 마크다운 CMS의 공통 방식), 그 대가로 참조 추적은 LIKE 검색에 의존한다(아래 미디어 라이브러리 참조).

### 5.1 인증 (Auth)
| 메서드 | 경로 | 권한 | 설명 |
|---|---|---|---|
| POST | /api/auth/signup | 공개 | 회원가입 (phone·name·password 필수, email 선택. USER 자동 부여) |
| POST | /api/auth/login | 공개 | 로그인 (phone·password), JWT 발급 |
| POST | /api/auth/refresh | 공개 | Access 토큰 재발급 (Redis Refresh 확인) |
| POST | /api/auth/logout | 인증 | 로그아웃 (Redis 블랙리스트) |

회원가입 요청:
```json
POST /api/auth/signup
{
  "phone": "010-1234-5678",
  "name": "홍길동",
  "password": "...",
  "email": "선택(생략 가능)",
  "termsAgreed": true,
  "privacyAgreed": true
}
```

로그인 요청/응답:
```json
POST /api/auth/login
{ "phone": "010-1234-5678", "password": "..." }

200 OK
{
  "tokens": {
    "accessToken": "eyJ...",     // sub=uuid, permissions 포함
    "refreshToken": "eyJ..."
  },
  "member": { "uuid": "a3f8...", "name": "홍길동", "phone": "010-1234-5678", "position": "장로", "roles": ["MEMBER"] },
  "requiresAgreement": false     // 필수 약관(terms/privacy) 중 미동의가 있으면 true → 재동의 페이지로 유도
}
```
로그인 실패 시 전화번호 미존재·비밀번호 불일치를 구분하지 않고 401 동일 응답(아래 공통 에러 형식 따름).
`requiresAgreement`가 true면 클라이언트는 별도 `GET /me/agreements` 호출 없이 바로 재동의 흐름으로 보낸다.

### 5.2 회원 (Member)
외부 식별은 `{uuid}`로 한다(내부 id 비노출).
| 메서드 | 경로 | 권한 | 설명 |
|---|---|---|---|
| GET | /api/members/me | 인증 | 내 정보(직분·권한 포함, DB 최신값) |
| PATCH | /api/members/me | 인증 | 내 정보 수정 (이름·전화번호·비밀번호·이메일). 전화번호 변경 시 중복 체크 |
| DELETE | /api/members/me | 인증 | 회원 탈퇴(자가탈퇴). 비밀번호 재인증 후 soft delete + 개인정보 스크럽 + 세션 무효화(리프레시 전체 회수) |
| GET | /api/members | MEMBER_MANAGE | 교인 목록 |
| GET | /api/members/{uuid} | MEMBER_MANAGE | 교인 상세 |
| PATCH | /api/admin/members/{uuid} | MEMBER_MANAGE | 관리자가 회원 정보 수정 (전화번호 변경 등). 위계 검증(대상 최고 priority ≤ 요청자 maxPriority) |
| POST | /api/admin/members/{uuid}/reset-password | MEMBER_MANAGE | 관리자 비밀번호 초기화 (로그인 불가 회원 구제). 위계 검증(위와 같음) |
| POST | /api/admin/members/{uuid}/roles | ROLE_MANAGE | 역할 부여 (위계 검증). MEMBER 부여 = 교인 승인 |
| DELETE | /api/admin/members/{uuid}/roles/{roleId} | ROLE_MANAGE | 역할 회수 (위계 검증) |
| PUT | /api/admin/members/{uuid}/position | MEMBER_MANAGE | 회원 직분 지정/변경/해제. body `{positionId}` (null이면 해제). 직분 자체는 권한과 분리돼 있지만, 남의 정보를 바꾸는 작업이라 대상 회원 위계 검증(대상 최고 priority ≤ 요청자 maxPriority)을 거친다. 미존재 회원/직분 → 404. 응답 `MemberDetailResponse` |

- 본인이 로그인된 상태면 `PATCH /api/members/me`로 전화번호를 직접 변경.
- 번호도 바뀌고 비밀번호도 잊어 로그인 불가하면, 관리자가 `PATCH /api/admin/members/{uuid}`로 번호 갱신 + `reset-password`로 초기화하여 구제(서로 아는 교회 특성 활용).

회원 탈퇴(자가탈퇴) 정책:
- `DELETE /api/members/me` — 본인만. 요청 본문에 현재 비밀번호를 받아 재인증(불일치 401 AUTHENTICATION_FAILED).
- 물리 삭제가 아니라 soft delete(`deleted_at`) + 개인정보 스크럽: phone·name은 비식별 토큰값, email은 null, password는 사용불가 값. 작성 콘텐츠는 FK 유지 + `(탈퇴한 사용자)` 표시.
- 세션 무효화: 모든 리프레시 토큰 회수(이후 재발급 차단) + 현재 access 토큰 블랙리스트. 성공 기준은 DB 커밋이며 Redis 무효화는 best-effort(장애 시 누락 가능). 다른 기기에 이미 발급된 access 토큰은 짧은 만료(기본 1시간)까지 유효 → 그 시점 이후 재발급 불가로 완전 차단.
- 마지막 활성 SUPER_ADMIN은 탈퇴 차단(403 ACCESS_DENIED).
- 재가입: 탈퇴한 전화번호로 재가입 가능(부분 유니크가 활성 회원만 대상). 단 새 계정(새 uuid)이며 이전 데이터와 무관.

**약관 재동의 사이클 (방식 A — 플래그 리셋)**
정책 개정 시 관리자가 해당 동의 플래그를 일괄 false로 바꾸면, 회원이 접속할 때 동의 상태를 조회해 풀린 항목이 있으면 재동의 페이지로 이동시키는 흐름. 별도 약관 버전 테이블 없이 members의 boolean으로 처리한다.

| 메서드 | 경로 | 권한 | 설명 |
|---|---|---|---|
| GET | /api/members/me/agreements | 인증 | 내 동의 상태 조회. 프론트는 필수 항목 중 false가 있으면 재동의 페이지로 유도 |
| PATCH | /api/members/me/agreements | 인증 | 재동의 제출. 해당 플래그 true + `agreed_at` 갱신 |
| POST | /api/admin/agreements/reset | MEMBER_MANAGE | 특정 동의 항목(`terms`/`privacy`)을 해당 교회의 전체(미삭제) 회원에 대해 false로 일괄 리셋 |

- 조회 응답 예: `{ "termsAgreed": false, "privacyAgreed": true, "agreedAt": "..." }`
- 일괄 리셋 요청 예: `POST /api/admin/agreements/reset { "target": "terms" }`
- 강제 수준: 기본은 **프론트 안내**(재동의 페이지 이동). 필수 약관 미동의 회원을 백엔드에서 전면 차단하려면, 동의 API·로그아웃 외 요청을 403 처리하는 인터셉터를 선택적으로 추가할 수 있다.

### 5.3 직분 (Position)
| 메서드 | 경로 | 권한 | 설명 |
|---|---|---|---|
| GET | /api/positions | 공개 | 직분 목록 (가입 폼·필터용, `sort_order ASC` 정렬) |
| POST | /api/admin/positions | POSITION_MANAGE | 직분 추가 |
| PATCH | /api/admin/positions/{id} | POSITION_MANAGE | 직분 수정 |
| DELETE | /api/admin/positions/{id} | POSITION_MANAGE | 직분 삭제 |

### 5.4 역할·권한 (Role)
| 메서드 | 경로 | 권한 | 설명 |
|---|---|---|---|
| GET | /api/admin/roles | ROLE_MANAGE | 역할 목록 (priority 순). 응답에 name·priority·is_system·description·permissions 포함 |
| POST | /api/admin/roles | ROLE_MANAGE | 역할 생성 (name·priority·description 입력) |
| PATCH | /api/admin/roles/{id} | ROLE_MANAGE | 역할명·priority·description 수정 (is_system 거부, 위계 검증) |
| DELETE | /api/admin/roles/{id} | ROLE_MANAGE | 역할 삭제 (is_system 거부, 위계 검증) |
| PUT | /api/admin/roles/{id}/permissions | ROLE_MANAGE | 역할에 권한 일괄 설정 (위계 검증) |
| GET | /api/admin/permissions | ROLE_MANAGE | 부여 가능한 전체 권한 목록 |

- `name`은 코드용 영어 식별자, `description`은 화면 표시용 한글. 응답·입력 모두 description을 포함한다.
- 모든 수정·삭제·권한변경은 4.3절 위계 검증을 따른다(자기보다 높은 priority 역할 불가, 같은 레벨까지 허용).

### 5.5 설교 (Sermon)
| 메서드 | 경로 | 권한 | 설명 |
|---|---|---|---|
| GET | /api/sermons | 공개 | 목록 (페이징, 설교자·시리즈·날짜·태그 필터, 검색 / 카드용 메타만, content 제외) |
| GET | /api/sermons/{id} | 공개 | 상세 |
| POST | /api/admin/sermons | SERMON_WRITE | 등록 |
| PUT | /api/admin/sermons/{id} | SERMON_WRITE | 전체 수정 |
| PATCH | /api/admin/sermons/{id} | SERMON_WRITE | 부분 수정 |
| DELETE | /api/admin/sermons/{id} | SERMON_WRITE | 삭제 |

설교 필드(권장): title, preacher(설교자), series(시리즈), scripture(성경 본문 구절), content(**마크다운 TEXT**, 설교 본문), video_url, audio_url, view_count, preached_at(설교일), created_by(FK → members, 최초 작성자), updated_by(FK → members, 마지막 수정자=표시용 작성자), created_at, updated_at, deleted_at. 태그는 content_tags로 연결(생성·수정 시 `tagIds` 수신). 기본 정렬: `preached_at DESC`. 목록 카드 표시 항목: 제목·태그·조회수·설교일·작성자(content 제외).

### 5.6 일정/행사 (Event)
| 메서드 | 경로 | 권한 | 설명 |
|---|---|---|---|
| GET | /api/events | 공개 | 목록 (`?year=&month=` 또는 `?startDate=&endDate=` 범위) |
| GET | /api/events/{id} | 공개 | 상세 |
| POST | /api/admin/events | EVENT_WRITE | 등록 |
| PUT | /api/admin/events/{id} | EVENT_WRITE | 전체 수정 |
| PATCH | /api/admin/events/{id} | EVENT_WRITE | 부분 수정 |
| DELETE | /api/admin/events/{id} | EVENT_WRITE | 삭제 |

일정 필드(권장): title, description(**마크다운 TEXT**, 행사 안내 본문), location, start_at, end_at, all_day(boolean), created_at, deleted_at. (반복 일정은 1차 범위 제외, 단건만.) 태그는 content_tags로 연결(생성·수정 시 `tagIds` 수신). 기본 정렬: `start_at`(달력·시작일 기준).

### 5.7 공지 (Notice)
| 메서드 | 경로 | 권한 | 설명 |
|---|---|---|---|
| GET | /api/notices | 공개 | 목록 (상단고정 우선 정렬 / 카드용 메타만, content 제외) |
| GET | /api/notices/{id} | 공개 | 상세 |
| POST | /api/admin/notices | NOTICE_WRITE | 등록 |
| PUT | /api/admin/notices/{id} | NOTICE_WRITE | 전체 수정 |
| PATCH | /api/admin/notices/{id} | NOTICE_WRITE | 부분 수정 (상단고정 토글 등) |
| DELETE | /api/admin/notices/{id} | NOTICE_WRITE | 삭제 |

공지 필드(권장): title, content(**마크다운 TEXT**, 공지 본문), is_pinned(boolean), view_count, created_by(FK → members, 최초 작성자), updated_by(FK → members, 마지막 수정자=표시용 작성자), created_at, updated_at, deleted_at. 태그는 content_tags로 연결(생성·수정 시 `tagIds` 수신). 기본 정렬: `is_pinned DESC, created_at DESC`(고정 먼저, 그 다음 최신). 목록 카드 표시 항목: 제목·태그·조회수·작성일·작성자(content 제외).

### 5.8 교구/부서 (Department)
| 메서드 | 경로 | 권한 | 설명 |
|---|---|---|---|
| GET | /api/departments | 공개 | 목록 |
| GET | /api/departments/{id} | 공개 | 상세 (소개, 담당 교역자, 활동) |
| POST | /api/admin/departments | DEPT_WRITE | 등록 |
| PUT | /api/admin/departments/{id} | DEPT_WRITE | 전체 수정 |
| PATCH | /api/admin/departments/{id} | DEPT_WRITE | 부분 수정 |
| DELETE | /api/admin/departments/{id} | DEPT_WRITE | 삭제 |

부서 필드(권장): name, description(**마크다운 TEXT**, 부서 소개·활동), leader(담당 교역자), parent_id(자기참조 FK, 계층 표현, nullable), sort_order, created_at, deleted_at. 기본 정렬: `sort_order ASC`(관리자 지정 순서).

### 5.9 공통 (Common)
| 메서드 | 경로 | 권한 | 설명 |
|---|---|---|---|
| GET | /api/main | 공개 | 메인페이지 통합(최신 설교 + 공지 + 다가오는 일정), Redis 캐싱 |

교회 소개·연혁·비전·오시는 길 등 상수성 정보는 **백엔드에 두지 않고 프론트엔드에 하드코딩**한다(거의 변경되지 않고, 교회별 프론트가 어차피 분리되어 있어 "백엔드는 동일" 원칙에 부합).

### 5.10 미디어 라이브러리 (Media)

업로드와 본문 작성을 분리한 **중앙 미디어 라이브러리** 방식이다(구글 플레이 에셋 라이브러리·깃허브 첨부와 동일 패턴). 파일을 먼저 업로드해 풀(pool)에 쌓고, 글 작성 시 라이브러리에서 선택해 본문에 `media:{id}`로 삽입한다. 도메인에 독립적이며 특정 글에 종속되지 않는다.

라이브러리는 **이미지와 PDF를 모두 수용**한다. 이미지는 본문 삽입·갤러리가 사용하고, PDF는 주보가 사용한다. 영상은 직접 업로드하지 않고 외부 링크(`video_url`)로 처리한다. 모든 파일은 같은 media 테이블에 저장하되 `mime_type`으로 용도를 구분하며, 라이브러리 UI는 타입(이미지/PDF)으로 필터링한다. 본문 삽입 시 이미지는 `![](media:{id})`, PDF는 링크 `[제목](media:{id})` 형태로 넣는다.

| 메서드 | 경로 | 권한 | 설명 |
|---|---|---|---|
| POST | /api/admin/media | MEDIA_MANAGE | 이미지·PDF 업로드 → `{ id, filename, mimeType, size, ... }` 반환 |
| GET | /api/admin/media | MEDIA_MANAGE | 미디어 목록 (라이브러리 그리드용, 페이징, mime/날짜 필터) |
| GET | /api/admin/media/{id} | MEDIA_MANAGE | 미디어 단건 정보 |
| GET | /api/admin/media/{id}/references | MEDIA_MANAGE | 이 미디어를 참조하는 글 목록 반환 |
| DELETE | /api/admin/media/{id} | MEDIA_MANAGE | 삭제 (참조 있으면 차단) |
| GET | /api/media/{id} | 공개 | 실제 파일 접근(서빙/다운로드) — 본문 이미지 렌더·PDF 열람용 |

**media 테이블**
| 컬럼 | 타입 | 비고 |
|---|---|---|
| id | BIGINT PK | 본문에서 `media:{id}`로 참조 |
| filename | VARCHAR | 원본 파일명 |
| stored_path | VARCHAR | volume 내 실제 저장 경로 |
| mime_type | VARCHAR | image/jpeg, application/pdf 등 |
| size | BIGINT | 바이트 |
| uploaded_by | BIGINT FK → members | 업로더 |
| created_at | TIMESTAMP | |

**참조 추적 (본문 LIKE + 갤러리 FK):**
- 별도 참조 연결 테이블을 두지 않는다. 본문 텍스트의 `media:{id}`와 갤러리의 FK가 곧 진실이다.
- 본문 필드(설교·공지·이벤트·부서·갤러리 앨범 description)는 `media:{id}` 문자열을 LIKE로 검색하고, 갤러리 사진은 `gallery_photos.media_id` FK를 정확히(`=`) 매칭한다. 둘을 합친 결과가 참조 목록이다.
```sql
SELECT 'sermon' AS type, id, title FROM sermons        WHERE content     LIKE '%media:42%' AND deleted_at IS NULL
UNION ALL
SELECT 'notice',      id, title FROM notices            WHERE content     LIKE '%media:42%' AND deleted_at IS NULL
UNION ALL
SELECT 'event',       id, title FROM events             WHERE description LIKE '%media:42%' AND deleted_at IS NULL
UNION ALL
SELECT 'department',  id, name  FROM departments        WHERE description LIKE '%media:42%' AND deleted_at IS NULL
UNION ALL
SELECT 'gallery_album', id, title FROM gallery_albums   WHERE description LIKE '%media:42%' AND deleted_at IS NULL
UNION ALL
SELECT 'gallery_photo', a.id, a.title FROM gallery_photos p
                                               JOIN gallery_albums a ON a.id = p.album_id
WHERE p.media_id = 42 AND a.deleted_at IS NULL
UNION ALL
SELECT 'bulletin', id, title FROM bulletins WHERE media_id = 42 AND deleted_at IS NULL;
```
- 본문 LIKE는 교회 규모(글 수백 개)에서 문제없고, 갤러리 FK 매칭은 인덱스로 즉시 처리된다. 향후 글 폭증 시 본문에 `pg_trgm` 인덱스 추가(코드 불변).

**삭제 정책 (차단형):**
- 본문(`media:{id}`)이든 갤러리(`gallery_photos.media_id`)든 참조가 하나라도 있으면 삭제를 거부하고 **409 Conflict + 참조 목록**을 반환한다. 쓰고 있는 사진이 사라져 본문·갤러리가 깨지는 상태를 원천 차단한다.
- 응답으로 받은 참조 목록을 프론트가 보여주고, 각 항목에서 해당 글 편집 페이지로 이동(리다이렉션)하는 버튼을 제공한다. 사용자는 본문에서 이미지를 제거 → 참조가 0이 되면 삭제 가능.
- 참조 없으면 즉시 삭제(파일 + 레코드).

`GET /references` 정상 응답 예시(조회용):
```json
{
  "mediaId": 42,
  "inUse": true,
  "references": [
    { "type": "notice", "id": 7,  "title": "2026 부활절 안내" },
    { "type": "sermon", "id": 15, "title": "산상수훈 강해 3" }
  ]
}
```

`DELETE` 차단 시 응답(공통 에러 형식 + 참조 목록 동봉):
```json
{
  "errorCode": "MEDIA_IN_USE",
  "title": "사용 중인 미디어",
  "status": 409,
  "detail": "이 미디어를 참조하는 콘텐츠가 있어 삭제할 수 없습니다.",
  "instance": "/api/admin/media/42",
  "references": [
    { "type": "notice", "id": 7,  "title": "2026 부활절 안내" },
    { "type": "sermon", "id": 15, "title": "산상수훈 강해 3" }
  ]
}
```

**고아 파일 정책:** 어느 글에서도 안 쓰는 미디어가 라이브러리에 쌓이는 것은 정상으로 간주한다(구글 드라이브와 동일). 자동 정리하지 않으며, 관리자가 라이브러리에서 직접 삭제(차단형)로 관리한다.

### 5.11 태그 (Tag)

설교·공지·이벤트·갤러리 앨범 등 콘텐츠를 분류하는 태그(예: 예배, 선교, 봉사). 직분과 같은 발상으로 **데이터로 관리**하여 코드 수정 없이 추가/삭제한다. 한 교회 안에서 모든 콘텐츠 도메인이 **하나의 글로벌 태그 풀을 공유**한다(태그 풀은 교회별로 따로다) — '선교' 태그 하나로 설교·공지·이벤트·갤러리를 가로질러 묶을 수 있다.

| 메서드 | 경로 | 권한 | 설명 |
|---|---|---|---|
| GET | /api/tags | 공개 | 태그 목록 (필터·작성 폼용) |
| POST | /api/admin/tags | TAG_MANAGE | 태그 추가 |
| PATCH | /api/admin/tags/{id} | TAG_MANAGE | 태그 수정 |
| DELETE | /api/admin/tags/{id} | TAG_MANAGE | 태그 삭제 (연결도 함께 정리) |

**tags 테이블**
| 컬럼 | 타입 | 비고 |
|---|---|---|
| id | BIGINT PK | |
| name | VARCHAR | 예배/선교/봉사… 교회 안에서 유일 `(church_id, name)` |
| created_at | TIMESTAMP | |

**content_tags 테이블** (다형 연결, 콘텐츠 ↔ 태그 다대다)
| 컬럼 | 타입 | 비고 |
|---|---|---|
| tag_id | BIGINT FK → tags | |
| resource_type | VARCHAR | sermon / notice / event / gallery_album |
| resource_id | BIGINT | |
| PK | (tag_id, resource_type, resource_id) | |

- 콘텐츠 생성·수정 시 본문과 함께 `tagIds: [1, 3]`을 받아 연결을 갱신한다(설교·공지·이벤트·갤러리 앨범 공통).
- 목록 조회는 태그 필터를 받는다: `GET /api/sermons?tagId=2`, `GET /api/notices?tagId=2`, `GET /api/events?tagId=2`, `GET /api/gallery/albums?tagId=2`(갤러리는 GALLERY_VIEW 필요).
- 콘텐츠 상세·목록 응답에 연결된 태그 목록(`tags: [{id, name}]`)을 포함한다.
- 태그 삭제 시 `content_tags`의 연결 row를 함께 정리(CASCADE)한다. 콘텐츠 자체는 영향받지 않으며, 글로벌 풀이라 삭제 영향 범위가 여러 도메인에 걸친다는 점에 유의한다.
- **설계 노트(트레이드오프):** `content_tags`는 `resource_type` + `resource_id` 조합의 polymorphic 연결이다. 단일 글로벌 태그 풀을 모든 도메인이 공유하기 위한 선택으로, DB의 FK 참조 무결성이 `resource_id`에는 적용되지 않는다(어느 도메인을 가리키는지 런타임에 결정되므로). 따라서 **참조 무결성은 애플리케이션 레벨에서 보장**한다 — 연결 생성 시 대상 리소스 존재를 검증하고, 콘텐츠 삭제(soft delete) 시 관련 content_tags 정리를 함께 처리한다.

### 5.12 갤러리 (Gallery)

교회 홈페이지의 사진 갤러리 **페이지**. 앨범(묶음) 단위로 사진을 전시한다. **회원 전용 열람** — `GALLERY_VIEW` 권한(= 승인된 교인, `MEMBER` 역할)이 있어야 조회할 수 있다. 단순 가입자(USER)·비로그인 방문자는 차단된다. 이 점이 공개 조회인 다른 도메인과 다르다.

| 메서드 | 경로 | 권한 | 설명 |
|---|---|---|---|
| GET | /api/gallery/albums | GALLERY_VIEW | 앨범 목록 (회원 전용, `?tagId=` 필터 지원) |
| GET | /api/gallery/albums/{id} | GALLERY_VIEW | 앨범 상세 + 사진 목록 (회원 전용) |
| POST | /api/admin/gallery/albums | GALLERY_WRITE | 앨범 생성 (`tagIds`로 태그 연결) |
| PATCH | /api/admin/gallery/albums/{id} | GALLERY_WRITE | 앨범 수정 (`tagIds` 갱신) |
| DELETE | /api/admin/gallery/albums/{id} | GALLERY_WRITE | 앨범 삭제 (gallery_photos 연결만 정리, media 원본은 라이브러리에 보존) |
| POST | /api/admin/gallery/albums/{id}/photos | GALLERY_WRITE | 앨범에 사진 추가. 본문에 `mediaIds`(기존 라이브러리 선택) 또는 파일 업로드(=media 자동 생성 후 연결) |
| DELETE | /api/admin/gallery/photos/{photoId} | GALLERY_WRITE | 앨범에서 사진 연결 해제 (media 원본은 라이브러리에 유지) |

**gallery_albums 테이블**
| 컬럼 | 타입 | 비고 |
|---|---|---|
| id | BIGINT PK | |
| title | VARCHAR | 예: 2026 부활절, 여름 수련회 |
| description | TEXT | 마크다운 (선택). 본문 이미지는 `media:{id}` 참조, 미디어 삭제 차단 검색 대상 |
| created_by | BIGINT FK → members | 최초 작성자 |
| updated_by | BIGINT FK → members | 마지막 수정자(표시용 작성자) |
| created_at | TIMESTAMP | |
| updated_at | TIMESTAMP | |
| deleted_at | TIMESTAMP | soft delete |

- 앨범 대표(썸네일) 이미지는 별도 컬럼 없이 **`sort_order`가 가장 앞선 사진(첫 사진)** 을 대표로 사용한다. 사진 순서를 바꾸면 대표도 따라 바뀐다.

**gallery_photos 테이블**
| 컬럼 | 타입 | 비고 |
|---|---|---|
| id | BIGINT PK | |
| album_id | BIGINT FK → gallery_albums | |
| media_id | BIGINT FK → media | 실제 이미지는 media 재사용 |
| caption | VARCHAR | 사진 설명 (선택) |
| sort_order | INT | 앨범 내 정렬 (첫 사진=대표). 사진 목록은 `sort_order ASC` |
| created_at | TIMESTAMP | |

- 갤러리 사진은 별도 저장하지 않고 **`media` 테이블을 재사용**한다. 모든 이미지는 예외 없이 media를 거친다 — 기존 라이브러리에서 골라 연결하거나(`mediaIds`), 새 파일을 업로드하면 내부적으로 media 레코드를 먼저 생성한 뒤 gallery_photos가 참조한다(깃허브식 "업로드=라이브러리 등록"). 중앙 라이브러리 단일 원칙을 유지한다.
- `gallery_photos`에서 사진을 제거하거나 앨범을 삭제하는 것은 **연결 해제**일 뿐, media 원본은 라이브러리에 그대로 남는다. 사진·PDF의 실제 삭제는 **오직 중앙 미디어 라이브러리에서 차단형 삭제**(`DELETE /api/admin/media/{id}`)로만 일어난다. 그 삭제는 갤러리·주보·본문 참조를 모두 검사해 하나라도 쓰이면 막는다.
- 회원 전용이므로 `GET /api/gallery/**`는 인증 + `GALLERY_VIEW` 검증을 거친다. 갤러리에는 교인 얼굴·행사 사진 등 개인정보성 이미지가 포함될 수 있어 공개 노출을 막는 것이 목적이다.
- 앨범은 공지·이벤트와 동일한 **글로벌 태그 풀**을 공유한다(`content_tags`의 `resource_type = gallery_album`). 생성·수정 시 `tagIds`로 연결.

### 5.13 주보 (Bulletin)

교회 홈페이지의 주보 페이지. 날짜별 주보 PDF를 전시한다. 갤러리와 평행한 구조지만 **공개 조회**(다른 교회 관례에 맞춤)이고 PDF를 다룬다는 점이 다르다. 조회는 누구나, 쓰기만 `BULLETIN_WRITE`로 막는다(설교·공지와 같은 패턴).

| 메서드 | 경로 | 권한 | 설명 |
|---|---|---|---|
| GET | /api/bulletins | 공개 | 목록 (주보 날짜 내림차순, 페이징) |
| GET | /api/bulletins/{id} | 공개 | 단건 (PDF 접근 정보 포함) |
| POST | /api/admin/bulletins | BULLETIN_WRITE | 업로드 (PDF media 생성 + 연결, 또는 기존 mediaId 연결) |
| PATCH | /api/admin/bulletins/{id} | BULLETIN_WRITE | 수정 |
| DELETE | /api/admin/bulletins/{id} | BULLETIN_WRITE | 삭제 (bulletin 레코드만 제거, PDF media 원본은 라이브러리에 보존) |

**bulletins 테이블**
| 컬럼 | 타입 | 비고 |
|---|---|---|
| id | BIGINT PK | |
| title | VARCHAR | 예: 2026-06-01 주보 |
| service_date | DATE | 주보 날짜. 기본 정렬 `service_date DESC`(최신 먼저) |
| media_id | BIGINT FK → media | 주보 PDF (application/pdf). 주보 1건당 PDF 1개 |
| created_by | BIGINT FK → members | 최초 작성자 |
| updated_by | BIGINT FK → members | 마지막 수정자(표시용 작성자) |
| created_at | TIMESTAMP | |
| updated_at | TIMESTAMP | |
| deleted_at | TIMESTAMP | soft delete |

- 주보 PDF도 **`media` 테이블을 재사용**한다(이미지와 같은 라이브러리, mime_type으로 구분). 갤러리와 동일하게 업로드 시 media 생성 후 참조하거나 기존 PDF를 선택해 연결.
- media 원본 삭제는 차단형을 따르며, 미디어 참조 검색에 `bulletins.media_id` FK가 포함되어 주보가 쓰는 PDF는 라이브러리에서 지울 수 없다.
- 공개 조회이므로 `GET /api/bulletins/**`는 인증 불필요.

---

### 5.14 문의 (Inquiry)

홈페이지 방문자가 교회에 문의를 남기는 창구. **답변은 담당자가 이메일/문자로 직접 발송**하므로 서버는 답변을 저장하지 않고 **처리 여부만 추적**한다(스펙 §2 No-SMTP 원칙 유지). 문의에는 이름·연락처가 담기므로 **조회부터 권한이 필요**하다(공개 읽기 아님).

| 메서드 | 경로 | 권한 | 설명 |
|---|---|---|---|
| POST | /api/inquiries | 공개 | 문의 등록 (비로그인). 접수 번호(id)만 반환 |
| GET | /api/admin/inquiries | INQUIRY_MANAGE | 목록 (최신순, 페이징, `?completed=true\|false` 필터) |
| GET | /api/admin/inquiries/{id} | INQUIRY_MANAGE | 단건 (문의 내용 포함) |
| PATCH | /api/admin/inquiries/{id}/complete | INQUIRY_MANAGE | 완료 체크 / 완료 취소 토글 |
| DELETE | /api/admin/inquiries/{id} | INQUIRY_MANAGE | 삭제 (soft delete) |

**inquiries 테이블**
| 컬럼 | 타입 | 비고 |
|---|---|---|
| id | BIGINT PK | 접수 번호 |
| name | VARCHAR(50) | 필수 |
| phone | VARCHAR(20) | 필수. `members.phone` 관례대로 digits-only 정규화 저장 |
| email | VARCHAR(100) | 선택 |
| content | TEXT | 필수, 최소 10자. 일반 텍스트(마크다운 아님 → `media:{id}` 참조 없음) |
| privacy_agreed_at | TIMESTAMP | 개인정보 수집·이용 동의 시각. 동의(`privacyAgreed=true`) 없으면 400 |
| completed_at | TIMESTAMP | NULL=미처리, 값=완료. 완료 취소 시 다시 NULL |
| created_by | BIGINT FK → members | 익명 제출이라 항상 NULL |
| updated_by | BIGINT FK → members | 완료 처리한 관리자 |
| created_at / updated_at / deleted_at / version | | BaseEntity |

- 등록 API는 공개라 **IP당 시간당 5건** 레이트리밋을 둔다(Redis `inquiry:rl:{ip}` INCR + TTL 1h). 초과 시 `429 RATE_LIMIT_EXCEEDED`. CAPTCHA는 두지 않는다 — 스팸이 실제로 뚫리면 그때 올린다.
- 문의 내용은 등록 후 수정하지 않는다. 관리자가 바꾸는 값은 완료 플래그뿐이고, 완료 토글은 멱등이라 요청에 `version`을 요구하지 않는다(JPA `@Version`은 백스톱으로 유지).
- 답변 본문·상태 머신(대기/진행중/완료)·관리자 메모는 **의도적 비기능**. 필요해지면 컬럼을 하나씩 추가한다.

---

## 6. 인덱스 전략

soft delete를 전제로 하므로 **모든 목록 조회 인덱스는 `WHERE deleted_at IS NULL` 부분 인덱스**로 만든다(삭제된 행을 인덱스에서 제외해 크기·성능 최적화). 각 도메인의 기본 정렬·필터 기준에 맞춘 인덱스를 둔다.

**목록·정렬 인덱스는 `church_id`를 선두 컬럼으로 갖는다**(V19, 예: sermons `(church_id, preached_at DESC) WHERE deleted_at IS NULL`). 아래 표는 그 뒤 컬럼만 적었다. 부모 id로만 찾는 인덱스(`content_tags`, `gallery_photos`, `member_roles`, `role_permissions`)는 부모 id가 전 교회에 걸쳐 유일하므로 `church_id`가 없다.

| 테이블 | 인덱스 | 용도 |
|---|---|---|
| members | `(church_id, phone) WHERE deleted_at IS NULL` UNIQUE | 로그인 조회 + 번호 재활용 대응 (교회 단위) |
| members | `(uuid)` UNIQUE | 외부 식별자 조회 |
| sermons | `(preached_at DESC) WHERE deleted_at IS NULL` | 설교 목록 정렬 |
| notices | `(is_pinned DESC, created_at DESC) WHERE deleted_at IS NULL` | 공지 목록(고정 우선) |
| events | `(start_at) WHERE deleted_at IS NULL` | 일정 범위·달력 조회 |
| bulletins | `(service_date DESC) WHERE deleted_at IS NULL` | 주보 목록 |
| departments | `(sort_order) WHERE deleted_at IS NULL` | 부서 정렬 |
| positions | `(sort_order)` | 직분 정렬 |
| content_tags | `(tag_id, resource_type)` | 태그 필터 조회 |
| content_tags | `(resource_type, resource_id)` | 콘텐츠의 태그 역조회 |
| gallery_photos | `(album_id, sort_order)` | 앨범 내 사진 정렬 |
| gallery_albums | `(created_at DESC) WHERE deleted_at IS NULL` | 앨범 목록 |
| member_roles | `(member_id)`, `(role_id)` | 권한 조회·역할별 회원 조회 |
| role_permissions | `(role_id)` | 역할의 권한 펼치기 |
| media | `(mime_type, created_at DESC)` | 라이브러리 타입 필터·정렬 |
| inquiries | `(created_at DESC) WHERE deleted_at IS NULL` | 문의 목록 |
| inquiries | `(created_at DESC) WHERE deleted_at IS NULL AND completed_at IS NULL` | 미처리 문의 목록(관리자 기본 화면) |

**미디어 참조 검색의 성능 한계 (중요):** 본문 참조 추적의 `LIKE '%media:{id}%'`는 **앞 와일드카드 때문에 B-tree 인덱스를 타지 못하고 풀스캔**한다. 교회 규모(도메인별 글 수백 개)에서는 5개 테이블 UNION 풀스캔도 체감 지연이 없어 수용한다. 글이 수만 건으로 늘면 본문 컬럼에 **`pg_trgm` GIN 인덱스**를 추가해 LIKE를 가속한다(스키마·코드 변경 없이 인덱스만 추가). 갤러리·주보의 `media_id` FK 검색은 일반 인덱스로 즉시 처리된다.

---

## 7. 패키지 구조 (도메인형)

기술 계층이 아니라 **도메인 단위로 먼저 나누는** Package-by-Feature 방식을 채택한다(도메인이 또렷이 나뉘므로 응집도가 높고 도메인 추가·분리가 쉽다). 도메인 횡단 공통 요소는 `global`로, 비즈니스 도메인은 `domain` 아래에 둔다.

```
com.elipair.church
├── ChurchBackendApplication.java
│
├── global/                      // 도메인 횡단 공통
│   ├── config/                  // SecurityConfig, RedisConfig, SwaggerConfig, JpaConfig
│   ├── security/                // JWT 발급·검증 필터, 인가, 위계(priority) 검증
│   ├── exception/               // 전역 예외 핸들러(RFC 7807), 커스텀 예외
│   ├── common/                  // 공통 응답(Page 래퍼), BaseEntity, 공통 enum
│   ├── storage/                 // FileStorage 인터페이스 + LocalFileStorage 구현
│   └── tenant/                  // 멀티테넌시: TenantFilter(/church/{slug}), TenantContext, ChurchResolver
│
└── domain/
    ├── auth/                    // 로그인·토큰 재발급·로그아웃
    ├── member/                  // 회원·약관 동의 (controller/service/repository/entity/dto)
    ├── role/                    // 역할·권한 (RBAC)
    ├── position/                // 직분
    ├── sermon/
    ├── notice/
    ├── event/
    ├── department/
    ├── tag/                     // 글로벌 태그 + content_tags
    ├── media/                   // 중앙 미디어 라이브러리
    ├── gallery/                 // 앨범·사진
    └── bulletin/                // 주보
```

규칙:
- 각 도메인 패키지 안은 `controller / service / repository / entity / dto`의 작은 계층으로 나눈다. 도메인이 단순하면(예: position) 하위 폴더 없이 파일만 둬도 된다 — 과도한 패키지 분리는 피한다.
- **`global/common/BaseEntity`** (`@MappedSuperclass`)에 공통 컬럼을 모은다: `created_at`, `updated_at`(JPA Auditing), `created_by`, `updated_by`, `deleted_at`(soft delete), `version`(낙관적 락). `church_id`(`@TenantId`)는 그 부모인 `BaseTimeEntity`에 있어 모든 엔티티가 물려받는다. 수정 가능한 콘텐츠 엔티티가 이를 상속해 감사·소프트삭제·낙관락을 일관 적용한다.
- 인가·위계 검증, 전역 에러 매핑(RFC 7807), 파일 저장 추상화는 모두 `global`에 두어 도메인이 의존하게 한다(도메인 → global 단방향 의존).
- `auth`는 member 데이터를 다루지만 관심사가 인증이라 별도 패키지로 둔다(초기엔 member에 통합 후 분리해도 무방).

---

## 8. 파일 저장 (로컬 디스크 + 추상화)

- 업로드 파일은 **로컬 디스크**에 저장하고, 그 디렉터리(`FILE_UPLOAD_DIR`, 예: `/app/uploads`)를 **Docker named volume에 마운트**한다. 컨테이너를 지워도 volume이 살아있으면 파일은 보존된다.
- 저장 로직은 `FileStorage` **인터페이스로 추상화**하고, 1차 구현은 `LocalFileStorage`로 한다. 향후 교회가 커져 OCI Object Storage나 S3로 옮길 경우 구현체만 교체하면 되도록 설계한다.
- 저장 키는 `churches/{churchId}/yyyy/MM/{uuid}.ext`다(`media.stored_path`, 불투명 키). 멀티테넌시 전환 전에 올린 파일은 옛 키(`yyyy/MM/…`)를 그대로 쓴다. 키가 곧 객체 키라 S3로 옮길 때 DB 값을 바꾸지 않는다.
- 파일 접근 URL은 프론트가 `<API 베이스>/church/<slug>/api/media/{id}`로 조립한다. 본문은 `media:{id}`만 저장하므로 URL 베이스가 교회마다 달라도 본문은 불변. `FILE_BASE_URL`은 바인딩만 되고 읽는 코드가 없는 죽은 설정이다.
- 업로드 최대 크기는 운영자가 `FILE_MAX_SIZE`(바이트, 기본 10MB=10485760)로 설정한다. 코드에 박힌 고정값이 아니라 `.env`로 조정하는 값(전 교회 공용)이며, 이 한도를 **초과하면 업로드를 거부**(`413 FILE_SIZE_EXCEEDED`)할 뿐 서버가 이미지를 리사이즈·압축하지는 않는다(렌더·가공은 프론트 몫). 검증은 `FileStorage` 저장 시점에서 수행한다.
- OCI 배포 시: 업로드 volume을 OCI **Block Volume**에 연결하고 스냅샷 백업을 켤 것(권장). 단일 Compute 인스턴스 + docker compose 구성을 전제로 한다(다중 노드/OKE로 가면 공유 스토리지 필요).

---

## 9. Redis 사용처
- Refresh Token 저장, 로그아웃 토큰 블랙리스트
- `/api/main`, 설교 목록 첫 페이지 캐싱 (`@Cacheable`)
- 콘텐츠 CUD 시 해당 캐시 키 무효화 (`@CacheEvict`)
- 설교·공지 조회수(view_count) 카운팅 후 주기적으로 DB 반영
- 로그인 실패 횟수 제한(§4.1), 문의 등록 제한(§5.14)
- **교회 분리**: 메인 캐시 키 접두사는 `main::{churchId}::`, 조회수 버퍼 키는 `view:{churchId}:{namespace}:{id}`다. flush 작업은 교회별로 묶어 `TenantContext.runAs`로 반영한다. Refresh Token·블랙리스트 키는 uuid·jti가 전역 유일이라 교회를 넣지 않는다.

---

## 10. 환경변수 (.env로 분리, git 제외)

```
# 첫 교회 — V18 마이그레이션이 churches 첫 행을 만들 때 한 번 읽는다(필수, 이후에도 지우지 않는다)
CHURCH_NAME=은혜교회
CHURCH_SLUG=grace            # API 경로 접두사 /church/<slug>
CORS_ALLOWED_ORIGIN=https://gracechurch.kr   # 첫 교회의 프론트 origin

# 접두사 없는 /api/** 요청을 처리할 교회(로컬·전환 기간 전용, 선택). 비우면 404
TENANT_DEFAULT_SLUG=

# PostgreSQL
DB_URL=jdbc:postgresql://postgres:5432/church_db
DB_USERNAME=church_user
DB_PASSWORD=<고유값>

# Redis
REDIS_HOST=redis
REDIS_PORT=6379
REDIS_PASSWORD=<고유값>

# JWT — 플랫폼 전체에 하나(전 교회 공용)
JWT_SECRET=<고유 시크릿>
JWT_ACCESS_EXPIRY=3600       # Access 토큰 1시간 (초)
JWT_REFRESH_EXPIRY=1209600   # Refresh 토큰 14일 (초)

# 파일 저장 (로컬 디스크 / Docker volume 마운트)
FILE_UPLOAD_DIR=/app/uploads
FILE_BASE_URL=https://api.example.kr/api/media   # 바인딩만 되고 쓰이지 않는다(§8)
FILE_MAX_SIZE=10485760       # 업로드 최대 크기(바이트). 운영자 조정값, 기본 10MB. 초과 시 업로드 거부
```

`application.yml`은 위 변수를 `${DB_URL}` 형태로 읽는다. 이 값들은 **플랫폼 공용**이다 — 교회별 값(이름·slug·프론트 origin)은 환경변수가 아니라 `churches` 테이블에 있고, 두 번째 교회부터는 `.env`를 건드리지 않는다. CORS는 요청마다 그 교회의 `frontend_origin` 하나만 허용하며, `CORS_ALLOWED_ORIGIN`은 V18이 첫 교회 origin으로 읽은 뒤에는 런타임에서 쓰이지 않는다. `.env.example`만 git에 커밋하고 `.env`는 `.gitignore`에 넣는다.

---

## 11. 인프라 (Docker)
- `docker compose`로 postgres(16-alpine), redis(7-alpine), backend 3개 컨테이너 구성.
- 컨테이너 간 통신은 같은 도커 네트워크 내에서 컨테이너명으로(예: `postgres`, `redis`). DB·Redis 포트는 호스트로 노출하지 않는다(보안).
- backend는 postgres·redis가 healthy해진 뒤 시작(`depends_on` + healthcheck).
- 업로드 파일용 named volume을 backend 컨테이너의 `FILE_UPLOAD_DIR`에 마운트. postgres·redis 데이터도 각각 named volume.
- 모든 비밀번호·시크릿은 `.env`에서 주입.
- 배포 대상은 OCI 단일 Compute 인스턴스를 전제 — 이 한 대가 모든 교회를 서비스한다. 데이터 volume은 OCI Block Volume 연결 + 스냅샷 백업 권장.
- 운영에서 backend 포트는 `127.0.0.1:8080`에만 연다(`docker-compose.prod.yml`). 외부 요청은 host 네트워크의 Caddy(80/443)를 거친다.

---

## 12. 새 교회 등록 체크리스트
코드 수정·재배포·DNS 작업이 없다. 정본 절차는 `docs/setup-new-church.md`.
1. 운영 서버에 SSH 접속 → `cd /srv/church-backend && ./scripts/new-church.sh`.
2. 교회 이름, slug, 프론트 주소, 교회 대표자 전화번호·이름·임시 비밀번호를 입력한다. 스크립트가 `churches` 행 + 기본 역할 4종 + 대표자(SUPER_ADMIN)를 원자적으로 만든다.
3. 출력된 `NEXT_PUBLIC_API_BASE=https://<공용API>/church/<slug>`를 그 교회 프론트 환경변수에 넣고 빌드·배포한다.
4. `curl -s https://<공용API>/church/<slug>/api/main`이 200인지 확인한다.
5. 대표자에게 전화번호와 임시 비밀번호를 전달한다. 대표자는 로그인해 비밀번호를 바꾼다.

교회 등록 API·관리 화면은 없다(의도). 권한을 `MEMBER`에 추가하는 마이그레이션을 쓰면 `scripts/new-church.sql`도 함께 고친다.
