package com.elipair.church.global.config;

import com.elipair.church.global.tenant.TenantContext;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import tools.jackson.databind.jsontype.PolymorphicTypeValidator;

/**
 * Redis 캐시 추상화(스펙 §9). /api/main을 @Cacheable, 콘텐츠 CUD 시 @CacheEvict로 무효화.
 * TTL은 CACHE_TTL(초, 기본 60)로 주입 — 교회별 조정값(멀티-교회 템플릿: 하드코딩 금지).
 * SB4=Jackson 3이므로 값 직렬화기는 GenericJacksonJsonRedisSerializer(Jackson 3, spring-data-redis 4)를 쓴다.
 */
@Configuration
@EnableCaching
public class CacheConfig {

    private final long ttlSeconds;

    public CacheConfig(@Value("${cache.ttl:60}") long ttlSeconds) {
        this.ttlSeconds = ttlSeconds;
    }

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        // 값에 @class 타입정보를 심어 final record(MainResponse)까지 정확히 역직렬화하되,
        // PolymorphicTypeValidator로 허용 타입을 allowlist 제한한다(임의 타입 역직렬화 가젯 경로 차단 — 보안 하드닝).
        // 캐시 값(MainResponse + 카드 DTO + 컬렉션 + 시간 타입)이 쓰는 패키지만 허용한다.
        PolymorphicTypeValidator typeValidator = BasicPolymorphicTypeValidator.builder()
                .allowIfSubType("com.elipair.church.")
                .allowIfSubType("java.util.")
                .allowIfSubType("java.time.")
                .build();
        RedisCacheConfiguration config = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofSeconds(ttlSeconds))
                // 멀티테넌시(#86): 캐시 키 접두사에 교회 id를 넣는다("main::{churchId}::"). 접두사는 호출마다 계산되므로
                // @Cacheable/@CacheEvict는 그대로 두고, 조회와 무효화(allEntries 포함)가 모두 현재 교회 키에만 닿는다.
                .computePrefixWith(cacheName -> cacheName + "::" + TenantContext.require() + "::")
                .disableCachingNullValues()
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(
                        GenericJacksonJsonRedisSerializer.builder()
                                .enableDefaultTyping(typeValidator)
                                .build()));
        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(config)
                .build();
    }
}
