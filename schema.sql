-- ============================================================
-- CrawlUrlPhim – MySQL schema initialisation script
-- Run once: mysql -u root -p < schema.sql
-- ============================================================

CREATE DATABASE IF NOT EXISTS movies
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

USE movies;

CREATE TABLE IF NOT EXISTS movies (
    id          VARCHAR(255) PRIMARY KEY,
    url         VARCHAR(2083) NOT NULL,
    title       TEXT,
    year        VARCHAR(10),
    country     VARCHAR(100),
    crawled_at  DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS movie_genres (
    movie_id    VARCHAR(255) NOT NULL,
    genre       VARCHAR(255) NOT NULL,
    PRIMARY KEY (movie_id, genre),
    FOREIGN KEY (movie_id) REFERENCES movies(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS movie_directors (
    movie_id    VARCHAR(255) NOT NULL,
    director    VARCHAR(255) NOT NULL,
    PRIMARY KEY (movie_id, director),
    FOREIGN KEY (movie_id) REFERENCES movies(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS movie_actors (
    movie_id    VARCHAR(255) NOT NULL,
    actor       VARCHAR(255) NOT NULL,
    PRIMARY KEY (movie_id, actor),
    FOREIGN KEY (movie_id) REFERENCES movies(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
