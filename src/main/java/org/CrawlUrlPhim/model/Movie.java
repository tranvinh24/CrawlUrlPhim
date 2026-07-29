package org.CrawlUrlPhim.model;

import java.util.List;

/**
 * Represents movie data crawled from toivote.com
 */
public class Movie {
    private String id;
    private String url;
    private String title;
    private String year;
    private String country;
    private List<String> genres;
    private List<String> directors;
    private List<String> actors;

    public Movie() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getYear() { return year; }
    public void setYear(String year) { this.year = year; }

    public String getCountry() { return country; }
    public void setCountry(String country) { this.country = country; }

    public List<String> getGenres() { return genres; }
    public void setGenres(List<String> genres) { this.genres = genres; }

    public List<String> getDirectors() { return directors; }
    public void setDirectors(List<String> directors) { this.directors = directors; }

    public List<String> getActors() { return actors; }
    public void setActors(List<String> actors) { this.actors = actors; }

    @Override
    public String toString() {
        return "Movie{" +
               "title='" + title + '\'' +
               ", year='" + year + '\'' +
               ", country='" + country + '\'' +
               ", genres=" + genres +
               ", directors=" + directors +
               ", actors=" + actors +
               '}';
    }
}
