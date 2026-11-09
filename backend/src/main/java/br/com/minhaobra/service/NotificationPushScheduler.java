package br.com.minhaobra.service;

import br.com.minhaobra.domain.NotificationPreference;
import br.com.minhaobra.domain.PaymentMonth;
import br.com.minhaobra.domain.PaymentReminder;
import br.com.minhaobra.repository.NotificationDeliveryRepository;
import br.com.minhaobra.repository.NotificationDeliveryRepository.Job;
import br.com.minhaobra.repository.NotificationDeliveryRepository.Month;
import br.com.minhaobra.repository.NotificationDeliveryRepository.State;
import br.com.minhaobra.repository.NotificationDeliveryRepository.Target;
import br.com.minhaobra.repository.NotificationPreferenceRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "app.push.enabled", havingValue = "true")
public class NotificationPushScheduler {
    private static final Duration RELEVANCE = Duration.ofHours(24);
    private static final int TARGETS_PER_PASS = 100;
    private static final int DELIVERIES_PER_PASS = 8;
    private final NotificationDeliveryRepository deliveries;
    private final NotificationPreferenceRepository preferences;
    private final FirebasePushClient push;
    private final Clock clock;
    private UUID cursor;

    public NotificationPushScheduler(NotificationDeliveryRepository deliveries,
            NotificationPreferenceRepository preferences, FirebasePushClient push, Clock clock) {
        this.deliveries = deliveries;
        this.preferences = preferences;
        this.push = push;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.push.plan-delay-ms:60000}")
    public void plan() {
        Instant now = clock.instant();
        var targets = deliveries.paymentTargets(cursor, TARGETS_PER_PASS);
        for (Target target : targets) {
            preferences.byOwner(target.recipientId()).filter(NotificationPreference::enabled)
                    .ifPresent(preference -> planTarget(target, preference, now));
        }
        cursor = targets.size() < TARGETS_PER_PASS ? null : targets.getLast().installationId();
    }

    private void planTarget(Target target, NotificationPreference preference, Instant now) {
        ZoneId zone = ZoneId.of(target.zoneId());
        LocalDate today = now.atZone(zone).toLocalDate();
        YearMonth latest = YearMonth.from(today.plusDays(30));
        YearMonth since = YearMonth.from(target.enabledSince().atZone(zone));
        YearMonth firstRecent = YearMonth.from(today).minusMonths(1);
        YearMonth earliest = preference.weekly() && since.isBefore(firstRecent) ? since : firstRecent;
        LocalDate firstDate = earliest.atDay(1).isBefore(target.trackingStart())
                ? target.trackingStart() : earliest.atDay(1);
        if (firstDate.isAfter(latest.atDay(1))) {
            return;
        }

        Map<YearMonth, Month> knownMonths = new HashMap<>();
        for (Month month : deliveries.months(target.financingId(), firstDate, latest.atDay(1))) {
            knownMonths.put(YearMonth.from(month.competence()), month);
        }
        for (YearMonth month = firstRecent; !month.isAfter(latest); month = month.plusMonths(1)) {
            knownMonths.putIfAbsent(month, null);
        }
        for (var entry : knownMonths.entrySet()) {
            for (PaymentMonth.Kind kind : PaymentMonth.Kind.values()) {
                LocalDate due = dueDate(target, preference, entry.getKey(), entry.getValue(), kind);
                if (due != null && !due.isBefore(target.enabledSince().atZone(zone).toLocalDate())) {
                    queueDueNotices(target, preference, zone, kind, due, now);
                }
            }
        }
    }

    private void queueDueNotices(Target target, NotificationPreference preference, ZoneId zone,
            PaymentMonth.Kind kind, LocalDate due, Instant now) {
        LocalDate today = now.atZone(zone).toLocalDate();
        for (LocalDate notice = now.minus(RELEVANCE).atZone(zone).toLocalDate(); !notice.isAfter(today);
                notice = notice.plusDays(1)) {
            Instant scheduled = PaymentReminder.scheduledAt(preference, due, notice, zone);
            if (scheduled != null && !scheduled.isBefore(target.enabledSince()) && !scheduled.isAfter(now)
                    && now.isBefore(scheduled.plus(RELEVANCE))) {
                deliveries.queuePayment(target, kind, due, notice, scheduled, scheduled.plus(RELEVANCE));
            }
        }
    }

