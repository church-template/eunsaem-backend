# 로컬 개발 시작 (setup-dev)

clone 직후 로컬에서 앱을 띄우고 테스트까지 도달하는 정본 절차. 대상: 사람·AI 에이전트 공통.

## 0. 전제

- JDK 21, Docker(Compose v2). 그 외 전부 컨테이너/Gradle wrapper가 해결.
- 컨테이너 경로(§2)는 `.env`에 **첫 교회 값 3개가 필수**다 — `CHURCH_NAME`·`CHURCH_SLUG`·`CORS_ALLOWED_ORIGIN`
  (없으면 compose가 `CHURCH_NAME is required`로 실행을 거부한다). 나머지는 `docker-compose.yml`의 dev 전용 기본값
  (`church_dev_pw`·`church_dev_redis` 등)으로 동작한다. 가장 쉬운 방법은 `.env.example`을 복사하는 것:

  ```bash
  cp .env.example .env
  # CORS_ALLOWED_ORIGIN을 로컬 프론트 주소로 바꾼다: CORS_ALLOWED_ORIGIN=http://localhost:3000
  ```

  | 키 | 로컬 값 | 뜻 |
  |---|---|---|
  | `CHURCH_NAME` | `개발교회` | 첫 교회 이름. V18이 `churches` 첫 행을 만들 때 한 번 읽는다 |
  | `CHURCH_SLUG` | `dev` | 첫 교회 slug = API 경로 접두사 `/church/dev` |
  | `CORS_ALLOWED_ORIGIN` | `http://localhost:3000` | 첫 교회의 프론트 origin. **DB를 처음 만들 때 한 번만** 기록된다 — 나중에 바꾸려면 `churches.frontend_origin`을 고치거나 볼륨을 지우고 다시 띄운다 |
  | `TENANT_DEFAULT_SLUG` | `dev` | 접두사 없는 `/api/**` 요청을 처리할 교회(아래 "교회 접두사") |

