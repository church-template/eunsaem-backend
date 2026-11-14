# 교회 홈페이지 프론트엔드 연동·동작 가이드

이 문서는 백엔드를 프론트에서 다룰 때 필요한 3종 문서 중 "얇은 동작 레이어"다. 필드별 스키마·요청/응답 구조·상태코드의 단일 진실은 `api-docs.json`(OpenAPI)이고, 왜 이렇게 설계됐는지는 `docs/church-backend-spec.md`(설계 청사진)에 있다. 이 문서는 그 둘이 담지 못하는 **횡단 동작·흐름·규칙·UI 게이팅·엣지케이스**만 다룬다. 필드·상태코드를 나열하지 않고, "이 응답을 받았을 때 프론트가 무엇을 해야 하는가"에 집중한다.

## 0. 시작하기

### 0.1 3종 문서 역할

| 문서 | 역할 | 무엇을 볼 때 |
|---|---|---|
| `api-docs.json` (OpenAPI) | **스키마 단일 진실** — 경로·요청/응답 필드·상태코드 | "이 엔드포인트가 정확히 어떤 필드를 받고 주는가" |
| `docs/church-backend-spec.md` | **설계 청사진** — 도메인 모델·의사결정 배경 | "왜 이렇게 설계됐는가" |
| **이 문서** | **횡단 동작·UI 게이팅** — 흐름·규칙·엣지케이스 | "응답을 받았을 때 프론트가 무엇을 해야 하는가" |

원칙: 필드명·상태코드가 헷갈리면 OpenAPI를 먼저 보라. 이 문서는 "OpenAPI에 없는 동작 규칙"을 메운다. 노트에 근거 없는 세부는 본문에서 "OpenAPI 참조"로 위임한다.

### 0.2 baseURL · 인증 헤더

- **baseURL에는 교회 접두사가 들어간다**: `NEXT_PUBLIC_API_BASE=https://<공용API>/c/<slug>` (예: `https://api.eunsaem.com/c/<slug>`). API 서버 하나를 모든 교회가 공유하고(멀티테넌시), 서버는 경로 접두사 `/c/<slug>`로 어느 교회 요청인지 정한다. 값은 교회 등록 스크립트가 출력해 준다(`docs/setup-new-church.md`).
- 모든 API는 그 baseURL 아래 `/api/**` 프리픽스다 — 실제 호출은 `https://<공용API>/c/<slug>/api/...`. 이 문서와 OpenAPI의 경로(`/api/...`)는 접두사를 생략한 것이므로, **모든 요청(fetch·서버 사이드 호출·`<img src>`·PDF 링크)을 반드시 `NEXT_PUBLIC_API_BASE`를 거쳐 조립**한다. 접두사가 빠지거나 slug가 틀리면 `404 RESOURCE_NOT_FOUND`다.
- Swagger UI(`/docs/swagger-ui.html`)와 스펙(`/v3/api-docs`)은 접두사 없이 공용 도메인 바로 아래에 있다(둘 다 공개).
- 보호 요청은 모두 `Authorization: Bearer <accessToken>` 헤더 필요. 서버는 **STATELESS**(쿠키·세션 없음) — 토큰은 클라이언트가 보관·재전송한다.
- **토큰은 교회별이다.** access 토큰에는 발급한 교회의 id(`cid`)가 들어 있어, 다른 교회 접두사로 보내면 `401 INVALID_TOKEN`이다. refresh도 발급받은 교회 접두사에서만 된다. 계정도 교회별이라 같은 전화번호로 교회마다 따로 가입한다.
- **CORS**: 서버는 요청마다 **그 교회에 등록된 프론트 origin 하나**(`churches.frontend_origin`)만 허용한다(`allowCredentials(true)`, `*` 불가). 프론트 도메인이 교회 등록 때 입력한 주소와 글자 그대로 일치해야 한다(`www` 유무·스킴 포함). 도메인을 바꾸면 운영자에게 DB 값 변경을 요청한다.

### 0.3 프론트가 알아야 할 env

프론트는 토큰 만료값·교회 고유값을 **하드코딩하지 말 것**. 프론트가 직접 의존하는 값은 `NEXT_PUBLIC_API_BASE` 하나뿐이다.

| 키 | 용도 | 프론트 사용처 |
|---|---|---|
| `NEXT_PUBLIC_API_BASE` (프론트 env) | 교회 접두사를 포함한 API 베이스 `https://<공용API>/c/<slug>` | 모든 API 호출과 미디어 URL의 앞부분. `media:{id}` → `${NEXT_PUBLIC_API_BASE}/api/media/{id}`(5장). `next/image` 허용 패턴도 접두사를 포함한 경로(`/c/<slug>/api/media/**`)여야 한다 |
| `FILE_BASE_URL` (서버 env) | — | 서버에 바인딩만 되고 쓰이지 않는 설정이다. 프론트는 의존하지 않는다 |
| `JWT_ACCESS_EXPIRY` / `JWT_REFRESH_EXPIRY` | 토큰 수명(1h / 14d) | **프론트가 하드코딩 금지** — 만료는 401 응답으로 감지하고 refresh로 대응(1장) |

