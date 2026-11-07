package br.com.minhaobra.security;

import br.com.minhaobra.service.ApiException;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Single-node limiter: bounded map, fixed short windows, no unbounded per-client allocations. */
@Component
public final class AttemptLimiter {
    private final Clock clock;
    private final int loginLimit,ipLimit,mfaLimit;
    private final Map<String,Bucket> buckets=new HashMap<>();
    private final Semaphore hashes=new Semaphore(2);
    public AttemptLimiter(Clock clock,@Value("${app.auth.login-attempts-per-minute:6}") int loginLimit,
            @Value("${app.auth.ip-attempts-per-minute:30}") int ipLimit,
            @Value("${app.auth.mfa-attempts-per-minute:6}") int mfaLimit) {
        this.clock=clock;this.loginLimit=loginLimit;this.ipLimit=ipLimit;this.mfaLimit=mfaLimit;
    }
    public void login(String ip,String username) { take("ip:"+ip,ipLimit);take("login:"+username,loginLimit); }
    public void mfa(String ip,String account) { take("ip:"+ip,ipLimit);take("mfa:"+account,mfaLimit); }
    private synchronized void take(String key,int limit) {
        long window=clock.millis()/60_000;
        buckets.entrySet().removeIf(e->e.getValue().window<window);
        Bucket bucket=buckets.get(key);
        if(bucket==null) {
            if(buckets.size()>=10_000) throw limited();
            bucket=new Bucket(window);buckets.put(key,bucket);
        }
        if(++bucket.attempts>limit) throw limited();
    }
    public <T>T hash(Supplier<T> work) {
        if(!hashes.tryAcquire()) throw limited();
        try{return work.get();}finally{hashes.release();}
    }
    private static ApiException limited() { return new ApiException(429,"TOO_MANY_ATTEMPTS","Muitas tentativas. Aguarde um minuto e tente novamente."); }
    private static final class Bucket { final long window;int attempts;Bucket(long window){this.window=window;} }
}
