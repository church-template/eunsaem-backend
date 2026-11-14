# 멀티테넌시 전환 운영 절차서 (#86)

교회마다 서버를 따로 띄우던 구조에서, 서버·DB·Redis 하나를 모든 교회가 쓰는 구조로 운영 서버를 옮기는 절차다.
설계 배경은 `docs/church-backend-spec.md` §2, 새 교회 추가는 `docs/setup-new-church.md`를 본다.

**기준**: 서비스가 잠깐 멈추는 것은 괜찮다. 회원·업로드 파일이 사라지는 것은 안 된다.

**이 문서의 명령 중 실제로 실행해 확인한 것**(2026-11-14): 수동 백업(§2-2, 운영 서버), `scripts/new-church.sh`와
`scripts/backup.sh`(임시 컨테이너에서 암호화·복호화·실패 경로까지), 확인 쿼리(§3-2), 기본값 복구(§7-4), 운영자용 SQL(§9)
— 뒤의 셋은 V1~V19를 적용한 임시 DB에서 실행했다. 배포·되돌리기·프론트 전환(§2-4, §4, §6, §7-3)과 사전 연습(§1-2, §1-3)의
명령은 실행해 보지 않았다 — §1의 사전 연습에서 한 번씩 실행해 확인하고 당일에 쓴다.

명령은 서버의 기본 셸(bash) 기준이다. `PSQL="…"; $PSQL -c …`처럼 변수에 담은 명령은 zsh에서는 동작하지 않는다.

여러 절의 명령이 `$DBU`·`$DBN`(DB 사용자·이름)을 쓴다. SSH에 새로 접속할 때마다 먼저 이 세 줄을 실행한다:

```bash
cd /srv/church-backend
DBU=$(grep '^DB_USERNAME=' .env | cut -d= -f2-); DBU=${DBU:-church_user}
DBN=$(grep '^DB_NAME=' .env | cut -d= -f2-); DBN=${DBN:-church_db}
```

서버에서 `docker compose … up -d`를 **직접 실행하지 않는다.** 운영 compose는 이미지 이름·버전을 배포 워크플로가 넘겨 주는 값으로
정하므로, 손으로 실행하면 엉뚱한 이미지를 찾다가 실패한다. 컨테이너를 다시 만들어야 할 때는 Actions의 Deploy를
`image_tag`에 현재 버전을 넣어 실행한다. 설정 변경 없이 재시작만 할 때는 `docker restart church-backend-backend-1`.

각 단계는 **명령 → 기대 결과 → 다르면 할 일** 순서로 적었다. 기대 결과와 다르면 다음 단계로 가지 않는다.

---

## 0. 한눈에 보기

| 단계 | 하는 일 | 서비스 중단 | 되돌리기 |
|---|---|---|---|
| 사전 연습 (§1) | 운영 덤프를 로컬에 복원해 새 버전·옛 버전을 띄워 본다 | 없음 | — |
| 1차 배포 (§2) | backend 정지 → 백업 → `.env` → 배포 PR | 10분 안팎 | 이전 이미지 재배포 (§4) |
| 자동 백업 등록 (§5) | cron에 `backup.sh` | 없음 | — |
| 프론트 전환 (§6) | 은샘 프론트의 API 주소에 `/church/<slug>` 추가 | 없음 | 프론트 환경변수 원복 |
| 2차 배포 (§7) | `TENANT_DEFAULT_SLUG` 제거 + V20 | 수십 초 | 1차 이미지 재배포 (§7-4) |
| 두 번째 교회 (§8) | `new-church.sh` | 없음 | — |

**배포는 자동이고 멈출 지점이 없다.** `main → deploy` PR을 열면 워크플로가 스스로 머지하고, 7~10분 뒤 서버에
새 버전이 올라가면서 마이그레이션이 돈다. 그래서 백업과 `.env` 준비는 반드시 **PR을 열기 전에** 끝낸다.

---

## 1. 사전 연습 (전환 며칠 전, 로컬 PC)

운영 데이터 사본으로 새 버전이 뜨는지, 그리고 **옛 버전으로 되돌아가는지**를 미리 본다.

### 1-1. 운영 덤프 받기

서버에서(서비스를 멈추지 않는다):