> 교회 이름·도메인 같은 고유값은 프론트 빌드 시 주입한다(12장).

## 1. 인증·세션 수명주기

### 1.1 가입 → 승인 모델 (이메일 인증 없음)

이 백엔드에는 **SMTP·이메일 인증이 없다.** 신원 확인은 관리자가 `MEMBER` 역할을 부여하는 것으로 대체된다.

1. `POST /api/auth/signup`(공개, **201**) — 응답에 **토큰 없음**. `SignupResponse`(uuid·name·phone·roles)만. 가입 직후 로그인 상태가 아니므로 **프론트가 별도로 `login`을 호출**해야 한다.
2. 가입 시 자동으로 `USER` 역할(권한 0)만 부여된다. 갤러리 등 회원 전용 기능은 아직 차단.
3. 관리자가 `MEMBER` 역할을 부여하면(9장 아님, 7장 회원 관리) **교인 승인** 완료 → `GALLERY_VIEW` 획득.

**가입 폼에서 프론트가 미리 막아야 매끄러운 검증**: password ≥ 8자, `termsAgreed`·`privacyAgreed` **둘 다 true**(아니면 400), email은 선택(형식 검증), phone 중복은 서버가 **409 `DUPLICATE_RESOURCE`**.

### 1.2 로그인 응답 활용

`POST /api/auth/login`(전화번호 + 비밀번호) 응답 = `{ tokens, member, requiresAgreement }`.

- `tokens` = `{ accessToken, refreshToken }` (**항상 중첩**).
- `member` = `MemberSummary`(uuid·name·phone·position·roles) — **표시용**. 권한 판단엔 쓰지 말 것(2장).
- `requiresAgreement` = boolean. `true`면 **재동의 플로우로** 보낸다(9장).
- **로그인 실패는 전화번호 존재 여부와 무관하게 항상 동일한 `401 AUTHENTICATION_FAILED`.** "없는 번호" vs "비번 오류"를 구분 표시하면 안 된다 → 단일 메시지("전화번호 또는 비밀번호가 올바르지 않습니다").
- **로그인 시도 제한**: 실패가 15분 안에 같은 전화번호·IP로 10회(또는 IP당 100회)를 넘으면 `429 RATE_LIMIT_EXCEEDED` → "잠시 후 다시 시도" 안내(`detail` 표시).

### 1.3 토큰 저장·재발급·로그아웃

- Access(기본 1h) / Refresh(기본 14d)를 클라이언트가 보관.
- **다중 기기 동시 로그인 허용** — 로그인마다 refresh가 jti 단위로 독립 저장된다. 한 기기 로그아웃이 다른 기기를 끊지 않는다.
- `POST /api/auth/refresh` → `{ tokens }`만 반환(`member`·`requiresAgreement` 없음). **access만 새로 발급**, `refreshToken`은 보낸 값을 그대로 echo(회전 없음). 서버는 refresh 시 **DB에서 권한을 재조회**해 새 access에 반영한다.
- refresh 실패(만료·revoke·위변조·탈퇴) → **`401 INVALID_TOKEN`** → 로그인 화면으로.
- `POST /api/auth/logout`(인증 필요, **204**): 헤더에 access + 본문에 refreshToken을 함께 보낸다. 현재 access를 즉시 블랙리스트, 본인 refresh를 revoke. **멱등** — 무효/타인 refresh여도 조용히 204. 다른 기기 세션엔 영향 없음.

### 1.4 401 처리 분기 (status만으로 구분 불가)

401이 두 종류라 `errorCode`로 분기해야 한다.

| errorCode | 의미 | 프론트 처리 |
|---|---|---|
| `AUTHENTICATION_FAILED` | 로그인 자격증명 실패 | 로그인 폼에 단일 오류 메시지 |
| `INVALID_TOKEN` | 토큰 없음·만료·무효·블랙리스트 | **access 만료면 refresh 시도 → 실패면 로그아웃** |

### 1.5 토큰값 vs `/api/members/me` (lag)

JWT의 `name`·`position`·`permissions`·`maxPriority`는 **발급 시점 스냅샷**이다.

- **권한 변경**: 다음 refresh 때 반영(refresh가 DB 재조회). access 만료(1h) 전까지 낡은 권한 유지 가능.
- **이름/직분/프로필 변경**: 다음 refresh까지 낡은 채로.
- **라이브 값이 필요한 화면**(권한 기반 UI, 프로필 표시)은 토큰이 아니라 **`GET /api/members/me`(로그인 필요)**를 신뢰하라. DB 최신값(uuid·이름·전화·이메일·직분·역할·permissions·maxPriority·약관상태)을 준다.

### 1.6 스니펫: 401 refresh 인터셉터(동시요청 큐잉)

