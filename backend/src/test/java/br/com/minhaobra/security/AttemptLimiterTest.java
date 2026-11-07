package br.com.minhaobra.security;

import br.com.minhaobra.service.ApiException;
import java.time.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AttemptLimiterTest {
    @Test void limitsAccountAndIpAndAllowsANewWindow() {
        MutableClock clock=new MutableClock();var limiter=new AttemptLimiter(clock,2,3,2,2);
        limiter.login("ip","one");limiter.login("ip","one");assertEquals(429,assertThrows(ApiException.class,()->limiter.login("other-ip","one")).status());
        limiter.login("ip","two");assertThrows(ApiException.class,()->limiter.login("ip","three"));
        clock.instant=clock.instant.plusSeconds(60);assertDoesNotThrow(()->limiter.login("ip","one"));
    }
    @Test void rejectsExcessiveParallelPasswordHashWork() throws Exception {
        var limiter=new AttemptLimiter(Clock.systemUTC(),6,30,6,6);CountDownLatch entered=new CountDownLatch(2),release=new CountDownLatch(1);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            Callable<String> work=()->limiter.hash(()->{entered.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}return "ok";});
            var one=executor.submit(work);var two=executor.submit(work);assertTrue(entered.await(5,TimeUnit.SECONDS));
            assertEquals(429,assertThrows(ApiException.class,()->limiter.hash(()->"denied")).status());release.countDown();
            assertEquals("ok",one.get());assertEquals("ok",two.get());assertEquals("allowed",limiter.hash(()->"allowed"));
        } finally {release.countDown();}
    }
    @Test void limitsRegistrationPerIpAndSharesTheOverallBudget() {
        MutableClock clock = new MutableClock();
        var limiter = new AttemptLimiter(clock, 6, 3, 6, 2);
        limiter.login("first-ip", "person");
        limiter.register("first-ip");
        limiter.register("first-ip");
        assertEquals(429, assertThrows(ApiException.class, () -> limiter.register("first-ip")).status());
        limiter.register("other-ip");
        limiter.register("other-ip");
        assertEquals(429, assertThrows(ApiException.class, () -> limiter.register("other-ip")).status());
        clock.instant = clock.instant.plusSeconds(60);
        assertDoesNotThrow(() -> limiter.register("first-ip"));
    }
    private static final class MutableClock extends Clock {
        Instant instant=Instant.parse("2026-11-07T00:00:00Z");public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return instant;}
    }
}