    private static LocalDate dueDate(Target target, NotificationPreference preference, YearMonth month,
            Month actual, PaymentMonth.Kind kind) {
        if (month.atDay(1).isBefore(target.trackingStart())
                || (actual == null && month.isAfter(YearMonth.from(target.expectedDelivery())))) {
            return null;
        }
        Integer recurring = kind == PaymentMonth.Kind.CAIXA ? preference.caixaDay() : preference.builderDay();
        return PaymentReminder.dueDate(month, actual == null ? null : actual.due(kind), recurring,
                actual == null ? PaymentMonth.Status.OPEN : actual.status(kind));
    }

    @Scheduled(fixedDelayString = "${app.push.delivery-delay-ms:10000}")
    public void deliver() {
        for (int i = 0; i < DELIVERIES_PER_PASS; i++) {
            Instant now = clock.instant();
            var job = deliveries.claim(now, now.plusSeconds(120));
            if (job.isEmpty()) {
                return;
            }
            deliver(job.get());
        }
    }

    private void deliver(Job job) {
        boolean registration = job.registrationId() != null;
        if ((!registration && !paymentStillDue(job)) || !clock.instant().isBefore(job.expiresAt())) {
            deliveries.finish(job, State.CANCELED);
            return;
        }
        var token = deliveries.token(job);
        if (token.isEmpty()) {
            deliveries.finish(job, State.CANCELED);
            return;
        }

        String creditor = job.kind() == PaymentMonth.Kind.CAIXA ? "CAIXA" : "construtora";
        String title = registration ? "Novo cadastro" : "Lembrete de pagamento";
        String body = registration ? "Há uma nova solicitação aguardando análise."
                : "Confira o pagamento da " + creditor + " com vencimento em "
                        + job.dueOn().format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")) + ".";
        switch (push.send(token.get(), job.id().toString(), job.recipientId().toString(), title, body)) {
            case SENT -> deliveries.finish(job, State.SENT);
            case INVALID_TOKEN -> {
                deliveries.removeInvalidToken(job, token.get());
                deliveries.finish(job, State.FAILED);
            }
            case PERMANENT_FAILURE -> deliveries.finish(job, State.FAILED);
            case RETRY -> retry(job);
        }
    }

    private void retry(Job job) {
        long backoff = Math.min(3600, 60L << Math.min(job.attempts() - 1, 6));
        Instant next = clock.instant().plusSeconds(backoff);
        if (next.isBefore(job.expiresAt())) {
            deliveries.retry(job, next);
        } else {
            deliveries.finish(job, State.FAILED);
        }
    }

    private boolean paymentStillDue(Job job) {
        var target = deliveries.paymentTarget(job);
        var preference = preferences.byOwner(job.recipientId());
        if (target.isEmpty() || preference.isEmpty()) {
            return false;
        }
        Target owner = target.get();
        ZoneId zone = ZoneId.of(owner.zoneId());
        if (job.scheduledAt().isBefore(owner.enabledSince())) {
            return false;
        }
        YearMonth month = YearMonth.from(job.dueOn());
        var actual = deliveries.months(owner.financingId(), month.atDay(1), month.atDay(1));
        LocalDate due = dueDate(owner, preference.get(), month, actual.isEmpty() ? null : actual.getFirst(), job.kind());
        return job.dueOn().equals(due)
                && job.scheduledAt().equals(PaymentReminder.scheduledAt(preference.get(), due, job.noticeOn(), zone));
    }

    @Scheduled(fixedDelay = 3600000)
    public void cleanExpired() {
        deliveries.expire(clock.instant().minus(Duration.ofDays(14)));
    }
}