```ts
let refreshing: Promise<string> | null = null;

async function authFetch(url: string, init: RequestInit = {}): Promise<Response> {
  const access = () => localStorage.getItem("accessToken")!;
  const withAuth = (token: string): RequestInit => ({
    ...init,
    headers: { ...init.headers, Authorization: `Bearer ${token}` },
  });

  let res = await fetch(url, withAuth(access()));
  if (res.status !== 401) return res;

  // INVALID_TOKEN만 refresh 대상. AUTHENTICATION_FAILED는 그대로 반환.
  const { errorCode } = await res.clone().json().catch(() => ({}));
  if (errorCode !== "INVALID_TOKEN") return res;

  // 동시 401들이 refresh를 한 번만 호출하도록 큐잉(공유 프로미스)
  refreshing ??= (async () => {
    const r = await fetch("/api/auth/refresh", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ refreshToken: localStorage.getItem("refreshToken") }),
    });
    if (!r.ok) { logout(); throw new Error("refresh failed"); } // 401 INVALID_TOKEN
    const { tokens } = await r.json();
    localStorage.setItem("accessToken", tokens.accessToken);   // access만 갱신됨
    return tokens.accessToken as string;
  })().finally(() => { refreshing = null; });

  const fresh = await refreshing;
  return fetch(url, withAuth(fresh)); // 원요청 재시도
}
```

## 2. 인가·권한 → UI 게이팅

### 2.1 두 축은 절대 결합하지 말 것

**직분(position)**과 **역할/권한(role/permission)**은 독립 축이다. 높은 직분(목사·장로)이라도 **권한은 0**. 메뉴·버튼 게이팅을 직분으로 하면 서버와 어긋난다. 게이팅은 **항상 `permissions`(권한 문자열)** 기준으로 한다.

- JWT는 `roles`가 아니라 펼쳐진 **`permissions`** + `maxPriority`를 담는다. 프론트도 roles가 아니라 permissions로 show/hide를 결정해야 서버 `@PreAuthorize("hasAuthority(...)")`와 일치한다.
- 권한 이름은 **접두사 없음**(`SERMON_WRITE`, `GALLERY_VIEW` 그대로).

### 2.2 권한 ↔ 화면기능 매핑표 (실제 `@PreAuthorize` 기반)

| 권한 | 이 권한이 켜는 화면 기능 | 주요 경로 |
|---|---|---|
| `SERMON_WRITE` | 설교 작성/수정/삭제 버튼 | `POST/PUT/PATCH/DELETE /api/admin/sermons` |
| `NOTICE_WRITE` | 공지 작성/수정/삭제 | `/api/admin/notices` |
| `EVENT_WRITE` | 일정 작성/수정/삭제 | `/api/admin/events` |
| `DEPT_WRITE` | 부서 작성/수정/삭제 | `/api/admin/departments` |
| `GALLERY_WRITE` | 앨범·사진 생성/수정/삭제 | `/api/admin/gallery/**` |
| `GALLERY_VIEW` | **갤러리 조회 자체**(회원 전용) | `/api/gallery/**` |
| `BULLETIN_WRITE` | 주보 업로드/수정/삭제 | `/api/admin/bulletins` |
| `MEDIA_MANAGE` | 미디어 라이브러리(업로드/목록/삭제) | `/api/admin/media` |
| `TAG_MANAGE` | 태그 추가/수정/삭제 | `/api/admin/tags` |
| `POSITION_MANAGE` | 직분 추가/수정/삭제 | `/api/admin/positions` |
| `MEMBER_MANAGE` | 회원 조회·수정·비번초기화, 약관 일괄리셋 | `/api/members`(조회), `/api/admin/members/{uuid}`, `/api/admin/agreements/reset` |
| `ROLE_MANAGE` | 역할/권한 관리, 회원 역할 부여/회수 | `/api/admin/roles`, `/api/admin/permissions`, `/api/admin/members/{uuid}/roles` |

> **주의 — 경로만 보면 헷갈리는 곳**: 회원 조회 `GET /api/members`·`GET /api/members/{uuid}`는 `/api/admin/**`가 아니지만 **`MEMBER_MANAGE`로 메서드 보안**이 걸려 있다(경로 규칙상 공개로 보여도 실제 차단). 반대로 `GET /api/positions`, `GET /api/tags`, `GET /api/bulletins`, `GET /api/main`, `GET /api/media/{id}`는 **공개**다.

### 2.3 경로 인가 3분법

| 경로 | 규칙 | UI 함의 |
|---|---|---|
| `/api/admin/**` | **로그인만**(경로) + **메서드 `@PreAuthorize`**(권한) 2단 방어 | 로그인했어도 권한 없으면 403; 토큰 없이 호출 시 401 `INVALID_TOKEN` |
| `/api/gallery/**` | **`GALLERY_VIEW` 필요**(회원 전용, 비공개) | 비로그인·`USER`만 보유 사용자는 차단 |
| 그 외 `/api/**` 읽기 | 공개 | 비로그인 노출 가능 |

**갤러리 회원전용 차단 UX**: 갤러리 진입 시 토큰/`/members/me`에 `GALLERY_VIEW`가 없으면, 호출하지 말고 "교인 승인 후 이용 가능" 안내를 띄운다. 그대로 호출하면 비로그인은 401 `INVALID_TOKEN`, 로그인+권한없음은 403 `ACCESS_DENIED`.

### 2.4 인가 거부 → 401 vs 403

`@PreAuthorize` 거부는 인증 상태에 따라 갈린다.

- **익명/미인증** → `401 INVALID_TOKEN` (로그인/토큰 갱신 필요)
- **인증됐으나 권한 부족·위계 위반** → `403 ACCESS_DENIED`

