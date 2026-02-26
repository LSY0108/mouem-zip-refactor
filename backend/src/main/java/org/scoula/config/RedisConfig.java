package org.scoula.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;

import javax.annotation.PostConstruct;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Configuration
@EnableCaching
@Log4j2
public class RedisConfig {

    @Value("${spring.redis.host}")
    private String redisHost;

    @Value("${spring.redis.port}")
    private int redisPort;

    @Bean
    public RedisConnectionFactory redisConnectionFactory() {
        return new LettuceConnectionFactory(redisHost, redisPort);
    }


    /**
     * typedMapper()
     * - DTO, 엔티티 등 '객체 그대로' 캐싱할 때 사용
     * - 타입 정보를 함께 저장해서 Redis에서 꺼낼 때 원래 객체 형태로 복원 가능
     * - LinkedHashMap 예외 방지용
     */
    private ObjectMapper typedMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        mapper.activateDefaultTyping(
                mapper.getPolymorphicTypeValidator(),
                ObjectMapper.DefaultTyping.NON_FINAL
        );
        return mapper;
    }


    @Bean
    public RedisCacheManager cacheManager (RedisConnectionFactory cf, ObjectMapper redisObjectMapper) {

        var typedSerializer = new GenericJackson2JsonRedisSerializer(typedMapper());

        var defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(10))
                .disableCachingNullValues()
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(typedSerializer));

        // 캐시 이름별 TTL
        Map<String, RedisCacheConfiguration> perCache = new HashMap<>();
        perCache.put("reports:list", defaultConfig.entryTtl(Duration.ofMinutes(5)));
        perCache.put("reports:detail", defaultConfig.entryTtl(Duration.ofDays(7)));
        perCache.put("contracts:list", defaultConfig.entryTtl(Duration.ofMinutes(15)));
        perCache.put("contracts:detail", defaultConfig.entryTtl(Duration.ofMinutes(30)));
        perCache.put("diagnosis:result", defaultConfig.entryTtl(Duration.ofDays(7)));
        perCache.put("safety:result", defaultConfig.entryTtl(Duration.ofDays(7)));
        perCache.put("registry:list", defaultConfig.entryTtl(Duration.ofMinutes(5)));

        return RedisCacheManager.builder(cf)
                .cacheDefaults(defaultConfig)
                .withInitialCacheConfigurations(perCache)
                .build();
    }
}
