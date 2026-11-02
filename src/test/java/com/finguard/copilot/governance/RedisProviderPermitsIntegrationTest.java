package com.finguard.copilot.governance;

import static org.assertj.core.api.Assertions.*;
import java.time.Duration;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
class RedisProviderPermitsIntegrationTest {
    @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:8.2.8-alpine").withExposedPorts(6379);
    LettuceConnectionFactory factory;StringRedisTemplate redis;
    @BeforeEach void init() {
        factory=new LettuceConnectionFactory(REDIS.getHost(),REDIS.getMappedPort(6379));factory.afterPropertiesSet();factory.start();
        redis=new StringRedisTemplate(factory);
        try(var connection=factory.getConnection()) {connection.serverCommands().flushDb();}
    }
    @AfterEach void close() {factory.destroy();}
    @Test void atomicallyLimitsProviderConcurrencyAndReleasesOnlyItsOwner() {
        var permits=new RedisProviderPermits(redis,1,10);
        var first=permits.acquire("a".repeat(64),Duration.ofSeconds(1));
        assertThatThrownBy(()->permits.acquire("b".repeat(64),Duration.ofSeconds(1))).hasMessageContaining("capacity");
        permits.release(new RedisProviderPermits.Lease("not-the-owner"));
        assertThat(permits.valid(first,Duration.ZERO)).isTrue();
        permits.release(first);
        assertThat(permits.acquire("b".repeat(64),Duration.ofSeconds(1))).isNotNull();
    }
    @Test void expiredOwnerCannotReleaseNewLeaseAndReviewerQuotaSurvivesRelease() {
        var permits=new RedisProviderPermits(redis,1,1);
        var old=permits.acquire("a".repeat(64),Duration.ofSeconds(1));
        redis.opsForZSet().add(RedisProviderPermits.LEASE_KEY,old.ownerToken(),0);
        var current=permits.acquire("b".repeat(64),Duration.ofSeconds(1));
        permits.release(old);
        assertThat(permits.valid(current,Duration.ZERO)).isTrue();
        permits.release(current);
        assertThatThrownBy(()->permits.acquire("b".repeat(64),Duration.ofSeconds(1))).hasMessageContaining("capacity");
    }
    @Test void missingRedisNeverGrantsPermission() {
        var unavailable=org.mockito.Mockito.mock(StringRedisTemplate.class);
        org.mockito.Mockito.when(unavailable.execute(org.mockito.ArgumentMatchers.any(org.springframework.data.redis.core.script.RedisScript.class),
                org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.<Object[]>any()))
                .thenThrow(new org.springframework.data.redis.RedisConnectionFailureException("test unavailable"));
        assertThatThrownBy(()->new RedisProviderPermits(unavailable,1,10).acquire("a".repeat(64),Duration.ofSeconds(1)))
                .hasMessageContaining("capacity");
    }
}