### 2.5 priority 위계 → 버튼 비활성

역할 관련 작업은 위계 가드를 받는다. 프론트는 가능하면 **버튼을 미리 비활성**해 403 왕복을 줄인다.

| 가드 | 규칙 | 위반 시 |
|---|---|---|
| 위계(escalation) | 대상 역할 priority가 **내 `maxPriority` 초과**면 차단(같은 레벨은 허용) | 403 |
| 시스템 역할 | `isSystem=true`(`SUPER_ADMIN`/`ADMIN`/`USER`) 수정·삭제 불가 | 403 |
| 자기 보호 | **자기 자신**의 역할 부여/회수 불가 | 403 |
| 마지막 SUPER_ADMIN | 그 교회의 활성 SUPER_ADMIN이 1명이면 그 역할 회수·강등·삭제 불가 | 403 |
| 다른 회원 관리 | 관리자의 회원 정보 수정·직분 지정·비밀번호 초기화는 **대상 회원의 최고 priority가 내 `maxPriority` 이하**일 때만(같은 레벨 허용). `ADMIN`은 `SUPER_ADMIN` 회원을 바꿀 수 없다 | 403 |

> 위계 위반은 **모두 `errorCode=ACCESS_DENIED`로 동일**하다. 구체 사유는 한글 `detail` 텍스트에만 담긴다(코드로 구분 불가) → 상세 사유 표시는 `detail`을 그대로 보여준다.

> 민감 화면(권한 토글 UI)의 라이브 판단은 토큰이 아니라 `GET /api/members/me`를 읽어라(1.5).

## 3. 공통 응답 규약

### 3.1 목록 봉투 `{ content, page }`

모든 페이지네이션 목록은 Spring Data `PagedModel` 직렬화(`VIA_DTO`)로 다음 형태다. SB3식 평면 키(`pageable`, `sort`, 최상위 `totalElements`)는 **나오지 않는다.**

```json
{
  "content": [ /* 카드 객체 배열 */ ],
  "page": { "size": 10, "number": 0, "totalElements": 42, "totalPages": 5 }
}
```

`page` 하위 키는 정확히 `size`·`number`(0-base 현재 페이지)·`totalElements`·`totalPages` 4개뿐.

### 3.2 목록 카드엔 본문 없음

목록과 상세는 **다른 DTO**다. 카드에는 본문(`content`/`description`) 필드 자체가 없고, `version`도 없다. 본문·`version`은 **상세 단건 조회(`GET .../{id}`)에서만** 온다. 별도 `summary`(잘린 요약) 필드는 없다 — 카드는 메타, 본문은 상세.

### 3.3 홈 맛보기 vs 전용 페이지 vs `/api/main`

- 같은 목록을 **`size`만 달리** 호출해 홈 미리보기(`size=3`)와 전용 페이지(`size=10`)를 구분한다.
- 또는 `GET /api/main`(공개)으로 **최신 설교 3 + 공지 3 + 다가오는 일정 5**를 한 번에 받는다(10장).

### 3.4 페이징·정렬·필터 표준

- 공통: `?page=0&size=10&sort=createdAt,desc`. `page`는 0-base. 기본값은 도메인별 `@PageableDefault`(정확한 기본 정렬 필드는 10장 표 / OpenAPI 참조).
- 표준 필터 파라미터(존재 여부는 도메인별, 10장):
  - 태그: **`tagId`**(단수). 매칭 글 없으면 빈 페이지.
  - 일정 달력: `year`+`month` **또는** `startDate`+`endDate`(둘 다 `yyyy-MM-dd`, **쌍으로**).
  - 미디어·설교 날짜 범위: `from`+`to`(상한 포함).

### 3.5 토큰 봉투

`login`/`refresh` 응답의 토큰은 항상 `tokens` 객체로 중첩(`accessToken`·`refreshToken`). 1장 참조.

## 4. 에러 처리(RFC 7807 → UI)

### 4.1 봉투 구조

모든 실패는 단일 봉투로 직렬화되며 **`@JsonInclude(NON_NULL)`** — 값 없는 필드는 키째 빠진다. 보안 필터 단계(401/403)도 동일한 봉투다.

- 항상: `errorCode`(영문 UPPER_SNAKE, **분기 키**)·`title`(한글, 표시용)·`status`·`detail`·`instance`(요청 경로).
- 조건부: `errors`(검증 실패 `INVALID_INPUT_VALUE`일 때만), `references`(`MEDIA_IN_USE`일 때만). **둘은 함께 오지 않는다.**

> **분기는 반드시 `errorCode`로.** `title`/`detail`은 표시용(한글)이라 분기 키로 쓰면 안 된다. `status`만으로도 불가 — 409가 5종, 401이 2종이다. `errors`는 항상 옵셔널 처리(타입 변환·본문 파싱 실패 400엔 `errors`가 없다).

### 4.2 errorCode → UI 처리표