```bash
cd /srv/church-backend
DBU=$(grep '^DB_USERNAME=' .env | cut -d= -f2-); DBU=${DBU:-church_user}
DBN=$(grep '^DB_NAME=' .env | cut -d= -f2-); DBN=${DBN:-church_db}
TS=$(date +%Y%m%d-%H%M); mkdir -p ~/backup-cutover && chmod 700 ~/backup-cutover
docker compose exec -T postgres pg_dump -U "$DBU" -d "$DBN" -Fc > ~/backup-cutover/db-$TS.dump
docker cp church-backend-backend-1:/app/uploads - > ~/backup-cutover/uploads-$TS.tar
docker compose exec -T postgres pg_restore --list < ~/backup-cutover/db-$TS.dump | grep -c 'TABLE DATA'
tar tf ~/backup-cutover/uploads-$TS.tar | grep -vc '/$'
```

- 기대: 첫 숫자 = 테이블 수 + 1(`flyway_schema_history`), 둘째 숫자 = `media` 행 수. (2026-11-14: 23, 113)
- SFTP로 두 파일을 PC에 내려받는다. 덤프에는 회원 전화번호와 비밀번호 해시가 있다 — 공유 폴더에 두지 않는다.
- 수동 백업은 `~/backup-cutover/`에 둔다. `~/backup/`은 자동 백업(`backup.sh`)이 쓰는 곳이고 7일이 지난 파일을 지운다.

### 1-2. 로컬에 복원하고 새 버전 띄우기

덤프 파일은 **레포 밖**(예: `~/cutover/`)에 둔다 — 레포 안에 두면 git 명령에 휩쓸려 회원 정보가 git 저장소에 남을 수 있다.

레포의 이슈 브랜치(또는 머지 후 `main`)에서, **개발용 override 없이** 띄운다(override는 dev 프로필이라 목데이터가 섞인다).
로컬 `.env`에 `CHURCH_NAME=은샘교회`, `CHURCH_SLUG=<정할 slug>`, `CORS_ALLOWED_ORIGIN=https://www.eunsaem.com`,
`TENANT_DEFAULT_SLUG=<같은 slug>`를 넣는다.

```bash
docker compose -f docker-compose.yml down -v          # 로컬 볼륨 초기화 (로컬 PC에서만!)
docker compose -f docker-compose.yml up -d postgres redis
docker compose -f docker-compose.yml exec -T postgres pg_restore -U church_user -d church_db --no-owner < ~/cutover/db-<TS>.dump
docker compose -f docker-compose.yml up -d --build backend
curl -fs http://localhost:8080/actuator/health
curl -s http://localhost:8080/api/main | head -c 300
curl -s http://localhost:8080/church/<slug>/api/main | head -c 300
```

- 기대: 헬스 `UP`, 두 `/api/main` 응답이 같고 실제 공지·일정이 보인다.
- 확인 쿼리(§3-2)를 돌려 `churches` 1행, 모든 테이블의 `church_id`가 1인지 본다.
- 실제 계정으로 로그인해 본다(프론트를 로컬에 붙이거나 `curl`로 `/api/auth/login`).
- 다르면: 로그(`docker compose -f docker-compose.yml logs backend | tail -100`)를 보고 원인을 고친 뒤 처음부터 다시 한다. 전환 일정을 잡지 않는다.

### 1-3. 옛 버전으로 되돌아가는지 확인

V18·V19가 적용된 이 DB에서 **전환 이전 이미지**가 뜨는지 본다(§4 되돌리기의 전제).

```bash
git status --short                          # 기대: 변경 없음(있으면 먼저 커밋한다 — stash를 쓰지 않는다)
git switch --detach <전환 이전 커밋 또는 태그>
docker compose -f docker-compose.yml up -d --build backend
curl -fs http://localhost:8080/actuator/health && curl -s http://localhost:8080/api/main | head -c 300
```

- 기대: 헬스 `UP`, 메인 응답 정상. 로그에 "미래 마이그레이션" 경고가 나오는 것은 정상이다.
- 옛 버전에서 글을 하나 쓰거나 회원가입을 해 본 뒤, `select church_id from members order by id desc limit 1`이 1인지 본다(`DEFAULT` 덕분).
- 다르면: 되돌리기가 이미지 교체로는 안 된다는 뜻이다. 전환하지 말고 원인을 찾는다.
- 끝나면 `git switch -`로 돌아온다.

### 1-4. 그 밖의 사전 확인 (서버)

