# T45 - Multi-threading Patterns in Network Programming

Project Java minh hoa va so sanh ba cach xu ly nhieu ket noi HTTP:

1. **Single-thread:** xu ly tung client lan luot.
2. **Thread-per-Connection:** moi client duoc mot thread rieng.
3. **Fixed Thread Pool:** so worker co dinh, request con lai cho trong queue.

Project chi dung Java chuan, khong can Maven hay thu vien ngoai.

## Cau truc

```text
.
├── src/
│   ├── ServerApp.java       # Ca ba loai server, HTTP handler va metrics
│   └── LoadTestClient.java  # Tao request dong thoi, do hieu nang
├── scripts/
│   ├── build.ps1
│   ├── run-server.ps1
│   ├── run-load-test.ps1
│   └── demo-all.ps1
├── .gitignore
└── README.md
```

Thu muc `out/` va `results/` duoc tao tu dong khi chay, khong dua len GitHub.

## Yeu cau

- JDK 21 tro len.
- Windows PowerShell 5.1 hoac PowerShell 7.

Kiem tra:

```powershell
java -version
javac -version
```

## How to run

(Chay tat ca) Mo PowerShell tai thu muc project va chay:

```powershell
.\scripts\demo-all.ps1
```

Script se tu dong:

1. Bien dich code.
2. Chay Single-thread va benchmark.
3. Chay Thread-per-Connection va benchmark.
4. Chay Thread Pool va benchmark.
5. Luu so lieu vao `results/benchmark.csv`.

Neu PowerShell chan script:

```powershell
Set-ExecutionPolicy -Scope Process Bypass
.\scripts\demo-all.ps1
```

## Chay thu cong tung thread

Can hai cua so PowerShell cung mo tai thu muc project.

### 1. Single-thread

Terminal 1:

```powershell
.\scripts\run-server.ps1 -Mode single
```

Terminal 2:

```powershell
.\scripts\run-load-test.ps1 `
  -Url "http://localhost:8080/sleep?ms=1000" `
  -Requests 10 `
  -Concurrency 10 `
  -Warmup 0
```

Ket qua mong doi: log chi co thread `main`; 10 request mat khoang 10 giay.

Nhan `Ctrl+C` o Terminal 1 de dung server.

### 2. Thread-per-Connection

Terminal 1:

```powershell
.\scripts\run-server.ps1 -Mode thread -SkipBuild
```

Chay lai dung lenh load test o Terminal 2.

Ket qua mong doi: log co `client-1`, `client-2`, ...; cac request chay gan nhu dong thoi.

### 3. Fixed Thread Pool

Terminal 1:

```powershell
.\scripts\run-server.ps1 -Mode pool -PoolSize 4 -SkipBuild
```

Chay lai dung lenh load test.

Ket qua mong doi: chi co `pool-worker-1` den `pool-worker-4`; 10 request duoc xu ly theo khoang ba dot.

## Cac dia chi cua server

Khi server dang chay:

| URL | Chuc nang |
|---|---|
| `http://localhost:8080/` | Trang gioi thieu va mode hien tai |
| `http://localhost:8080/health` | Kiem tra server dang chay |
| `http://localhost:8080/sleep?ms=1000` | Gia lap tac vu cham |
| `http://localhost:8080/metrics` | Xem bo dem request va latency |

## Y nghia ket qua

- **Total time:** tong thoi gian hoan thanh bai test.
- **Throughput:** so request hoan thanh moi giay.
- **Average latency:** do tre trung binh.
- **p50/p95:** 50%/95% request co do tre khong vuot qua gia tri nay.
- **Success/failed:** request thanh cong/that bai.

Thread-per-Connection thuong nhanh trong demo nho vi co the tao mot thread cho moi client. Thread Pool gioi han so thread, tai su dung worker va kiem soat tai nguyen tot hon khi tai tang.

## Build va chay khong qua script

```powershell
javac -encoding UTF-8 -d out src\ServerApp.java src\LoadTestClient.java
java -cp out ServerApp --mode=pool --port=8080 --pool-size=4 --queue-size=100
```

Tai terminal khac:

```powershell
java -cp out LoadTestClient --url=http://localhost:8080/sleep?ms=100 --requests=20 --concurrency=10 --warmup=2
```
```powershell
git remote -v
```