| status | errorCode | UI 처리 |
|---|---|---|
| 400 | `INVALID_INPUT_VALUE` | `errors[]`(있으면) 필드별 인라인 표시; 없으면 `detail` 토스트 |
| 401 | `AUTHENTICATION_FAILED` | 로그인 폼 단일 오류(가입 여부 비노출) |
| 401 | `INVALID_TOKEN` | refresh 시도 → 실패 시 로그인 화면 리다이렉트 |
| 403 | `ACCESS_DENIED` | 권한 부족/위계 위반 — `detail` 표시, 버튼 숨김 점검 |
| 404 | `RESOURCE_NOT_FOUND` | "삭제됐거나 없는 항목" 안내, 목록 복귀 |
| 409 | `MEDIA_IN_USE` | `references` 링크 노출, 삭제 차단(6장) |
| 409 | `OPTIMISTIC_LOCK_CONFLICT` | 최신본 재조회 → 재편집 안내(8장) |
| 409 | `DUPLICATE_RESOURCE` | 해당 입력 필드(전화 등)에 중복 안내 |
| 409 | `ROLE_IN_USE` | "회원에게 할당된 역할은 삭제 불가" 안내 |
| 409 | `DEPARTMENT_HAS_CHILDREN` | "하위 부서 먼저 정리" 안내 |
| 413 | `FILE_SIZE_EXCEEDED` | 파일 크기 초과(400 아님) — 한도 안내·재선택 |
| 500 | `FILE_STORAGE_ERROR` | 파일 처리 오류 — 재시도 |
| 500 | `INTERNAL_ERROR` | 일반 오류 토스트 |

### 4.3 케이스별 동작 메모

- **401 가입여부 비노출**: `AUTHENTICATION_FAILED`는 전화번호 미존재·비번 불일치를 동일 응답으로 처리. 두 경우를 구분 표시하지 말 것.
- **`MEDIA_IN_USE`**: `references`(각 `{type,id,title}`) 동봉, `detail`은 항상 `"이 미디어를 참조하는 콘텐츠가 있어 삭제할 수 없습니다."` → 참조 목록을 링크로 보여주고 삭제 막기(6장).
- **`OPTIMISTIC_LOCK_CONFLICT`**: 추가 payload 없음. "다른 사용자가 먼저 수정함" → 최신본 재조회 후 재편집(8장).

### 4.4 스니펫: 전역 errorCode 분기

```ts
type ApiError = {
  errorCode: string; title: string; status: number; detail: string;
  errors?: { field: string; reason: string }[];
  references?: { type: string; id: number; title: string }[];
};

async function handleApiError(res: Response): Promise<never> {
  const e: ApiError = await res.json();
  switch (e.errorCode) {
    case "AUTHENTICATION_FAILED":
      throw new FormError("전화번호 또는 비밀번호가 올바르지 않습니다."); // 가입여부 비노출
    case "INVALID_TOKEN":
      redirectToLogin(); break;                       // refresh는 인터셉터에서 선처리(1장)
    case "ACCESS_DENIED":
      toast(e.detail); break;                          // 위계 사유는 detail에만
    case "INVALID_INPUT_VALUE":
      if (e.errors?.length) showFieldErrors(e.errors); // 필드 인라인
      else toast(e.detail);
      break;
    case "MEDIA_IN_USE":
      showMediaReferences(e.references ?? []); break;  // 삭제 차단 + 참조 링크
    case "OPTIMISTIC_LOCK_CONFLICT":
      toast("다른 사용자가 먼저 수정했습니다. 최신 내용을 다시 불러옵니다.");
      await reloadAndReedit(); break;
    case "DUPLICATE_RESOURCE":
      markDuplicateField(); break;
    default:
      toast(e.title);                                  // ROLE_IN_USE / 413 / 500 등
  }
  throw e;
}
```

## 5. 콘텐츠 렌더링 파이프라인

### 5.1 raw 마크다운 — 서버는 변환하지 않음

본문 필드(`sermon.content`, `notice.content`, `event.description`, `department.description`, 갤러리 앨범 `description`)는 **raw 마크다운 TEXT**로 저장된다. 서버는 HTML 변환도 sanitize도 하지 않는다 → **마크다운 → HTML 변환 + 새니타이즈가 전적으로 프론트 책임.** raw HTML은 기본 비허용으로 처리하라(XSS 방지).

### 5.2 `media:{id}` → URL 치환

본문 속 이미지·PDF는 URL이 아니라 **`media:{id}` 리터럴**로 참조된다(`![alt](media:42)`, `[제목](media:42)`). 렌더 전에 실제 서빙 URL `GET /api/media/{id}`(공개)로 치환한다. 이 모델 덕에 본문이 교회별 베이스 URL과 무관하게 동일하다. **치환 결과는 교회 접두사를 포함한 절대 URL**(`${NEXT_PUBLIC_API_BASE}/api/media/{id}`)이어야 한다 — 상대경로 `/api/media/{id}`는 접두사가 없어 교회를 정할 수 없다. 다른 교회의 미디어 id는 404다.

- `GET /api/media/{id}`는 파일 바이트를 반환하며 응답에 `X-Content-Type-Options: nosniff`가 붙는다(MIME 스니핑 기반 저장형 XSS 차단). 관리용 메타 조회(`/api/admin/media/{id}`)와 다르다(6장).
- **치환 순서**: 마크다운 파싱 후 sanitize까지 끝낸 다음 `media:{id}`를 URL로 바꾸거나, sanitize 단계에서 허용 스킴/도메인을 통제하라. 어느 쪽이든 **새니타이즈는 반드시 거친다.**