```bash
docker compose version          # 기대: v2.24 이상 (prod 오버레이의 ports: !override)
cat /srv/caddy/Caddyfile        # 기대: reverse_proxy localhost:8080 (경로 제한 없음 — /church/… 가 막히지 않는다).
                                # trusted_proxies 설정이 없어야 한다 — 있으면 클라이언트가 X-Forwarded-For로 IP를 속여
                                # 로그인·문의 제한을 피할 수 있다
grep -E '^(CORS_ALLOWED_ORIGIN)=' /srv/church-backend/.env   # 기대: https://www.eunsaem.com
```

- 은샘 사이트에서 **본문에 이미지가 들어간 글**을 열어 이미지가 지금 보이는지 기록해 둔다(§6의 비교 기준).

---

## 2. 1차 배포 (전환 당일)

한가한 시간에 한다(주일·수요일 저녁 제외). 중단은 backend를 멈춘 때부터 새 버전이 뜰 때까지 **10분 안팎**이다.

### 2-1. DB 도구 세션 확인

```bash
cd /srv/church-backend
DBU=$(grep '^DB_USERNAME=' .env | cut -d= -f2-); DBU=${DBU:-church_user}
DBN=$(grep '^DB_NAME=' .env | cut -d= -f2-); DBN=${DBN:-church_db}
docker compose exec -T postgres psql -U "$DBU" -d "$DBN" -At -c \
  "select application_name, client_addr, state from pg_stat_activity where datname = current_database() and pid <> pg_backend_pid()"
```

- 기대: `PostgreSQL JDBC Driver`(백엔드)만 보인다.

지금 떠 있는 버전을 적어 둔다 — §4에서 되돌릴 때 이 태그가 필요하다(배포 후에는 서버에서 옛 이미지가 지워진다).

```bash
docker compose -f docker-compose.yml -f docker-compose.prod.yml images backend     # TAG 열의 값(예: 0.0.52)을 기록
```

- 다르면: DBeaver 등 DB 도구를 닫는다. 락을 쥔 세션이 있으면 V19의 `ALTER TABLE`이 기다리다 배포가 실패한다.

### 2-2. backend 정지 → 백업 → 확인

```bash
docker compose -f docker-compose.yml -f docker-compose.prod.yml stop backend
TS=$(date +%Y%m%d-%H%M); mkdir -p ~/backup-cutover && chmod 700 ~/backup-cutover
docker compose exec -T postgres pg_dump -U "$DBU" -d "$DBN" -Fc > ~/backup-cutover/db-$TS.dump
docker cp church-backend-backend-1:/app/uploads - > ~/backup-cutover/uploads-$TS.tar
docker compose exec -T postgres pg_restore --list < ~/backup-cutover/db-$TS.dump | grep -c 'TABLE DATA'
tar tf ~/backup-cutover/uploads-$TS.tar | grep -vc '/$'
docker compose exec -T postgres psql -U "$DBU" -d "$DBN" -At -c "select count(*) from media" -c "select count(*) from members"
```

- 기대: 첫 숫자 = 23(테이블 수 + 1), 둘째 숫자 = 방금 조회한 `media` 행 수와 같다.
- **두 파일을 SFTP로 PC에 내려받는다.** 서버 안에만 있으면 서버와 함께 사라진다.
- 다르면: `docker compose -f docker-compose.yml -f docker-compose.prod.yml start backend`로 서비스를 다시 올리고 중단한다.

### 2-3. `.env`에 값 추가 (반드시 백업 확인 뒤)

`/srv/church-backend/.env`에 추가한다. 이 값이 없으면 배포가 `pull` 단계에서 멈추고 기존 컨테이너가 그대로 남는다 —
백업 없이 배포가 나가는 실수를 막는 안전장치이므로, 백업보다 먼저 넣지 않는다.

```
CHURCH_NAME=은샘교회
CHURCH_SLUG=<slug>              # 소문자·숫자·하이픈. API 주소 /church/<slug>에 쓰인다. 나중에 바꾸기 번거롭다
TENANT_DEFAULT_SLUG=<같은 slug>   # 전환 기간 전용 — 접두사 없는 요청을 은샘으로 처리한다
```

```bash
grep -E '^(CHURCH_NAME|CHURCH_SLUG|TENANT_DEFAULT_SLUG|CORS_ALLOWED_ORIGIN)=' /srv/church-backend/.env
```

