<div align="center">

<h1>은샘교회 웹사이트 백엔드</h1>

기독교 한국침례회 은샘교회 홈페이지의 API 서버 — 다른 교회에도 다시 쓸 수 있는 템플릿

**[www.eunsaem.com](https://www.eunsaem.com)** · 프론트엔드 레포 [church-template/church-frontend](https://github.com/church-template/church-frontend)

![Spring Boot](https://img.shields.io/badge/Spring_Boot-4.0-6DB33F?logo=springboot&logoColor=white)
![Java](https://img.shields.io/badge/Java-21-007396?logo=openjdk&logoColor=white)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-4169E1?logo=postgresql&logoColor=white)
![Redis](https://img.shields.io/badge/Redis-7-DC382D?logo=redis&logoColor=white)
![Flyway](https://img.shields.io/badge/Flyway-CC0200?logo=flyway&logoColor=white)
![Docker](https://img.shields.io/badge/Docker_Compose-2496ED?logo=docker&logoColor=white)
![GitHub Actions](https://img.shields.io/badge/GitHub_Actions-2088FF?logo=githubactions&logoColor=white)

</div>

교회 홈페이지용 **공유형 멀티테넌트 백엔드**. 서버·DB·Redis 하나를 모든 교회가 함께 쓰고,
교회별 차이(이름·slug·프론트 주소)는 `churches` 테이블의 행으로 둔다. 새 교회 추가 = 서버에서 등록 스크립트 1회 실행 —
**코드 수정 0, 재배포 0**. 요청의 교회는 API 경로 접두사 `/c/{slug}`로 정한다.

실제 화면은 [www.eunsaem.com](https://www.eunsaem.com)에서 볼 수 있습니다.

## 무엇을 하나

| 영역 | 기능 |
|---|---|
| 회원·인증 | 회원가입(약관·개인정보 동의 필수)·로그인, JWT 액세스/리프레시 토큰, 로그아웃 블랙리스트 |
| 권한 | 직분(position), 역할(role)·권한 관리, 교회 대표자(SUPER_ADMIN)는 교회 등록 스크립트가 생성 |
| 콘텐츠 | 설교, 공지, 일정, 부서, 갤러리, 주보, 태그, 메인 화면 통합 조회(`/api/main`) |
| 미디어 | 이미지·PDF를 한 곳에서 관리하는 중앙 미디어 라이브러리 |
| 참여 | 성경 통독 챌린지, 차량 운행·탑승 신청(픽업 위치 좌표), 문의하기(IP 기준 레이트리밋) |

API 문서는 Swagger UI(`/docs/swagger-ui.html`, spec: `/v3/api-docs`)로 확인합니다. `SWAGGER_ENABLED`로 끌 수 있습니다.

## 아키텍처

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/images/architecture-dark.svg">
  <img alt="은샘교회 백엔드 런타임·배포 구조: 브라우저 → Caddy(HTTPS) → Spring Boot API → PostgreSQL·Redis·업로드 볼륨, GitHub Actions → GHCR → 서버 pull" src="docs/images/architecture-light.svg" width="100%">
</picture>

요청은 Caddy가 HTTPS를 종료한 뒤 Spring Boot로 넘기고, 교회 필터(`/c/{slug}`)·JWT 필터와 경로·권한 인가를 거쳐 PostgreSQL·Redis·업로드 볼륨에 닿습니다.
점선은 배포 경로입니다. 서버 한 대(Docker Compose)가 모든 교회를 서비스하며, 교회별 값은 `churches` 테이블에 있습니다.

## 설계 원칙

- **공유형 멀티테넌시**: 앱·DB·Redis 하나를 모든 교회가 공유합니다. 19개 테이블의 `church_id` 컬럼과 Hibernate `@TenantId`로 격리하고, 교회가 정해지지 않은 DB 접근은 예외로 막습니다(fail-closed). 네이티브 쿼리의 `church_id` 조건 누락과 교회 간 누출은 테스트로 잡습니다.
- **교회별 값은 코드 밖에**: 교회 이름·slug·프론트 주소(CORS)는 `churches` 행, DB·Redis 접속 정보와 JWT 시크릿 같은 플랫폼 공용 값은 `.env`에 둡니다. 본문 속 이미지는 URL 대신 `media:{id}`로 저장해 도메인이 달라도 같은 본문이 동작합니다.
- **직분과 권한을 분리한 RBAC**: 직분과 권한은 별개 축이고, 관리자가 역할을 만들어 권한을 조합합니다. 인가는 항상 `SERMON_WRITE` 같은 권한 단위로 검사하고, 역할 부여는 자기보다 엄격히 낮은 역할만 가능합니다.
- **경로 인가**: `/api/admin/**`는 인증 + 메서드 권한, 설교·갤러리·통독·차량은 회원 전용(`SERMON_VIEW`·`GALLERY_VIEW`·`CHALLENGE_PARTICIPATE`·`VEHICLE_APPLY`), 나머지는 공개입니다.
- **영속성·API 규약**: 전면 soft delete(`deleted_at`)와 partial 인덱스, 낙관적 락(`@Version`, 충돌 시 409), RFC 7807 단일 에러 형식과 `errorCode` 표, 공통 페이지 envelope.
- **캐시**: Redis에 리프레시 토큰·블랙리스트를 두고, 메인·설교 목록 첫 페이지를 캐싱하며 조회수는 모았다가 주기적으로 DB에 반영합니다.
- **의도된 비기능**: SMTP·이메일 인증 없음(관리자가 `MEMBER` 역할을 부여해 교인 확인), 고령 사용자를 고려해 복잡한 비밀번호 정책 없음.

## 기술 스택

| 구분 | 사용 기술 |
|---|---|
| 언어·프레임워크 | Java 21, Spring Boot 4.0 (Web MVC, Security, Data JPA, Validation, Actuator) |
| 데이터 | PostgreSQL 16, Flyway, Redis 7 |
| 인증 | JWT (jjwt) |
| 매핑·문서 | MapStruct, Lombok, springdoc-openapi (Swagger UI) |
| 테스트·품질 | JUnit 5, Testcontainers(PostgreSQL), ArchUnit, JaCoCo, Spotless(Palantir Java Format) |
| 인프라 | Docker Compose, GHCR 멀티아치 이미지(amd64·arm64), Caddy(HTTPS), GitHub Actions |

## 빠른 시작

JDK 21과 Docker(Compose v2)가 필요합니다. 첫 교회 값(`CHURCH_NAME`·`CHURCH_SLUG`·`CORS_ALLOWED_ORIGIN`)이 필수라 `.env.example`을 복사해 시작합니다. 나머지는 `docker-compose.yml`의 개발용 기본값으로 동작합니다.

```bash
cp .env.example .env
docker compose up -d --build
curl -s localhost:8080/actuator/health   # {"status":"UP"}
```

```bash
./gradlew build     # 컴파일 + 전체 테스트(Testcontainers — Docker 필요) + jar
./gradlew test --tests 'com.elipair.church.SomeTest.someMethod'   # 단일 테스트
```

목데이터·시드 계정·검증 절차는 [docs/setup-dev.md](docs/setup-dev.md) 참조. 운영 배포용 `.env`는 `.env.example`을 복사해 값을 채웁니다.

## 구조

```
src/main/java/com/elipair/church/
├── domain/   # auth · member · role · position · tag · media · sermon · notice · event
│             # department · gallery · bulletin · main · challenge · inquiry · vehicle
└── global/   # common · config · exception · security · storage · tenant · viewcount
src/main/resources/db/migration/   # Flyway 마이그레이션
```

도메인형 패키지이며, `domain → global` 단방향 의존 같은 아키텍처 규칙은 ArchUnit 테스트로 강제합니다.

## 배포

- **CI**: `main` 대상 PR과 `main` push마다 `./gradlew build`로 전체 테스트를 실행합니다. 통합 테스트는 Testcontainers가 실제 PostgreSQL을 띄웁니다.
- **CD**: `deploy` 브랜치에 push하면 GHCR에 멀티아치 이미지를 빌드·push하고, SSH로 서버에서 이미지를 pull해 Docker Compose로 올린 뒤 헬스 체크까지 확인합니다. 배포할 때마다 워크플로우 토큰으로 레지스트리에 로그인하고 끝나면 로그아웃해 서버에 자격 증명을 남기지 않습니다.
- **HTTPS**: Caddy 리버스 프록시가 Let's Encrypt 인증서를 자동 발급·갱신합니다.
- 버전·CHANGELOG와 아래 버전 표기는 워크플로우가 관리합니다.

## 문서 지도

| 목적 | 문서 |
|---|---|
| 로컬 개발 시작 (clone → 실행 → 테스트) | [docs/setup-dev.md](docs/setup-dev.md) |
| 새 교회 등록 (스크립트 1회, 재배포 없음) | [docs/setup-new-church.md](docs/setup-new-church.md) |
| 서버 프로비저닝 (OCI VM, 플랫폼 전체에 1회) | [docs/deploy-server-setup.md](docs/deploy-server-setup.md) |
| 운영 DB 점검 | [docs/db-remote-access.md](docs/db-remote-access.md) |
| 전체 설계 스펙 (정본, 한국어) | [docs/church-backend-spec.md](docs/church-backend-spec.md) |
| 프론트엔드 연동 가이드 | [docs/church-frontend-guide.md](docs/church-frontend-guide.md) |
| 기능별 설계·구현 계획 | [docs/superpowers/specs/](docs/superpowers/specs/) · [plans/](docs/superpowers/plans/) |
| 핵심 규칙 (RBAC·API·영속성·미디어 등) | [.claude/rules/](.claude/rules/) |
| AI 에이전트 진입점 | [AGENTS.md](AGENTS.md) (Claude Code는 `CLAUDE.md`) |

## 만든 사람

홍의민 · [EM-H20](https://github.com/EM-H20). 백엔드와 프론트엔드를 혼자 설계·구현했습니다.

## License

[MIT](LICENSE)

---

<!-- AUTO-VERSION-SECTION: DO NOT EDIT MANUALLY -->
## 최신 버전 : v0.0.52 (2026-11-10)

[전체 버전 기록 보기](CHANGELOG.md)