### 5.3 스니펫: `media:{id}` → URL 치환 + DOMPurify 새니타이즈

```ts
import { marked } from "marked";
import DOMPurify from "dompurify";

// 경계 안전: media:42 뒤에 숫자가 이어지면 매칭 안 함(420/421 오탐 방지) — 서버 추적 규약과 동일
const MEDIA_REF = /media:(\d+)(?!\d)/g;

function renderMarkdown(raw: string): string {
  // 1) media:{id} → 공개 서빙 URL (API_BASE = NEXT_PUBLIC_API_BASE, 교회 접두사 /c/<slug> 포함)
  const withUrls = raw.replace(MEDIA_REF, (_, id) => `${API_BASE}/api/media/${id}`);
  // 2) 마크다운 → HTML
  const html = marked.parse(withUrls, { async: false }) as string;
  // 3) 새니타이즈(raw HTML/스크립트 제거) — 서버가 안 하므로 필수
  return DOMPurify.sanitize(html, { USE_PROFILES: { html: true } });
}
```

## 6. 미디어 라이브러리 워크플로

### 6.1 업로드-먼저-참조

이미지·PDF는 하나의 `media` 풀에 산다. **먼저 업로드해 `media.id`를 얻고**, 본문에서 `media:{id}`로 참조하거나(설교·공지·이벤트·부서·앨범 설명), 갤러리·주보가 FK로 재사용한다. 바이너리를 도메인 테이블에 따로 저장하는 경로는 없다.

| 동작 | 경로 | 권한 | 반환 |
|---|---|---|---|
| 업로드 | `POST /api/admin/media` (`multipart`, 필드 `file`) | `MEDIA_MANAGE` | **201** + 메타 |
| 목록 | `GET /api/admin/media` | `MEDIA_MANAGE` | 페이지(기본 `size=20`, `createdAt,desc`) |
| 메타 조회 | `GET /api/admin/media/{id}` | `MEDIA_MANAGE` | 메타(바이트 아님) |
| 참조 조회 | `GET /api/admin/media/{id}/references` | `MEDIA_MANAGE` | `{ mediaId, inUse, references[] }` |
| 삭제 | `DELETE /api/admin/media/{id}` | `MEDIA_MANAGE` | **204** 또는 409 |
| **공개 서빙** | `GET /api/media/{id}` | **공개** | 파일 바이트 |

### 6.2 업로드 규칙

- 허용 형식 **5종만**: JPEG/PNG/GIF/WEBP/PDF. **매직바이트로 형식 확정** — 확장자·Content-Type 헤더는 신뢰하지 않는다. 미허용은 디스크에 쓰지 않고 거부(`400 INVALID_INPUT_VALUE`).
- 크기 초과(기본 10MB) → **`413 FILE_SIZE_EXCEEDED`**(400 아님).
- 목록 필터: `type=image|pdf`(생략=전체, 그 외 값 400), `from`/`to`(업로드일, `yyyy-MM-dd`, **상한 포함**).

### 6.3 삭제 = 차단형(blocking)

1. 삭제 전 **`GET .../{id}/references`로 사전 조회** 권장 — `{ mediaId, inUse, references[] }`. 정상 200(에러 봉투 아님).
2. `DELETE` 시 참조가 **1건이라도** 있으면 **`409 MEDIA_IN_USE`** + `references` 동봉. 프론트는 "이 콘텐츠들에서 사용 중" 안내 + 항목(`type`/`title`) 링크 노출, **삭제 버튼은 막고 편집으로 유도**.
3. 참조가 0이 되면 그때 실제 삭제(파일+레코드).

`references[]`의 `type` 실제 값: `sermon`·`notice`·`event`·`department`·`gallery_album`·`gallery_photo`·`bulletin`. (`gallery_photo`는 소속 앨범의 id·title로 표면화.)

### 6.4 갤러리·주보 제거 = 연결 해제

앨범에서 사진 제거, 앨범 삭제, 주보 삭제는 **연결 해제(un-link)일 뿐 원본은 라이브러리에 보존**된다. 원본 실제 삭제는 위 차단형 미디어 삭제가 유일한 경로다. soft-deleted 콘텐츠는 참조로 집계되지 않는다.

## 7. 작성자 표시 정책

- 응답의 작성자 필드명은 **`author`**(타입 `String`). 회원 id나 객체가 아니라 **이미 해석된 표시 이름 문자열**이 내려온다.
- 표시 기준은 **`updated_by`(마지막 편집자)**, `created_by`(원작성자)가 아니다. 글이 수정되면 `author`가 그 시점 편집자 이름으로 바뀐다(탈퇴 작성자의 글이 편집되면 자가 치유).
- 마스킹은 **서버가 적용해 내려준다** — 프론트는 그대로 표기하면 된다(클라이언트에서 재판단 불필요):
  - 해당 회원이 탈퇴(soft-delete) → **`"(탈퇴한 사용자)"`**
  - `updated_by`가 null이거나 회원 행 없음 → **`"(알 수 없음)"`**