- 기대: 네 줄. `CORS_ALLOWED_ORIGIN`은 `https://www.eunsaem.com` — **이 값이 첫 교회의 프론트 주소로 DB에 기록된다.**
- `CHURCH_SLUG`와 `TENANT_DEFAULT_SLUG`가 다르면 새 버전이 기동에 실패한다(의도된 동작).
- `CHURCH_NAME`에 작은따옴표(`'`)를 넣지 않는다. 마이그레이션 SQL이 깨져 기동에 실패한다(데이터는 그대로다 — 값을 고치고 다시 배포하면 된다).
- 값을 넣지 않은 채 배포 PR을 열어 배포가 `pull`에서 멈췄다면: backend는 §2-2에서 멈춘 상태이고, 서버의 compose 파일은 이미
  새것이라 `start backend`도 같은 오류로 실패한다. `.env`에 값을 넣은 뒤 Actions의 Deploy를 `image_tag`에 **새 버전**을 넣어 다시 실행한다.
- 지워도 되는 옛 값: `CHURCH_KEY`, `ADMIN_PHONE`, `ADMIN_NAME`, `ADMIN_PASSWORD`. 단 되돌릴 가능성이 있는 동안은 그대로 둔다.

### 2-4. 배포 PR 열기

`main → deploy` PR을 연다(`/pro-changelog-deploy`). 워크플로가 머지 → 이미지 빌드 → 서버 배포 → 헬스 확인(180초)을 한다.

- 기대: Actions의 Deploy가 초록.
- 빨강이면: 서버에서 `docker compose -f docker-compose.yml -f docker-compose.prod.yml logs backend | tail -100`을 보고 §4로 되돌린다.

---

## 3. 배포 직후 확인

### 3-1. 서비스

```bash
curl -fs http://localhost:8080/actuator/health
curl -s -o /dev/null -w '%{http_code}\n' https://api.eunsaem.com/api/main
curl -s -o /dev/null -w '%{http_code}\n' https://api.eunsaem.com/church/<slug>/api/main
sudo ss -tlnp | grep ':8080'
```

- 기대: `UP`, `200`, `200`, 그리고 8080이 `127.0.0.1:8080`에만 떠 있다.
- 은샘 사이트에서: 로그인 유지(잠깐 뒤 자동 갱신), 메인, 설교 상세, 갤러리 이미지, 관리자에서 글 수정·이미지 업로드.

### 3-2. 데이터

```bash
PSQL="docker compose exec -T postgres psql -U $DBU -d $DBN -At"
$PSQL -c "select id, name, slug, frontend_origin from churches"
$PSQL -c "select version, success from flyway_schema_history order by installed_rank desc limit 2"
for t in positions roles members tags content_tags media sermons notices events departments gallery_albums gallery_photos \
         bulletins bible_challenges challenge_participations challenge_reading_logs inquiries vehicle_runs vehicle_requests; do
  echo "$t $($PSQL -c "select count(*), count(*) filter (where church_id = 1) from $t")"
done
$PSQL -c "select count(*) from members" -c "select count(*) from media"
```

- 기대: `churches` 1행(은샘 이름·slug·`https://www.eunsaem.com`), 마이그레이션 19·18이 `t`, 테이블마다 두 숫자가 같다(전부 교회 1),
  회원·미디어 수가 §2-2에서 본 값과 같다.
- 다르면: §4로 되돌린다.

### 3-3. 옛 조회수 키 정리

```bash
RP=$(grep '^REDIS_PASSWORD=' .env | cut -d= -f2-)
docker compose exec -T -e REDISCLI_AUTH="$RP" redis redis-cli --scan --pattern 'view:sermon:*'
docker compose exec -T -e REDISCLI_AUTH="$RP" redis redis-cli --scan --pattern 'view:notice:*'
```

- 목록을 **먼저 보고**, 전환 전 형식(`view:sermon:12`처럼 숫자가 하나)만 지운다. 새 형식은 `view:1:sermon:12`다.
  지울 키가 있으면: `docker compose exec -T -e REDISCLI_AUTH="$RP" redis redis-cli del view:sermon:12 …`
- 그대로 둬도 동작에는 문제가 없다(새 버전이 건너뛴다). 잃는 것은 배포 직전 최대 1분치 조회수다.

---

## 4. 되돌리기 (2차 배포 전)

`church_id`의 `DEFAULT`가 남아 있는 동안은 **이전 이미지를 다시 배포하는 것만으로** 되돌린다. 데이터는 잃지 않는다 —
옛 코드는 새 컬럼을 무시하고, 옛 코드가 넣는 행은 기본값으로 은샘에 들어간다.

