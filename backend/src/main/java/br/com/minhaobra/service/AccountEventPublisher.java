package br.com.minhaobra.service;

import br.com.minhaobra.repository.AccountOutboxRepository;
import java.time.Clock;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods=false)
@EnableScheduling
@ConditionalOnProperty(name="app.events.enabled",havingValue="true")
public class AccountEventPublisher {
    public static final String TOPIC="minhaobra.account-events.v1";
    private final AccountOutboxRepository repository;private final KafkaTemplate<String,String> kafka;
    private final TransactionTemplate transaction;private final Clock clock;
    public AccountEventPublisher(AccountOutboxRepository repository,KafkaTemplate<String,String> kafka,org.springframework.transaction.PlatformTransactionManager manager,Clock clock) {
        this.repository=repository;this.kafka=kafka;this.transaction=new TransactionTemplate(manager);this.clock=clock;
    }
    @Bean NewTopic accountEventsTopic() {
        return TopicBuilder.name(TOPIC).partitions(1).replicas(1).config("retention.ms","86400000").config("retention.bytes","67108864").config("segment.bytes","16777216").build();
    }
    @Scheduled(fixedDelayString="${app.events.delay-ms:5000}") public void publish() {
        transaction.executeWithoutResult(status->{
            for(var event:repository.pending()) {
                try {
                    kafka.send(TOPIC,event.key().toString(),event.payload()).get(5,TimeUnit.SECONDS);
                    repository.published(event.id());
                } catch(Exception unavailable) {
                    if(unavailable instanceof InterruptedException) Thread.currentThread().interrupt();
                    int attempts=Math.min(30,event.attempts()+1);
                    long delay=Math.min(300L,1L<<Math.min(attempts,8));
                    repository.retry(event.id(),attempts,clock.instant().plusSeconds(delay));
                    if(Thread.currentThread().isInterrupted()) break;
                }
            }
        });
    }
}
