package org.example.crawler;

import com.google.gson.*;
import org.example.model.Movie;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Crawls individual movie pages from toivote.com and extracts structured movie data.
 * Extracts data from the Schema.org LD+JSON script tag embedded in each movie page.
 */
public class MovieCrawler {

    private static final Logger logger = LoggerFactory.getLogger(MovieCrawler.class);
    private static final int TIMEOUT_MS = 15000;
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    /**
     * Crawls a movie detail page and returns a populated Movie object.
     *
     * @param url The full URL of the movie detail page (e.g. https://toivote.com/movie/{uuid})
     * @return Movie object with extracted data, or null if crawl fails
     */
    public Movie crawl(String url) {
        logger.info("Crawling: {}", url);
        try {
            Document doc = Jsoup.connect(url)
                    .userAgent(USER_AGENT)
                    .timeout(TIMEOUT_MS)
                    .followRedirects(true)
                    .get();

            // Extract movie ID from URL
            String id = extractIdFromUrl(url);

            // Prefer LD+JSON Schema.org data (most reliable, server-rendered)
            Movie movie = parseFromLdJson(doc, url, id);
            if (movie != null) {
                // Country is NOT in LD+JSON — parse from HTML dt/dd pairs
                String country = extractCountryFromHtml(doc);
                if (country != null) {
                    movie.setCountry(country);
                }
                logger.info("SUCCESS: [{}] - {} ({})", id, movie.getTitle(), movie.getYear());
                return movie;
            }

            logger.warn("Could not parse LD+JSON for URL: {}", url);
            return null;

        } catch (IOException e) {
            logger.error("Failed to fetch URL {}: {}", url, e.getMessage());
            return null;
        }
    }

    /**
     * Parses Schema.org Movie LD+JSON from the page's <script type="application/ld+json"> tags.
     */
    private Movie parseFromLdJson(Document doc, String url, String id) {
        Elements scripts = doc.select("script[type=application/ld+json]");
        Gson gson = new Gson();

        for (Element script : scripts) {
            String json = script.html();
            try {
                JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
                String type = obj.has("@type") ? obj.get("@type").getAsString() : "";
                if (!"Movie".equals(type)) continue;

                Movie movie = new Movie();
                movie.setId(id);
                movie.setUrl(url);

                // Title
                movie.setTitle(getStringOrNull(obj, "name"));

                // Year (dateCreated)
                movie.setYear(getStringOrNull(obj, "dateCreated"));

                // Genres
                List<String> genres = new ArrayList<>();
                if (obj.has("genre")) {
                    JsonElement genreEl = obj.get("genre");
                    if (genreEl.isJsonArray()) {
                        genreEl.getAsJsonArray().forEach(g -> genres.add(g.getAsString()));
                    } else if (genreEl.isJsonPrimitive()) {
                        genres.add(genreEl.getAsString());
                    }
                }
                movie.setGenres(genres);

                // Directors
                List<String> directors = new ArrayList<>();
                if (obj.has("director")) {
                    JsonElement dirEl = obj.get("director");
                    extractPersonNames(dirEl, directors);
                }
                movie.setDirectors(directors);

                // Actors
                List<String> actors = new ArrayList<>();
                if (obj.has("actor")) {
                    JsonElement actorEl = obj.get("actor");
                    extractPersonNames(actorEl, actors);
                }
                movie.setActors(actors);

                return movie;

            } catch (JsonSyntaxException | IllegalStateException e) {
                logger.debug("Skipping non-Movie LD+JSON block: {}", e.getMessage());
            }
        }
        return null;
    }

    /**
     * Extracts country from the HTML definition list (dt/dd pairs).
     * Looks for <dt>Đất nước</dt><dd>...</dd>
     */
    private String extractCountryFromHtml(Document doc) {
        // Find <dl> containing movie info
        Elements dts = doc.select("dl dt");
        for (Element dt : dts) {
            String dtText = dt.text().trim();
            if (dtText.equalsIgnoreCase("Đất nước") || dtText.equalsIgnoreCase("Country")) {
                Element dd = dt.nextElementSibling();
                if (dd != null && dd.tagName().equals("dd")) {
                    return dd.text().trim();
                }
            }
        }
        return null;
    }

    /**
     * Extracts person names from a JSON element that can be either an array or single object.
     */
    private void extractPersonNames(JsonElement el, List<String> nameList) {
        if (el.isJsonArray()) {
            for (JsonElement item : el.getAsJsonArray()) {
                if (item.isJsonObject()) {
                    String name = getStringOrNull(item.getAsJsonObject(), "name");
                    if (name != null) nameList.add(name);
                }
            }
        } else if (el.isJsonObject()) {
            String name = getStringOrNull(el.getAsJsonObject(), "name");
            if (name != null) nameList.add(name);
        }
    }

    /**
     * Safely gets a string value from a JSON object, returning null if absent.
     */
    private String getStringOrNull(JsonObject obj, String key) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            return obj.get(key).getAsString();
        }
        return null;
    }

    /**
     * Extracts the UUID from a toivote.com movie URL.
     * e.g. https://toivote.com/movie/51a2de2f-2a62-4c5a-a333-7bcf18959366
     */
    private String extractIdFromUrl(String url) {
        Pattern pattern = Pattern.compile("/movie/([a-f0-9\\-]{36})");
        Matcher matcher = pattern.matcher(url);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return url; // fallback
    }
}