1. (프론트를 이미 `/church/<slug>`로 바꿨다면) **프론트 환경변수를 먼저 원복·재배포한다.** 옛 백엔드는 `/church/…`를 모른다.
2. GitHub → Actions → Deploy → Run workflow → `image_tag`에 §2-1에서 적어 둔 이전 버전(예: `0.0.52`)을 넣고 실행한다.
3. `curl -fs http://localhost:8080/actuator/health`, 사이트 확인.

- 서버의 compose 파일은 새 버전 그대로여도 된다(옛 이미지는 추가된 환경변수를 무시한다).
- `.env`의 `CHURCH_*` 값은 지우지 않는다(compose가 필수로 요구한다).
- 이 방법이 안 될 때만 덤프를 복원한다. 덤프 복원은 **덤프 이후의 데이터가 사라지는 유일한 경로**다.

---

## 5. 자동 백업 등록 (1차 배포 직후)

1차 배포가 `scripts/backup.sh`를 서버에 복사한다. 그 전까지는 §2-2의 수동 백업이 전부다.

먼저 값 파일을 만든다. 비밀번호가 셸 히스토리에 남지 않게 **편집기로** 쓴다.

```bash
( umask 077; touch ~/.backup.env ); nano ~/.backup.env
```

파일 내용(두 번째 줄은 서버 밖 보관처를 정한 뒤에 넣는다 — 정하기 전에는 **줄을 넣지 않는다**):

```
export BACKUP_PASSPHRASE='<길고 무작위인 값>'
export BACKUP_UPLOAD_CMD='<파일 경로 하나를 받아 서버 밖으로 올리는 명령>'
```

한 번 실행해 본다:

```bash
cd /srv/church-backend && ( . ~/.backup.env && ./scripts/backup.sh ); echo "exit=$?"
```

- 기대: "덤프: 테이블 데이터 **24**개 / 업로드: 파일 N개"(1차 배포 뒤에는 `churches`가 늘어 24), `~/backup/`에 `.gpg` 두 개.
  서버 밖 전송을 아직 안 걸었으면 `exit=2`(경고), 걸었는데 전송이 실패하면 `exit=1`(암호화된 로컬 사본은 남는다).
- `BACKUP_PASSPHRASE`는 비밀번호 관리자에 둔다. 잃으면 백업을 열 수 없다.

**서버 밖 보관**: 어디로 보낼지 정한다(예: OCI Object Storage 비공개 버킷). `BACKUP_UPLOAD_CMD`에 "파일 경로 하나를 받아
올리는 명령"을 넣는다. 버킷은 비공개로 두고 보관 기간(수명 주기 규칙)을 정한다.

**cron 등록** — 값을 crontab에 직접 쓰지 않고 위 파일에서 불러온다. cron은 줄 안의 `%`를 줄바꿈으로 바꿔서, 비밀번호에 `%`가
있으면 명령이 중간에 잘리고 백업이 **아무 기록 없이** 돌지 않는다.

`crontab -e`에 한 줄(매일 새벽 4시):

```
0 4 * * * . $HOME/.backup.env && /srv/church-backend/scripts/backup.sh >> $HOME/backup/backup.log 2>&1; echo "$(date) exit=$?" >> $HOME/backup/backup.log
```

- 등록한 다음 날 `tail ~/backup/backup.log`에 `exit=0`이 찍혔는지 **반드시 확인한다.** 찍히지 않았으면 cron이 돌지 않은 것이다.
- 실패(종료 코드 1)와 경고(2 — 서버 밖 전송 안 됨, 디스크 70% 초과)를 받을 방법을 정한다(메일, 메신저 웹훅 등).
- **복원 연습을 한 번 한다**: PC에서 `gpg -d db-….dump.gpg > db.dump` 후 §1-2처럼 로컬에 복원해 본다.
- **다운 알림**: 외부 모니터(서비스는 운영자가 고른다)에 `https://api.eunsaem.com/actuator/health`를 등록한다.

---

## 6. 프론트 전환

은샘 프론트 레포(`eunsaem-frontend`)에서:

