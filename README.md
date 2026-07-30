# CrawlUrlPhim

Công cụ crawl dữ liệu phim viết bằng Java, thu thập thông tin từ [toivote.com](https://toivote.com), lưu trữ vào SQLite và cung cấp HTTP API để truy vấn dữ liệu.

## Tính năng

- Thu thập thông tin phim: tiêu đề, năm phát hành, quốc gia, thể loại, đạo diễn, diễn viên
- Phân tích dữ liệu cấu trúc Schema.org JSON-LD nhúng trong HTML
- Lưu dữ liệu vào SQLite theo mô hình chuẩn hóa (`movies.db`)
- Bỏ qua phim đã crawl để hỗ trợ chạy tăng dần
- Giới hạn tốc độ request để tránh quá tải server
- Ghi log có cấu trúc qua SLF4J + Logback
- Web service trả về dữ liệu phim theo URL dưới dạng JSON
- **Cache TTL tùy chỉnh** giảm số lần truy cập database cho các URL lặp lại

## Công nghệ sử dụng

| Thành phần            | Thư viện                          |
|-----------------------|-----------------------------------|
| HTTP / Phân tích HTML | Jsoup 1.17.2                      |
| Phân tích JSON        | Gson 2.10.1                       |
| Cơ sở dữ liệu         | MySQL 8 + mysql-connector-j 8.3.0 |
| Connection Pool       | HikariCP 5.1.0                    |
| Logging               | SLF4J 2.0.12 + Logback 1.5.3     |
| Build                 | Maven 3, Java 17                  |

## Cấu trúc cơ sở dữ liệu

```
movies          (id, url, title, year, country, crawled_at)
movie_genres    (movie_id, genre)
movie_directors (movie_id, director)
movie_actors    (movie_id, actor)
```

## Cấu trúc thư mục

```
src/main/java/org/CrawlUrlPhim/
    Main.java                   Điểm vào, hỗ trợ 2 chế độ: crawl và server
    model/Movie.java            Mô hình dữ liệu phim
    db/DatabaseManager.java     Tầng lưu trữ MySQL (HikariCP)
    crawler/UrlRepository.java  Danh sách URL cần crawl
    crawler/MovieCrawler.java   Trích xuất dữ liệu từ HTML
    cache/CacheTTL.java         Cache TTL tùy chỉnh (generic, thread-safe)
    web/WebServer.java          Khởi động HTTP server trên port 8080
    web/MovieHandler.java       Xử lý request, tích hợp cache, trả về JSON
src/main/resources/
    logback.xml                 Cấu hình logging
    db.properties               Cấu hình kết nối MySQL (mặc định)
schema.sql                      Script tạo database MySQL (chạy một lần)
```

## Cài đặt MySQL

**1. Tạo database (chạy một lần):**

```bash
mysql -u root -p < schema.sql
```

**2. Cấu hình kết nối** – chỉnh `src/main/resources/db.properties` hoặc truyền qua JVM flags:

```properties
db.host=localhost
db.port=3306
db.name=movies
db.user=root
db.password=your_password
```

Hoặc truyền khi chạy:

```bash
java -Ddb.host=localhost -Ddb.password=secret \
     -jar target/movie-crawler-jar-with-dependencies.jar
```

## Build và chạy

**Yêu cầu:** Java 17, Maven 3

```bash
mvn clean package -q
```

### Chế độ Crawl

Thu thập dữ liệu phim từ toivote.com và lưu vào `movies.db`:

```bash
java -jar target/movie-crawler-jar-with-dependencies.jar
```

### Chế độ Web Service

Khởi động HTTP server trên port 8080:

```bash
java -jar target/movie-crawler-jar-with-dependencies.jar --server
```

Truy vấn thông tin phim theo URL:

```
GET http://localhost:8080/movie?url=https://toivote.com/movie/{uuid}
```

Ví dụ phản hồi:

```json
{
  "id": "51a2de2f-2a62-4c5a-a333-7bcf18959366",
  "url": "https://toivote.com/movie/51a2de2f-2a62-4c5a-a333-7bcf18959366",
  "title": "Tên phim",
  "year": "2023",
  "country": "Mỹ",
  "genres": ["Hành động", "Phiêu lưu"],
  "directors": ["Tên đạo diễn"],
  "actors": ["Diễn viên A", "Diễn viên B"]
}
```

**Docker:**

```bash
docker build -t crawl-url-phim .
docker run crawl-url-phim
```

## Cache TTL

Web service tích hợp **`CacheTTL<K, V>`** — một cache tùy chỉnh, generic, thread-safe — để giảm số lần truy cập database khi cùng một URL phim được yêu cầu nhiều lần.

### Cơ chế hoạt động

| Chính sách       | Tham số | Mô tả |
|------------------|---------|-------|
| **Write TTL** (m) | 120 s  | Entry tự động xóa sau `m` giây kể từ lần `put()` đầu tiên, bất kể có được đọc hay không |
| **Idle TTL** (n)  | 30 s   | Entry tự động xóa nếu không có `get()` nào trong `n` giây; mỗi lần đọc thành công **reset** bộ đếm |

Cả hai bộ đếm chạy đồng thời — entry bị xóa khi **một trong hai** điều kiện xảy ra trước.

### Luồng xử lý request

```
GET /movie?url=...
        │
        ▼
  cache.get(url)  ──HIT──▶  Trả kết quả ngay (không query DB)
        │
       MISS
        │
        ▼
  db.getMovieByUrl(url)
        │
        ├── Không tìm thấy ──▶  404 Not Found
        │
        └── Tìm thấy ──▶  cache.put(url, movie) ──▶  200 OK
```

### API thống kê cache

```
GET http://localhost:8080/movie/cache-stats
```

Ví dụ phản hồi:

```json
{
  "cacheSize": 5,
  "hitRate": "73.33%",
  "idleTtlSeconds": 30,
  "writeTtlSeconds": 120,
  "liveEntries": {
    "https://toivote.com/movie/...": { "title": "...", "year": "2023" }
  }
}
```

### Class `CacheTTL<K, V>`

```java
// Khởi tạo: CacheTTL(int idleTtlSeconds, int writeTtlSeconds)
CacheTTL<String, Movie> cache = new CacheTTL<>(30, 120);

cache.put(url, movie);              // ghi vào cache, bắt đầu cả 2 TTL
Movie m = cache.get(url);           // đọc; reset idle TTL nếu hit
Map<String,Movie> all = cache.getMap();  // snapshot tất cả entry còn sống
double rate = cache.getHitRate();        // tỷ lệ hit (%), ví dụ: 75.0
cache.shutdown();                   // dừng background eviction thread
```

## Kết quả đầu ra (chế độ crawl)

```
URLs đã xử lý  : 100
Lưu mới        : 87
Đã có trong DB : 10
Thất bại       : 3
Tổng trong DB  : 87
```
