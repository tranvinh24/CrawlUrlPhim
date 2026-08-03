# Hướng Dẫn Thiết Lập Docker Linux VM + SSH + Chạy JAR

## 1. Build và Test JAR (Windows)

```powershell
# Build fat JAR
cd D:\Bai2
mvn clean package

# Test chạy trực tiếp trên Windows (optional)
java -jar target/movie-crawler-jar-with-dependencies.jar
```

File JAR sẽ ở: `D:\Bai2\target\movie-crawler-jar-with-dependencies.jar`

---

## 2. Tạo SSH Key Pair (trên Windows)

Mở **PowerShell** và chạy:

```powershell
# Tạo SSH key pair (RSA 4096-bit)
ssh-keygen -t rsa -b 4096 -C "crawler-key" -f "$env:USERPROFILE\.ssh\crawler_key"

# Xem public key (sẽ copy vào container)
Get-Content "$env:USERPROFILE\.ssh\crawler_key.pub"
```

Lưu lại public key (`crawler_key.pub`) để dùng ở bước 4.
ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAACAQDQRAsj8iRg4dodrE5f56a2xk26MMGzUHE6EsswoscEDQ/JuJPzGGMy/ET9WNfutonxuVFLAk/U6r9aRPwuGNUrhUoTEOkx4mqj+ROqAyOMvWPVyxO8tpHwU7FO3BxyEq/9u2bLrAWP7fTDRchLNMDsWGJUyHn8dwWS90pGh2FTQMY9Fx891BdPM333M3BDX8AX9hUqsZo3nvm40nmRuyrIIBRK9INT/NmZtIGvB9qK+7DmkGmMOLA0aJGc00FEmb7HY3Pp54bpIf3t35TeI1vyI0c8cy2Vd2l1eb3kbILwA4RPHjTh8EnE3hEhpAfLXchvKVZvQ9lfHm/75sgvcPhJTtUKCVn1RBLm/rslPHrfogFRnbP5UJS6sUxc07CDQq/I2yu/OhACGzpuz7q0jm3pkqGZYIW3yldwO4XbIh04g705Ghn/n5RoIqN8FqUbtMPcQIX0rEgbyuRoHplkU5OuWMWB2rG1Z3BNJ8TQqi+VqOU9eJp+eaxKgDSlX3p9+SeURI2jcEcSJFRPimg+5siGeCC65kJ9JlbGVp8YqKiaU9118MawYxc72ywC1UorZlbzC2CEmFm6nfVRIjHKNMpCLVinx26PrRzvfsSppzJmhcdsL3SyWHXbDENIY+GfgAgapECOVUUV+xHo62ps20wj8QkrxYBSN2kHANTThhXmNw== crawler-key
---

## 3. Build Docker Image

```powershell
cd D:\Bai2
docker build -t linux-crawler .
```

---

## 4. Chạy Docker Container

```powershell
# Chạy container, map port SSH 2222 -> 22
docker run -d --name crawler-vm -p 2222:22 linux-crawler

# Kiểm tra container đang chạy
docker ps
```

---

## 5. Copy SSH Public Key Vào Container

```powershell
# Lấy public key
$pubKey = Get-Content "$env:USERPROFILE\.ssh\crawler_key.pub"

# Copy public key vào authorized_keys của user crawler trong container
docker exec crawler-vm bash -c "echo '$pubKey' >> /home/crawler/.ssh/authorized_keys"
docker exec crawler-vm bash -c "chmod 600 /home/crawler/.ssh/authorized_keys"
docker exec crawler-vm bash -c "chown -R crawler:crawler /home/crawler/.ssh"
```

---

## 6. SSH Vào Container Bằng Key (Không Password)

Dùng **PowerShell** hoặc **PuTTY**:

### Cách 1: PowerShell / Windows OpenSSH

```powershell
# SSH vào VM với private key
ssh -i "$env:USERPROFILE\.ssh\crawler_key" -p 2222 crawler@localhost

# Kiểm tra không cần password
```

### Cách 2: PuTTY

1. Mở **PuTTYgen**, load `crawler_key` → **Save private key** (.ppk format)
2. Mở **PuTTY**:
   - Host: `localhost`, Port: `2222`
   - Connection > SSH > Auth > Private key file: chọn file `.ppk`
   - Open → đăng nhập với user `crawler`

---

## 7. Copy JAR Vào Container Bằng SCP

```powershell
# SCP JAR vào container (chạy từ PowerShell trên Windows)
scp -i "$env:USERPROFILE\.ssh\crawler_key" -P 2222 `
    "D:\Bai2\target\movie-crawler-jar-with-dependencies.jar" `
    crawler@localhost:/home/crawler/

# Copy shell script
scp -i "$env:USERPROFILE\.ssh\crawler_key" -P 2222 `
    "D:\Bai2\run_crawler.sh" `
    crawler@localhost:/home/crawler/
```

---

## 8. Chạy Shell Script Trong Container

SSH vào container (bước 6), sau đó:

```bash
# Cấp quyền thực thi cho shell script
chmod +x /home/crawler/run_crawler.sh

# Chạy script (chạy JAR mỗi 5 giây)
./run_crawler.sh

# Hoặc chạy trong background với nohup
nohup ./run_crawler.sh > /home/crawler/logs/nohup.log 2>&1 &

# Xem log
tail -f /home/crawler/logs/crawler_$(date +%Y%m%d).log
```

---

## 9. Kiểm Tra Kết Quả

```bash
# Trong container - xem database SQLite
ls -la /home/crawler/movies.db

# Xem số lượng phim đã crawl
sqlite3 /home/crawler/movies.db "SELECT COUNT(*) FROM movies;"

# Xem danh sách phim
sqlite3 /home/crawler/movies.db \
  "SELECT title, year, country FROM movies LIMIT 20;"

# Xem genres của một phim
sqlite3 /home/crawler/movies.db \
  "SELECT m.title, g.genre FROM movies m JOIN movie_genres g ON m.id=g.movie_id LIMIT 20;"
```

---

## Tóm Tắt Cấu Trúc Database

| Table | Columns |
|-------|---------|
| `movies` | id, url, title, year, country, crawled_at |
| `movie_genres` | movie_id, genre |
| `movie_directors` | movie_id, director |
| `movie_actors` | movie_id, actor |

File `movies.db` là SQLite database đồng thời là backup trên disk.

---

## Dừng Container

```powershell
docker stop crawler-vm
docker rm crawler-vm
```
