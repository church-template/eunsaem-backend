# 배포 서버 초기 세팅 가이드 (OCI VM, 플랫폼 전체에 1회)

서버 한 대가 **모든 교회**를 서비스한다(멀티테넌시, #86). 이 문서는 그 서버를 처음 만들 때 한 번만 수행한다.
이후에는 `deploy` 브랜치 push마다 GitHub Actions가 자동 배포하고,
교회를 추가할 때는 서버를 새로 만들지 않고 `docs/setup-new-church.md`의 등록 스크립트만 실행한다.

전제: OCI VM (Ubuntu 22.04+ 권장, Ampere ARM/x86 모두 지원 — 이미지가 멀티아치).

## 1. Docker 설치

```bash
curl -fsSL https://get.docker.com | sudo sh
sudo usermod -aG docker $USER   # 재로그인 필요
docker compose version           # v2.x 확인
```

## 2. 배포 디렉터리 + `.env` 작성

```bash
sudo mkdir -p /srv/church-backend
sudo chown $USER:$USER /srv/church-backend
```

레포의 `.env.example`을 참고해 `/srv/church-backend/.env`를 작성한다.
반드시 채울 값:

| 키 | 설명 |
|---|---|
| `DB_PASSWORD` / `REDIS_PASSWORD` | 강한 랜덤 값 |
| `JWT_SECRET` | 플랫폼 전체에 하나(전 교회 공용). 유출되면 전 교회 토큰을 위조할 수 있다 |
| `CHURCH_NAME` / `CHURCH_SLUG` | **첫 교회**의 이름과 slug(소문자·숫자·하이픈). 첫 기동 때 V18 마이그레이션이 `churches` 첫 행으로 기록한다 |
| `CORS_ALLOWED_ORIGIN` | **첫 교회**의 프론트 origin(예: `https://www.<교회도메인>`). 위와 같이 V18이 한 번 읽는다 |

- `CHURCH_NAME`·`CHURCH_SLUG`·`CORS_ALLOWED_ORIGIN`은 빠지면 compose가 실행을 거부한다. 첫 기동 때 값이 **영구 기록**되므로
  오타 없이 넣는다. 그 뒤에는 런타임에서 쓰이지 않지만 **지우지 않는다**(값을 바꾸는 것은 무해하다).
- 선택: `TENANT_DEFAULT_SLUG` — 접두사(`/c/<slug>`) 없는 `/api/**` 요청을 처리할 교회. 첫 교회 프론트가 아직 접두사 없는
  주소를 쓰는 전환 기간에만 넣고, 끝나면 지운다. 비우면 그런 요청은 404이고, `churches`에 없는 slug를 넣으면 기동이 실패한다.
- 두 번째 교회부터는 `.env`를 건드리지 않는다 — 교회별 값은 `churches` 테이블에 있다(`docs/setup-new-church.md`).
- 최초 관리자를 만드는 환경변수는 없다. 서버를 새로 만든 경우 첫 교회(V18이 만든 행)에는 관리자가 없으므로,
  대표자가 일반 가입을 한 뒤 운영자가 DB에서 `SUPER_ADMIN`을 연결한다(`docs/setup-new-church.md` §3의 SQL).
  두 번째 교회부터는 등록 스크립트가 대표자 계정까지 만든다.

```bash
chmod 600 /srv/church-backend/.env
```

> `docker-compose.yml`과 `docker-compose.prod.yml`은 배포 워크플로우가 매번 scp로
> 동기화하므로 직접 복사할 필요 없다.

## 3. GHCR pull 인증 (레포가 private인 경우만)

read:packages 권한만 가진 PAT(classic)를 만들어 1회 로그인:

```bash
echo "<PAT>" | docker login ghcr.io -u <github-username> --password-stdin
```

## 4. 네트워크 개방 (OCI는 2중 방화벽)

**(a) OCI 콘솔** — VCN → 서브넷의 Security List(또는 NSG)에 인그레스 규칙 추가:
- TCP 22 (SSH — 가능하면 관리자 IP로 제한)
- TCP 80 / 443 (Caddy 리버스 프록시 — §7)

**8080은 열지 않는다.** 운영 compose(`docker-compose.prod.yml`)가 backend 포트를 `127.0.0.1:8080`에만 묶으므로
외부에서는 닿지 않고, host 네트워크의 Caddy만 `localhost:8080`으로 접근한다. 8080을 밖에 열면 TLS 없이 직접 접근할 수 있고,
앱이 프록시 헤더(`X-Forwarded-For`)를 신뢰하므로 클라이언트가 IP를 속여 문의·로그인 시도 제한을 우회할 수 있다.

**(b) OS 방화벽** — OCI 기본 이미지는 iptables가 별도로 막고 있다. Caddy는 host 네트워크로 뜨므로(§7)
외부에서 80/443이 닿지 않으면 OS 방화벽에서도 연다(8080 규칙은 넣지 않는다):

```bash
sudo iptables -I INPUT -p tcp -m multiport --dports 80,443 -j ACCEPT
sudo netfilter-persistent save   # Ubuntu (iptables-persistent)
```

## 5. 배포용 SSH 키 + GitHub Secrets

로컬(관리자 PC)에서 배포 전용 키 생성:

```bash
ssh-keygen -t ed25519 -f church-deploy-key -C "church-backend-deploy" -N ""
```

- 공개키(`church-deploy-key.pub`) 내용을 VM의 `~/.ssh/authorized_keys`에 추가.
- GitHub 레포 → Settings → Secrets and variables → Actions에 등록:

| Secret | 값 |
|---|---|
| `DEPLOY_HOST` | VM 공인 IP |
| `DEPLOY_USER` | SSH 유저 (Ubuntu 이미지 `ubuntu`, Oracle Linux `opc`) |
| `DEPLOY_SSH_KEY` | `church-deploy-key` **개인키 전문** |

앱 시크릿(DB/Redis 비밀번호, JWT_SECRET 등)은 GitHub에 **절대 넣지 않는다** — 서버 `.env`에만 존재.

## 6. 첫 배포 & 확인

`main → deploy` PR을 머지(또는 deploy에 push)하면 Actions가 배포한다. 서버에서 확인:

```bash
cd /srv/church-backend
docker compose ps                                   # backend/postgres/redis healthy
curl -f http://localhost:8080/actuator/health       # {"status":"UP"}
```

롤백: Actions → Deploy → Run workflow → `image_tag`에 이전 버전(예: `0.0.31`) 입력 후 실행.

## 7. HTTPS — Caddy 리버스 프록시

프론트가 별도 도메인(예: Vercel `https`)에서 API를 호출하면 백엔드도 **진짜 TLS**가 필요하다
(자체서명/IP는 브라우저의 mixed-content·인증서 거부). Caddy가 도메인별 Let's Encrypt 인증서를
자동 발급·갱신하며, 백엔드 재배포와 독립적으로(재부팅 시 자동) 뜬다.

전제: 80/443이 방화벽(§4)에서 열려 있을 것. 8080은 `127.0.0.1`에만 묶여 있어 외부에서 닿지 않는다(§4) —
원(raw) 백엔드를 직접 노출하지 않는다.

API 도메인은 **전 교회 공용 하나**다(현재 `api.eunsaem.com`). 교회는 도메인이 아니라 경로 접두사 `/c/<slug>`로
구분하므로, 교회를 추가해도 DNS·Caddy를 건드리지 않는다. 아래 `api.<교회도메인>`은 이 공용 API 도메인을 뜻한다.

### 7-1. DNS

도메인 관리처(가비아 등)에서 A레코드 추가: `api` → `<VM_IP>`.
전파 확인: `dig @8.8.8.8 api.<교회도메인> +short` → VM IP가 나오면 완료.

### 7-2. Caddy 기동

```bash
sudo mkdir -p /srv/caddy && sudo chown $USER:$USER /srv/caddy

cat > /srv/caddy/Caddyfile <<'EOF'
api.<교회도메인> {
    reverse_proxy localhost:8080
}
EOF

cat > /srv/caddy/docker-compose.yml <<'EOF'
services:
  caddy:
    image: caddy:2-alpine
    network_mode: host          # 호스트 80/443 사용 + localhost:8080 도달
    restart: unless-stopped
    volumes:
      - ./Caddyfile:/etc/caddy/Caddyfile
      - caddy-data:/data         # 발급 인증서 영속화(재발급 rate-limit 방지)
      - caddy-config:/config
volumes:
  caddy-data:
  caddy-config:
EOF

cd /srv/caddy && docker compose up -d && docker compose logs --tail 25
```

로그에 `certificate obtained successfully`(또는 에러 없는 `serving initial configuration`)가 뜨면 성공.

### 7-3. 교회의 프론트 주소(CORS)

CORS는 `.env`가 아니라 **`churches.frontend_origin`** 으로 정해진다 — 요청마다 그 교회의 origin 하나만 허용한다.

- **첫 배포 전**이라면 `.env`의 `CORS_ALLOWED_ORIGIN`에 첫 교회 프론트 주소를 정확히 넣는다(§2). 첫 기동 때 V18이 이 값을
  `churches` 첫 행에 기록한다.
- **첫 배포 후**에는 `.env`의 `CORS_ALLOWED_ORIGIN`을 바꿔도 아무 일도 일어나지 않는다. 교회의 프론트 주소를 바꾸려면
  DB 값을 고치고 backend를 재기동한다(교회 정보는 메모리에 캐시된다):

```bash
docker exec -it church-backend-postgres-1 psql -U <DB_USERNAME> -d <DB_NAME> \
  -c "UPDATE churches SET frontend_origin = 'https://<프론트도메인>' WHERE slug = '<slug>';"

docker restart church-backend-backend-1     # 재시작이면 충분하다(캐시만 비우면 된다)
```

서버에서 `docker compose … up -d`를 직접 실행하지 않는다 — 운영 compose는 이미지 이름·버전을 배포 워크플로가 넘겨 주는
값으로 정하므로 손으로 실행하면 엉뚱한 이미지를 찾다가 실패한다. `.env` 변경을 반영하려면(컨테이너를 다시 만들어야 한다)
Actions의 Deploy를 `image_tag`에 현재 버전을 넣어 실행한다.

`FILE_BASE_URL`은 바인딩만 되고 읽는 코드가 없는 죽은 설정이라 맞출 필요가 없다.

확인: `curl -s https://api.<교회도메인>/actuator/health` → `{"status":"UP"}`, 브라우저에서 자물쇠(유효 인증서).
교회별 API는 `https://api.<교회도메인>/c/<slug>/api/main`으로 확인한다.
