# auto-upload (backend)

상품등록 자동화 플랫폼 백엔드. Spring Boot 4.1 / Java 21.

## 배포 (auto-app, 192.168.0.41)

```
/opt/autoreg/
├── compose.yml   ← deploy/compose.yml
├── .env          ← deploy/.env.example 참고, 레포에 커밋하지 않음
└── backend/      ← 이 레포
```

```
cd /opt/autoreg && docker compose up -d --build backend
curl localhost:8080/actuator/health
```
