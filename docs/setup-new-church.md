# 새 교회 등록 (setup-new-church)

**원칙: 코드 수정 0, 재배포 0, DNS 작업 0.** 서버·DB·Redis 하나를 모든 교회가 공유한다(멀티테넌시, #86).
새 교회 = 운영 서버에서 등록 스크립트 1회 실행 → 그 교회 프론트에 `NEXT_PUBLIC_API_BASE` 설정.
코드를 고쳐야 한다면 규칙 위반이다(`.claude/rules/multi-church-template.md`).

서버 자체를 처음 세팅하는 절차(플랫폼 전체에 1회)는 `docs/deploy-server-setup.md`가 정본이다.
이 문서는 이미 돌고 있는 서버에 교회를 하나 더 붙이는 절차다.

## 1. 스크립트가 만드는 것

`scripts/new-church.sh`(+ `scripts/new-church.sql`)가 한 번에(원자적으로 — 실패하면 아무것도 남지 않는다):

- `churches` 1행 (이름, slug, 프론트 주소)
- 기본 역할 4종 — `SUPER_ADMIN`(1000) / `ADMIN`(900) / `MEMBER`(100) / `USER`(0)와 역할별 권한
- **교회 대표자** 회원 1명 = 그 교회의 `SUPER_ADMIN` (`SUPER_ADMIN`은 API로 부여할 수 없어 이 스크립트가 유일한 생성 경로다)

교회 등록 API·관리 화면은 없다(의도 — 인터넷에 "교회와 최고 관리자를 만드는" 통로를 열지 않는다).
운영자는 앱에 상시 계정을 두지 않는다.

## 2. 실행 방법 (운영자, 교회 하나당 한 번)

실행 위치는 **운영 서버**다. PostgreSQL 포트가 서버의 `127.0.0.1`에만 열려 있고, 스크립트가 서버 안의 DB 컨테이너에 직접 명령을 넣기 때문이다.

1. 터미널(Termius 등)로 서버에 SSH 접속한다.
2. 스크립트를 실행한다. `API_BASE`를 주면 끝에 바로 복사해 쓸 값이 출력된다.
   ```bash
   cd /srv/church-backend && API_BASE=https://api.eunsaem.com ./scripts/new-church.sh
   ```
   (스크립트 두 파일은 배포 워크플로가 compose 파일과 함께 `/srv/church-backend/scripts/`로 복사한다.)
3. 차례로 묻는 값을 입력한다: 교회 이름 → 교회 키(slug, 소문자·숫자·하이픈 1~40자) → 프론트 주소(`https://…`, 경로·끝 슬래시 없이) → 교회 대표자 전화번호 → 이름 → 임시 비밀번호(8자 이상, 화면에 보이지 않음, 두 번 입력해 확인).
4. 스크립트가 입력값을 다시 보여 주고(비밀번호 제외) 확인(`y`)을 받은 뒤 실행한다. 실패하면 아무것도 남지 않는다.
5. 끝나면 스크립트가 그 교회 프론트에 넣을 값을 출력한다: `NEXT_PUBLIC_API_BASE=https://api.eunsaem.com/c/<slug>`. 이 값으로 프론트를 빌드·배포한다.
6. 확인: 200과 빈 목록이 나오면 정상이다.
   ```bash
   curl -s https://api.eunsaem.com/c/<slug>/api/main
   ```
7. 교회 대표자에게 전화번호와 임시 비밀번호를 전달한다. 대표자는 로그인해 비밀번호를 바꾸고, 다른 관리자(`ADMIN`)는 직접 추가한다.

API 도메인(`api.eunsaem.com`)은 전 교회 공용이라 DNS·Caddy 작업과 앱 재시작이 없다. 새 교회는 등록 후 길어도 30초 안에 요청을 받는다(없는 slug 조회를 30초간 기억하기 때문).

**입력값 메모**

- **slug**는 API 경로 접두사(`/c/<slug>`)가 되고 프론트 빌드에 박힌다. 나중에 바꾸지 않는다.
- **프론트 주소**는 그 교회의 CORS 허용 origin(`churches.frontend_origin`)이 된다. 브라우저가 보내는 Origin과 글자 그대로 같아야 한다(`https://www.example.com`과 `https://example.com`은 다르다). 나중에 바꾸려면 DB의 `churches.frontend_origin`을 고친 뒤 backend를 재시작한다(교회 정보는 메모리에 캐시된다).
- 스크립트 환경변수(선택): `POSTGRES_CONTAINER`(기본 `church-backend-postgres-1`), `ENV_FILE`(기본 `/srv/church-backend/.env` — `DB_USERNAME`·`DB_NAME`을 읽는다), `API_BASE`(안내 출력용).

## 3. 대표자가 아직 정해지지 않았을 때

운영자 본인 번호로 먼저 만든다. 대표자가 정해지면:

1. 대표자가 그 교회 프론트에서 일반 가입을 한다.
2. 운영자가 DB에서 그 회원에게 `SUPER_ADMIN`을 연결한다(접속 방법: `docs/db-remote-access.md`).
   ```sql
   INSERT INTO member_roles (member_id, role_id)
   SELECT m.id, r.id
   FROM members m
   JOIN churches c ON c.id = m.church_id
   JOIN roles r ON r.church_id = m.church_id AND r.name = 'SUPER_ADMIN'
   WHERE c.slug = '<slug>' AND m.phone = '<대표자 전화번호, 숫자만>' AND m.deleted_at IS NULL;
   -- INSERT 0 1 이 나와야 한다. 0이면 slug·전화번호를 다시 확인한다.
   ```
   대표자는 다시 로그인(또는 토큰 갱신)하면 권한이 반영된다.
3. 운영자는 자기 계정으로 로그인해 회원 탈퇴한다(`DELETE /api/members/me`). `SUPER_ADMIN`이 2명이 된 뒤라 "마지막 SUPER_ADMIN 보호"에 걸리지 않는다.

## 4. 로컬 PC에서 실행하고 싶을 때

DB 도구를 붙일 때 쓰는 SSH 터널(`docs/db-remote-access.md`)을 연 상태에서 `psql`로 `new-church.sql`을 직접 실행할 수 있다. 단계가 하나 더 생기고 값 검증·확인 절차가 없으므로 **기본 절차는 서버 실행**이다.

```bash
ssh -L 5432:127.0.0.1:5432 <user>@<서버>          # 터미널 1: 터널

# 터미널 2 — 임시 비밀번호가 셸 히스토리에 남지 않게 read로 받는다
read -r -s -p "임시 비밀번호: " NC_PW; echo
psql -h 127.0.0.1 -U <DB_USERNAME> -d <DB_NAME> -v ON_ERROR_STOP=1 \
  -v name='<교회 이름>' -v slug='<slug>' -v origin='https://<프론트 도메인>' \
  -v phone='<대표자 전화번호>' -v owner_name='<대표자 이름>' -v pw="$NC_PW" \
  -f scripts/new-church.sql
```

끝나면 §2의 5~7단계(프론트 환경변수, `curl` 확인, 대표자에게 전달)를 똑같이 한다.

## 5. 등록 후 확인 체크리스트

- [ ] `curl -s https://api.eunsaem.com/c/<slug>/api/main` → 200
- [ ] 대표자 로그인 → 비밀번호 변경
  ```bash
  curl -s -X POST https://api.eunsaem.com/c/<slug>/api/auth/login \
    -H 'Content-Type: application/json' \
    -d '{"phone":"<대표자 전화번호>","password":"<임시 비밀번호>"}'
  ```
- [ ] 그 교회 프론트에서 CORS 통과(로그인·목록이 브라우저에서 동작)
- [ ] 다른 교회 데이터가 보이지 않음(목록이 비어 있음)

## 6. 알아둘 것

- 한 교회에서 받은 토큰은 다른 교회 접두사에서 `401 INVALID_TOKEN`이다(access 토큰의 `cid`). 같은 전화번호로 교회마다 따로 가입할 수 있고, 서로 다른 계정이다.
- 교회의 유일한 `SUPER_ADMIN`이 비밀번호를 잊으면 화면으로는 풀 수 없다 — 운영자가 DB에서 초기화한다.
- `MEMBER`에 권한을 추가하는 마이그레이션을 쓰면 `scripts/new-church.sql`의 MEMBER 권한 목록도 함께 고친다(`NewChurchScriptTest`가 어긋남을 잡는다).

운영 DB 원격 점검: `docs/db-remote-access.md`.
