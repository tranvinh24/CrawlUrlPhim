package org.CrawlUrlPhim.crawler;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Provides a list of ~100 movie URLs from toivote.com.
 * Starts with hardcoded real UUIDs and optionally discovers more from the site.
 */
public class UrlRepository {

    private static final Logger logger = LoggerFactory.getLogger(UrlRepository.class);
    private static final String BASE_URL = "https://toivote.com";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private static final int TIMEOUT_MS = 15000;

    // Real movie UUIDs extracted from toivote.com
    private static final List<String> KNOWN_IDS = Arrays.asList(
        "51a2de2f-2a62-4c5a-a333-7bcf18959366",
        "a8ec8f01-0c61-4540-851f-93d330839401",
        "9df2e91c-db9e-4b24-87b4-e9ffe6d75d76",
        "3ff95344-b359-439d-937a-757c720e8eaa",
        "65c394b1-3bab-456e-92ca-3abc544e5ab5",
        "074d82e4-c286-4645-836d-0b0c63935136",
        "080a89c4-11dd-43b1-ae5a-123dd4b52910",
        "168b04e0-dbe4-48f6-9dfe-2b48f877dfa5",
        "2d9acb2c-dcb9-4a8b-8ab5-61d0c61fd50c",
        "3f7b2e15-8fd3-49e5-a267-fc739184992d",
        "5a880933-36a9-4e48-8b6f-ad06f55fb1ed",
        "5c10ed04-8d3b-4d9d-88df-0fd3ee923143",
        "7fec4dbf-20bb-482b-817e-7b67c2e69131",
        "841e509c-dd7e-4cfc-8539-57ed96eb5f32",
        "86adb6a0-1d87-40a1-99cf-813a6b49a3f7",
        "8a9c672a-7873-45a4-bd93-bd8381e48c00",
        "8df53c62-df00-4cff-a88d-89666eda58a7",
        "929e8ddf-e9ad-4b09-833f-42951a5ac9a8",
        "93eab6db-7bb2-426b-8132-ab94c79f82a2",
        "978c2d5a-7dca-47e2-aad0-4a5720786ac5",
        "ae52ec46-aef3-4814-95ce-0fc7b9dfd28e",
        "b8323d7b-247f-48c2-8cf7-06bd36d4c100",
        "b983a5a4-dd92-4943-9216-7b3937a8215c",
        "be6bdf1e-f139-49d3-9e3a-0717e749144b",
        "c8d6bea1-ee43-4b83-b402-f8dabff62986",
        "e8906eaf-53fb-4712-803f-f325a5dd7806",
        "fc388b8d-5c13-437f-814b-4fb22a0d98c7",
        "bb200000-feed-0001-0000-000000000001"
    );

    /**
     * Returns a deduplicated list of movie URLs.
     * Tries to discover more by scraping discovery pages, padding to ~100.
     */
    public static List<String> getUrls() {
        Set<String> idSet = new LinkedHashSet<>(KNOWN_IDS);

        // Discover more from homepage and search/leaderboard pages
        List<String> discoveryPages = Arrays.asList(
            BASE_URL + "/",
            BASE_URL + "/leaderboard",
            BASE_URL + "/search"
        );

        for (String page : discoveryPages) {
            if (idSet.size() >= 100) break;
            try {
                logger.info("Discovering movie URLs from: {}", page);
                Document doc = Jsoup.connect(page)
                        .userAgent(USER_AGENT)
                        .timeout(TIMEOUT_MS)
                        .get();

                Set<String> found = extractMovieIds(doc.html());
                logger.info("Found {} movie IDs on {}", found.size(), page);
                idSet.addAll(found);
            } catch (IOException e) {
                logger.warn("Could not discover from {}: {}", page, e.getMessage());
            }
        }

        // Build URL list
        List<String> urls = new ArrayList<>();
        for (String id : idSet) {
            urls.add(BASE_URL + "/movie/" + id);
            if (urls.size() >= 100) break;
        }

        logger.info("Total URLs to crawl: {}", urls.size());
        return urls;
    }

    /**
     * Extracts movie UUIDs from raw HTML using regex.
     */
    private static Set<String> extractMovieIds(String html) {
        Set<String> ids = new LinkedHashSet<>();
        Pattern pattern = Pattern.compile("/movie/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})");
        Matcher matcher = pattern.matcher(html);
        while (matcher.find()) {
            ids.add(matcher.group(1));
        }
        return ids;
    }
}
