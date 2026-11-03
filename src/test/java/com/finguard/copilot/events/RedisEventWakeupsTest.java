package com.finguard.copilot.events;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class RedisEventWakeupsTest {
    @Container static GenericContainer<?> REDIS=new GenericContainer<>("redis:8.2.8-alpine").withExposedPorts(6379);
    @Test void pubsubWakesAnotherProcessAndOutageDoesNotThrowIntoCommit() throws Exception {
        var factory=new LettuceConnectionFactory(REDIS.getHost(),REDIS.getMappedPort(6379));factory.afterPropertiesSet();factory.start();
        var template=new StringRedisTemplate(factory);var left=new AnalysisWakeups();var right=new AnalysisWakeups();var id=UUID.randomUUID();
        try(var a=new RedisEventWakeups(factory,template,left);var b=new RedisEventWakeups(factory,template,right);var watch=right.watch(id)) {
            a.start();b.start();
            for(int i=0;i<100 && (!a.subscribed() || !b.subscribed());i++) Thread.sleep(20);
            assertThat(a.subscribed()).isTrue();assertThat(b.subscribed()).isTrue();
            left.committed(id);
            assertThat(watch.awaitSignal(2000)).isTrue();
            factory.stop();
            assertThatCode(()->left.committed(id)).doesNotThrowAnyException();
        } finally {factory.destroy();}
    }
}
