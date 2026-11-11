package br.com.minhaobra.service;

import br.com.minhaobra.repository.NotificationDeliveryRepository;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.ContainerPostProcessor;
import org.springframework.kafka.listener.AbstractMessageListenerContainer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.stereotype.Component;
import org.springframework.util.backoff.FixedBackOff;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(name = {"app.push.enabled", "app.events.enabled"}, havingValue = "true")
public class NotificationRegistrationListener {
    private static final Logger LOG = LoggerFactory.getLogger(NotificationRegistrationListener.class);
    private final NotificationDeliveryRepository deliveries;
    private final ObjectMapper json;
    private final Clock clock;

    public NotificationRegistrationListener(NotificationDeliveryRepository deliveries, ObjectMapper json, Clock clock) {
        this.deliveries = deliveries;
        this.json = json;
        this.clock = clock;
    }

    @Bean
    static ContainerPostProcessor<String, String, AbstractMessageListenerContainer<String, String>> registrationNoticeRetry() {
        return container -> {
            var errors = new DefaultErrorHandler((record, failure) ->
                    LOG.warn("Falha ao processar aviso de cadastro: {}", failure.getClass().getSimpleName()),
                    new FixedBackOff(5000L, FixedBackOff.UNLIMITED_ATTEMPTS));
            errors.setClassifications(Map.of(
                    TransientDataAccessException.class, true,
                    DataAccessResourceFailureException.class, true,
                    RecoverableDataAccessException.class, true), false);
            container.setCommonErrorHandler(errors);
        };
    }

    @KafkaListener(topics = AccountEventPublisher.TOPIC, groupId = "minhaobra-registration-notices-v1",
            containerPostProcessor = "registrationNoticeRetry")
    public void receive(String payload) {
        UUID eventId;
        UUID accountId;
        try {
            var event = json.readTree(payload);
            if (!"ACCOUNT_REQUESTED".equals(event.path("type").asString())) {
                return;
            }
            eventId = UUID.fromString(event.path("id").asString());
            accountId = UUID.fromString(event.path("accountId").asString());
        } catch (JacksonException | IllegalArgumentException invalid) {
            return;
        }
        deliveries.queueRegistration(eventId, accountId, clock.instant());
    }
}
