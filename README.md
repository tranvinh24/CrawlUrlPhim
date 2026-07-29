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

## Công nghệ sử dụng

| Thành phần            | Thư viện                          |
|-----------------------|-----------------------------------|
| HTTP / Phân tích HTML | Jsoup 1.17.2                      |
| Phân tích JSON        | Gson 2.10.1                       |
| Cơ sở dữ liệu         | SQLite JDBC 3.45.1.0              |
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
    db/DatabaseManager.java     Tầng lưu trữ SQLite
    crawler/UrlRepository.java  Danh sách URL cần crawl
    crawler/MovieCrawler.java   Trích xuất dữ liệu từ HTML
    web/WebServer.java          Khởi động HTTP server trên port 8080
    web/MovieHandler.java       Xử lý request và trả về JSON
src/main/resources/
    logback.xml                 Cấu hình logging
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

## Kết quả đầu ra (chế độ crawl)

```
URLs đã xử lý  : 100
Lưu mới        : 87
Đã có trong DB : 10
Thất bại       : 3
Tổng trong DB  : 87
```