- `author`는 **설교·공지·갤러리·주보**에만 있고, **일정·부서엔 필드 자체가 없다**(설계상 미사용, 10장).

## 8. 동시 수정(낙관적 락)

### 8.1 흐름

설교·공지·일정·부서·갤러리 앨범·주보는 모두 `@Version` 낙관락이다.

1. 상세(`GET .../{id}`)에서 받은 **`version`을 보관**(목록 카드엔 없음).
2. 수정(`PUT`/`PATCH`) 요청 본문에 그 **`version`을 `@NotNull` 필수로 동봉**.
3. 충돌 시 **`409 OPTIMISTIC_LOCK_CONFLICT`**(추가 payload 없음) → 최신본 재조회 후 재편집 안내.

### 8.2 응답 `version` 재사용

수정 성공 응답의 `version`은 **증가 후(post-increment) 값**이다(서버가 `flush`로 즉시 반영). 연속 편집 시 직전 응답의 `version`을 다음 요청에 그대로 재사용하면 된다 — **별도 재조회 불필요.**

> 엣지: **태그만 바꾸는 수정은 `version`이 오르지 않는다**(엔티티 본 필드가 안 바뀜 — 일정·갤러리 앨범). 단 `PATCH`로 본문/태그를 안 바꿔도 도메인에 따라 version이 오를 수 있다(설교·공지). 어느 쪽이든 **응답에 실린 `version`을 신뢰**하면 안전하다.

### 8.3 스니펫: 409 낙관락 재시도

```ts
async function saveWithRetry(id: number, edit: (cur: Detail) => UpdateReq) {
  let detail = await getDetail(id);           // 최신본(version 포함)
  for (let attempt = 0; attempt < 2; attempt++) {
    const res = await authFetch(`/api/admin/sermons/${id}`, {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ ...edit(detail), version: detail.version }), // version 필수 동봉
    });
    if (res.ok) return res.json();             // 응답 version = post-increment, 그대로 재사용 가능
    const e = await res.json();
    if (e.errorCode !== "OPTIMISTIC_LOCK_CONFLICT") throw e;
    // 충돌: 최신본 재조회 → 사용자에게 변경 알리고 재편집 유도
    detail = await getDetail(id);
    if (!await confirmReedit(detail)) throw e; // 사용자가 병합/포기 결정
  }
  throw new Error("재시도 후에도 충돌");
}
```

## 9. 약관 동의 사이클

- 가입 필수 2종: `termsAgreed`·`privacyAgreed` **둘 다 true**여야 가입 성립(아니면 400).
- 로그인 응답 `requiresAgreement = !(termsAgreed && privacyAgreed)`. **true면 재동의 페이지로** 보낸다.
- 본인 약관: `GET /api/members/me/agreements`(현재 상태) / `PATCH /api/members/me/agreements`(재동의 제출). 제출은 **둘 다 true여야 성립**, 하나라도 false면 `400 INVALID_INPUT_VALUE`. 성공 시 `agreedAt` 갱신 → 다음 로그인부터 `requiresAgreement=false`.
- **관리자 일괄 리셋**: `POST /api/admin/agreements/reset`(`MEMBER_MANAGE`, **200 본문 없음**). body `target`은 **정확히 `"terms"` 또는 `"privacy"`** 하나(그 외 400, 한 번에 한 항목). 해당 플래그를 전 회원 false로 → 영향 회원은 다음 로그인 시 `requiresAgreement=true` → 재동의 유도.

## 10. 도메인별 연동 노트

각 도메인 1~2행. 정확한 필드·상태코드는 OpenAPI 참조.

