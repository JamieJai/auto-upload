# auto-upload (backend)

상품등록 자동화 플랫폼 백엔드. Spring Boot 4.1 / Java 21.

## 배포 (auto-app, 192.168.0.41)

```
/opt/autoreg/
├── compose.yml   ← deploy/compose.yml
├── .env          ← deploy/.env.example 참고, 레포에 커밋하지 않음
├── backend/      ← 이 레포
└── frontend/     ← JamieJai/auto-upload-frontend (nginx 이미지로 빌드)

/data            ← HDD 1.5TB (hdd2t). images/, backup/pg/
```

```
cd /opt/autoreg && docker compose up -d --build backend
curl localhost:8080/actuator/health
```

## 백업

- `auto-db` PostgreSQL: `deploy/pg_backup.sh` 를 `autoreg-pg-backup.timer` 가 매일 03:30 실행 → `/data/backup/pg`, 14일 보관
- `auto-db` VM: Proxmox 백업 작업, 매주 일요일 04:00 → `hdd2t`, 최근 4개 보관