1. 코드 두 곳 수정이 들어간 버전인지 확인한다 — `next.config.ts`의 이미지 허용 패턴, `src/lib/markdown.ts`의 본문 이미지 경로.
2. 배포 환경변수 `NEXT_PUBLIC_API_BASE`를 `https://api.eunsaem.com/church/<slug>`로 바꾸고 다시 빌드·배포한다(빌드 시점에 박히는 값이다).
3. 확인: 로그인, 메인, 설교 상세, 갤러리 이미지, **본문에 이미지가 들어간 글**(§1-4에서 기록한 글), 주보 PDF, 관리자 업로드.

- 다르면: 환경변수를 원래 값(`https://api.eunsaem.com`)으로 되돌리고 재배포한다. 백엔드는 접두사 없는 요청도 그대로 받는다.

---

## 7. 2차 배포 — 마무리

이 단계 뒤로는 접두사 없는 주소(`https://api.eunsaem.com/api/…`)가 404가 된다.

### 7-1. 시작 조건

```bash
docker logs --since 168h church-backend-backend-1 2>&1 | grep -c '기본 교회로 처리'
```

- 기대: `0` — 프론트 전환 뒤 7일 동안 접두사 없는 요청이 없었다.
- 0이 아니면: `grep '기본 교회로 처리'`로 어떤 경로인지 본다. 열려 있던 옛 탭이면 기다리고, 프론트에 접두사를 안 붙이는 코드가 남았으면 고친다.
- 주의: 로그는 컨테이너를 다시 만들면(배포하면) 비워진다. 마지막 배포로부터 7일을 본다.
- 주의: 컨테이너 로그는 30MB까지만 남는다. `docker logs church-backend-backend-1 2>&1 | head -1`의 시각이 7일보다 최근이면
  로그가 넘친 것이므로, 그 시각부터의 기간만 확인된 것이다.
- 감수하는 것: 밖에 공유된 접두사 없는 링크(예: 카톡에 올린 주보 PDF 주소)는 이후 열리지 않는다.

### 7-2. 덤프 한 번 더

§2-2의 백업 명령을 **첫 줄(`stop backend`)만 빼고** 다시 실행하고 PC로 내려받는다. 이때 첫 숫자의 기대값은 **24**다
(1차 배포로 `churches` 테이블이 늘었다).

### 7-3. 배포

1. V20(`church_id` 기본값 제거)이 담긴 버전이 `main`에 있는지 확인한다.
2. 서버 `.env`에서 `TENANT_DEFAULT_SLUG` 줄을 지운다.
3. 배포 PR을 연다.
4. 확인:

```bash
curl -s -o /dev/null -w '%{http_code}\n' https://api.eunsaem.com/api/notices          # 기대: 404
curl -s -o /dev/null -w '%{http_code}\n' https://api.eunsaem.com/church/<slug>/api/notices   # 기대: 200
```

### 7-4. 되돌리기 (2차 배포 후) — 데이터를 잃지 않는 순서

1. **1차 릴리스 이미지를 재배포한다**(Deploy → `image_tag`에 1차 버전). 1차 코드는 `church_id`를 직접 채우므로 기본값이 없어도 동작한다. 접두사 없는 요청을 다시 받으려면 `.env`에 `TENANT_DEFAULT_SLUG`를 되살린다.
2. 전환 **이전** 이미지로 가야 한다면, 먼저 기본값을 되살린 뒤 재배포한다(교회가 은샘 하나뿐인 동안만 쓸 수 있다):

```bash
for t in positions roles members tags content_tags media sermons notices events departments gallery_albums gallery_photos \
         bulletins bible_challenges challenge_participations challenge_reading_logs inquiries vehicle_runs vehicle_requests; do
  docker compose exec -T postgres psql -U "$DBU" -d "$DBN" -c "ALTER TABLE $t ALTER COLUMN church_id SET DEFAULT 1"
done
```

3. **덤프 복원은 DB가 실제로 망가졌을 때만.** 덤프 이후에 생긴 회원·글·파일 기록이 사라진다.

---

## 8. 두 번째 교회

2차 배포, 자동 백업, 다운 알림이 갖춰진 뒤에 받는다. 먼저 시험용 교회를 하나 만들어 본다.

```bash
cd /srv/church-backend && API_BASE=https://api.eunsaem.com ./scripts/new-church.sh
curl -s https://api.eunsaem.com/church/<새 slug>/api/main      # 기대: 200, 빈 목록
```

- 시험용 교회의 대표자 계정으로 로그인해 글을 하나 쓰고, 은샘 사이트에 그 글이 보이지 않는지 확인한다.
- 절차 전체는 `docs/setup-new-church.md`.