- host bootRun 경로(§3)는 `.env`가 필요 없다 — `dev` 프로필이 위 네 값의 기본값(`개발교회`/`dev`/`http://localhost:3000`/`dev`)을 갖고 있다.
- **교회 접두사**: 운영 API 경로는 `/church/<slug>/api/...`다(멀티테넌시, #86). 로컬에서는 `tenant.default-slug=dev` 덕에
  접두사 없는 `localhost:8080/api/...`가 `dev` 교회로 처리되고, `localhost:8080/church/dev/api/...`도 똑같이 동작한다.
  컨테이너 경로에서 `.env`의 `TENANT_DEFAULT_SLUG`를 비우면 접두사 없는 요청은 404다.

## 1. dev 오버라이드 생성 (1회)

`docker-compose.override.yml`은 로컬 전용이라 git에 없다(.gitignore). 아래로 생성:

```yaml
# docker-compose.override.yml — 로컬 전용(.gitignore). compose가 base와 자동 병합.
services:
  postgres:
    ports:
      - "127.0.0.1:5432:5432"   # 호스트 도구(DBeaver·IntelliJ)·host bootRun용
  redis:
    ports:
      - "127.0.0.1:6379:6379"   # host bootRun 시 필수(미노출이면 인증 조회 500 — §6 함정)
  backend:
    environment:
      SPRING_PROFILES_ACTIVE: dev                    # db/dev 목데이터 시드 로드
      LOGGING_LEVEL_ORG_HIBERNATE_SQL: DEBUG         # 실행 SQL 로그(선택)
```

- `dev` 프로필이 하는 일: Flyway locations에 `classpath:db/dev` 추가 →
  `db/dev/afterMigrate__seed.sql`이 매 부팅 멱등 실행되어 목데이터 시드(§5).
  운영은 이 프로필을 안 켜므로 시드가 절대 섞이지 않는다.

## 2. 전체 기동 (컨테이너 경로 — 기본)

```bash
docker compose up -d --build
```

postgres(16)·redis(7)·backend가 뜨고, backend는 `8080:8080` 노출. 헬스 대기 후 §4 검증으로.

## 3. host bootRun 경로 (빠른 반복 개발용, 선택)

인프라만 컨테이너로 띄우고 앱은 Gradle로 직접 실행:

```bash
docker compose up -d postgres redis
SPRING_PROFILES_ACTIVE=dev \
DB_URL=jdbc:postgresql://localhost:5432/church_db \
DB_PASSWORD=church_dev_pw \
REDIS_HOST=localhost \
REDIS_PASSWORD=church_dev_redis \
JWT_SECRET=dev-only-secret-change-in-production-0123456789 \
./gradlew bootRun
```

- compose는 `.env`를 자동 로드하지만 **Gradle은 아니다** — 위처럼 인라인 env 필수.
- DB 볼륨을 과거에 다른 비번으로 초기화했다면 그 값을 사용(비번은 볼륨 생성 시점에 고정됨).
- §1 오버라이드로 5432·6379가 열려 있어야 한다.

## 4. 검증

```bash
curl -s localhost:8080/actuator/health
# → {"status":"UP"}

# Swagger UI(브라우저): http://localhost:8080/docs/swagger-ui.html  (spec: /v3/api-docs)

# 시드 관리자 로그인
curl -s -X POST localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"phone":"01090000001","password":"church1234!"}'
# → {"tokens":{"accessToken":"...","refreshToken":"..."},"member":{...},"requiresAgreement":false}

# 교회 접두사 경로(운영과 같은 형태)도 같은 결과
curl -s localhost:8080/church/dev/api/main
```

테스트·빌드:

```bash
./gradlew build   # 컴파일+전체 테스트(Testcontainers 포함— Docker 필요)+jar
./gradlew test --tests 'com.elipair.church.SomeTest.someMethod'   # 단일
```

## 5. 목데이터 (dev 프로필 전용)

- 모든 시드 행은 **id 9000번대 예약 블록** — 앱 실데이터(10000+)와 절대 충돌 없음. 정리는 **id 9000~9999만** 삭제(`id BETWEEN 9000 AND 9999` — `>= 9000`은 실데이터까지 지운다).
- 공용 비밀번호 `church1234!`. 대표 계정:

| phone | 이름 | 역할 | 용도 |
|---|---|---|---|
| 01090000019 | 대표자 | SUPER_ADMIN(+USER) — id 9019 | 교회 대표자. 역할 관리·ADMIN 부여·위계 검증 테스트 |
| 01090000001 | 김은혜 | ADMIN(+USER), 목사 | 관리자 API 테스트 |
| 01090000003 | 박소망 | MEMBER(+USER), 장로 | 회원전용(갤러리·설교·챌린지) 테스트 |
| 01090000007 | 윤방문 | USER만 | 미승인 → 회원전용 403 테스트 |
| 01090000008 | 한탈퇴 | (소프트삭제) | 탈퇴 상태·"(탈퇴한 사용자)" 표시 테스트 |

- SUPER_ADMIN은 시드 계정 9019뿐이다. 기동 시 환경변수로 관리자를 만들던 부트스트랩은 없어졌다(운영은 `scripts/new-church.sh`가 교회 대표자를 만든다).
- 시드는 전부 첫 교회(`dev`) 소속이다.
- 전체 계정·태그·부서·콘텐츠 목록과 id 규약 상세: `src/main/resources/db/dev/afterMigrate__seed.sql` 헤더 주석이 정본.

## 6. 함정 (실전 이슈 누적)

- **host bootRun에서 redis 미연결이면 인증 필요 조회가 500** — JWT 필터가 블랙리스트를 Redis에서
  확인한다. redis 컨테이너 기동 + §1의 6379 노출 + `REDIS_PASSWORD` 지정까지 세 가지 다 필요.
- **접두사 없는 `/api/**`가 전부 404** — `TENANT_DEFAULT_SLUG`가 비어 있다. `.env`에 `TENANT_DEFAULT_SLUG=dev`를 넣거나
  `/church/dev/api/...`로 호출한다. 반대로 `churches`에 없는 slug를 넣으면 앱이 기동하지 않는다.
- **교회 없이 DB를 만지면 예외**("테넌트가 지정되지 않았습니다") — 스케줄러 등 요청 밖 코드는
  `TenantContext.runAs(churchId, …)`를 트랜잭션 바깥에서 감싸야 한다(`.claude/rules/multi-church-template.md`).
- **회원전용 경로**: 갤러리·설교·통독 챌린지는 `MEMBER` 승인 계정으로만 200. `USER`(01090000007)는 403이 정상.
- **`version.yml`·`build.gradle` version·`CHANGELOG.*` 수동 편집 금지** — CI 자동화 소유(`.claude/rules/versioning-ci.md`).
- **SB4 스타터 명명**: web은 `spring-boot-starter-webmvc`, 테스트는 모듈별 `*-test`(`.claude/rules/spring-boot-4.md`).
- 운영 DB에 원격 접속하려면: `docs/db-remote-access.md`.