| 도메인 | 기본 정렬 | 필터 | 카드 표시 항목 | tagIds | 공개 여부 | 본문(마크다운) |
|---|---|---|---|---|---|---|
| **auth** | — | — | — | — | signup/login/refresh 공개, logout 인증 | — |
| **member(me)** | — | — | uuid·name·phone·position·roles·permissions·maxPriority·약관 | — | 본인 인증 필요(익명 401) | — |
| **member(admin)** | — | — | 목록/상세 + `approved`(GALLERY_VIEW 보유 = 교인·권한보유 어드민) | — | 조회·수정 `MEMBER_MANAGE`, 역할부여/회수 `ROLE_MANAGE` | — |
| **position** | `sortOrder,asc`(비페이징 평배열) | 없음 | id·name(한글)·sortOrder | — | **목록 공개**, 쓰기 `POSITION_MANAGE` | — |
| **role** | `priority,desc`(비페이징 평배열) | 없음 | id·name·priority·isSystem·description·permissions | — | `ROLE_MANAGE` | — |
| **permission** | `name,asc`(비페이징 평배열) | 없음 | id·name(영문)·description(한글) | — | `ROLE_MANAGE` | — |
| **tag** | `name,asc`(비페이징 평배열) | 없음 | id·name(한글) | — | **목록 공개**, 쓰기 `TAG_MANAGE` | — |
| **sermon** | `preachedAt,desc` | `preacher`·`series`(완전일치)·`from`/`to`·`q`·`tagId` | id·title·preacher·series·scripture·preachedAt·viewCount·tags·author | O(create/update/patch) | 조회 공개, 쓰기 `SERMON_WRITE` | `content` (+videoUrl·audioUrl 외부링크) |
| **notice** | `isPinned,desc` + `createdAt,desc` | `q`(**제목만**)·`tagId` | id·title·isPinned·viewCount·createdAt·tags·author | O | 조회 공개, 쓰기 `NOTICE_WRITE` | `content` |
| **event** | `startAt,asc` | `year`+`month` **또는** `startDate`+`endDate`(쌍 필수)·`tagId` | id·title·location·startAt·endAt·allDay·tags (**author·viewCount 없음**) | O(태그만 수정 시 version 불변) | 조회 공개, 쓰기 `EVENT_WRITE` | `description` |
| **department** | `sortOrder,id`(**비페이징 평배열**) | 없음 | id·name·leader·parentId·sortOrder (**author·tags·viewCount 없음**) | **X**(태그 없음) | 조회 공개, 쓰기 `DEPT_WRITE` | `description` |
| **main** | 고정(설교3·공지3·일정5) | 없음 | 세 도메인 카드 메타(본문 제외) | — | **공개**(Redis 캐시, 콘텐츠 CUD 시 무효화) | — |
| **media** | `createdAt,desc`(admin) | `type`·`from`/`to` | 메타 | — | 관리 `MEDIA_MANAGE`, **서빙 `/api/media/{id}` 공개** | — |
| **gallery** | `createdAt,desc` | `tagId` | id·title·thumbnailMediaId(첫 사진, 없으면 null)·photoCount·createdAt·tags·author | O(앨범, 태그만 수정 시 version 불변) | **`GALLERY_VIEW` 회원전용**, 쓰기 `GALLERY_WRITE` | 앨범 `description` |
| **bulletin** | `serviceDate,desc` | 없음(OpenAPI 참조) | id·title·serviceDate·mediaId·author | — | **조회 공개**, 쓰기 `BULLETIN_WRITE` | 없음(PDF FK만, `GET /api/media/{mediaId}`로 열람) |

도메인 특이 동작 메모:
- **일정 달력**: 범위 파라미터는 **반드시 쌍**(한쪽만 보내면 400). 둘 다 보내면 year/month 우선. 겹침은 `end_at` 배타 경계, `end_at=null` 점 이벤트는 `start_at` 기준 포함.
- **부서**: 비페이징 평배열 → 프론트가 `parentId`로 트리 조립. 하위 부서 있으면 삭제 `409 DEPARTMENT_HAS_CHILDREN`. 루트화는 `PUT parentId=null`만(PATCH의 `parentId=null`은 미변경).
- **공지 검색**: `q`는 **제목만** 매칭(Swagger 설명은 "제목/내용"이라 적혀 있으나 코드는 제목만 — 소스가 사실).
- **조회수**: 설교·공지만 존재. 상세 조회 시 +1(부수효과).
- **갤러리 사진 추가**: `POST .../{id}/photos`(multipart) — `files`(신규 업로드) **와/또는** `mediaIds`(기존 재사용) 혼합 가능. 반환은 갱신된 앨범 상세 전체.
- **주보 업로드/수정**: `file` **XOR** `mediaId`(정확히 하나, 수정은 둘 다 생략 시 PDF 미변경). PDF 매직바이트 검증, 한도 초과 413.

## 11. 식별자·언어 규칙

- **코드용 키 = 영문**: permission `name`(`SERMON_WRITE`), role `name`(`ADMIN`/`MEMBER`/`USER`). UI는 **영문 name으로 분기**한다.
- **표시용 라벨 = 한글**: role `description`, position `name`, tag `name`. UI는 **한글 라벨을 보여준다**.
- **외부 식별자**:
  - **회원 = `uuid`** (경로 변수, JWT `sub`). BIGINT `id`는 외부 비노출.
  - **콘텐츠(설교·공지·…) = `id`** (경로 변수). 단, 역할 회수만 `roleId`(Long)를 path에 추가로 받는다.
- **전화번호**: 서버가 **숫자만 정규화**(`01012345678`)해 저장·중복검사한다. 프론트는 하이픈 등을 넣어 보내도 되지만, 중복/조회 결과는 정규화 기준이다.

## 12. 백엔드에 없는 것

- **교회 소개·연혁·비전·오시는 길** 등 거의 안 바뀌는 상수 콘텐츠는 **API에 없다** — 프론트에 하드코딩한다(프론트가 교회별이므로).
- **교회 고유값**(이름·도메인·로고 등)은 **프론트 빌드 시 주입**한다(0.3). 백엔드가 아는 교회별 값은 slug와 프론트 origin뿐이다.
- **교회를 가로지르는 계정·토큰 없음** — 한 번 로그인으로 여러 교회를 오갈 수 없다(0.2).
- **SMTP·이메일 인증 없음** — 신원 확인은 `MEMBER` 역할 부여로 대체(1.1).
- **복잡한 비밀번호 정책 없음** — 최소 길이(~8자)만, 특수문자·대소문자 강제 없음(고령 사용자 배려).
