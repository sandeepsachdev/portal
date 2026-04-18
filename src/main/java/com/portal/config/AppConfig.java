package com.portal.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Configuration
@EnableCaching
public class AppConfig {

    @Bean
    public RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(10_000);
        return new RestTemplate(factory);
    }

    @Bean
    public CacheManager cacheManager() {
        return new SimpleCacheManager(){{
            setCaches(List.of(
                cache("netflixShows",  120),  // 2 h  — releases are infrequent
                cache("news",           15),  // 15 m — news changes frequently
                cache("wikiArticles",   30),  // 30 m
                cache("trends",         15)   // 15 m
            ));
        }};
    }

    private static CaffeineCache cache(String name, long minutes) {
        return new CaffeineCache(name,
                Caffeine.newBuilder()
                        .expireAfterWrite(minutes, TimeUnit.MINUTES)
                        .build());
    }
}