---

## 9. 운영자용 SQL

운영자는 앱에 상시 계정을 두지 않는다. 필요할 때 아래를 쓴다. `<…>`만 바꿔 넣는다.
`PSQL="docker compose exec -T postgres psql -U $DBU -d $DBN"` 로 두고 `$PSQL -c "…"`.

**교회 id 찾기**

```sql
select id, name, slug from churches;
```

**임시 최고 관리자 만들기 / 지우기** (화면으로 직접 확인해야 할 때)

```sql
-- 만들기
with m as (
  insert into members (uuid, phone, name, password, terms_agreed, privacy_agreed, agreed_at, created_at, church_id)
  values (gen_random_uuid(), '<숫자만 전화번호>', '운영자(임시)', crypt('<임시 비밀번호>', gen_salt('bf', 10)), true, true, now(), now(), <교회 id>)
  returning id
)
insert into member_roles (member_id, role_id)
select m.id, r.id from m, roles r where r.church_id = <교회 id> and r.name in ('SUPER_ADMIN', 'USER');

-- 지우기 (soft delete — 작성 기록이 가리킬 수 있어 행은 남긴다)
update members set deleted_at = now() where church_id = <교회 id> and phone = '<숫자만 전화번호>' and name = '운영자(임시)';
```

**대표자가 뒤늦게 정해졌을 때** — 대표자가 일반 가입을 한 뒤:

```sql
insert into member_roles (member_id, role_id)
select m.id, r.id from members m, roles r
where m.church_id = <교회 id> and m.phone = '<대표자 숫자만 전화번호>' and m.deleted_at is null
  and r.church_id = <교회 id> and r.name = 'SUPER_ADMIN';
```

그다음 임시로 만들어 둔 운영자 계정을 위의 "지우기"로 정리한다.

**유일한 최고 관리자가 비밀번호를 잊었을 때**

```sql
update members set password = crypt('<임시 비밀번호>', gen_salt('bf', 10))
where church_id = <교회 id> and phone = '<숫자만 전화번호>' and deleted_at is null;
```

- 세 SQL 모두 `church_id` 조건을 빼지 않는다. 같은 전화번호가 여러 교회에 있을 수 있다.
- `crypt`는 `pgcrypto` 확장이다(새 교회 스크립트가 처음 실행될 때 만들어진다). 없으면 `create extension if not exists pgcrypto;`.

---

## 10. 그 밖의 운영 메모

- **OCI Security List에서 8080 인그레스 규칙을 지운다.** 1차 배포 뒤 8080은 서버 안(`127.0.0.1`)에만 열린다. 밖에서는 80/443만 쓴다.
- **`.env` 사본을 비밀번호 관리자에 둔다.** DB 비밀번호와 `JWT_SECRET`은 서버 `.env`에만 있다.
- **`JWT_SECRET`은 전 교회 공용이다.** 유출되면 전 교회의 토큰을 위조할 수 있다. 교체 절차: `.env`의 값을 바꾼다 →
  Actions의 Deploy를 `image_tag`에 현재 버전을 넣어 실행한다(컨테이너를 다시 만들어 새 값을 읽는다) → 전 회원이 다시 로그인해야 한다.
  서버에서 `docker compose … up -d --force-recreate`를 직접 실행하면 이미지 이름을 못 찾아 실패하고, 옛 시크릿으로 계속 돈다.
- **`CHURCH_NAME`·`CHURCH_SLUG`·`CORS_ALLOWED_ORIGIN`은 `.env`에서 지우지 않는다.** 값을 바꾸는 것은 문제없지만(첫 교회 행은 이미 만들어졌다), 없으면 기동이 실패한다. 교회의 프론트 주소를 바꾸려면 `churches.frontend_origin`을 고치고 `docker restart church-backend-backend-1`로 재시작한다.
- **postgres·redis의 로그 용량 제한**은 전환이 안정된 뒤 별도 배포로 넣는다. 두 서비스의 compose 설정을 바꾸면 배포 때 DB와 Redis가 재시작된다.
- **이름 정리**(레포 이름, 서버 호스트명)는 전환이 안정된 뒤 따로 한다. `/srv/church-backend` 디렉터리와 compose 프로젝트 이름은 바꾸지 않는다 — 볼륨 이름이 바뀌어 DB와 업로드 파일이 사라진 것처럼 보인다.
