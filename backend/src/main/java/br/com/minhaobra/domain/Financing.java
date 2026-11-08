package br.com.minhaobra.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;

public record Financing(UUID id, long version, long financedCents, long releasedCents,
        BigDecimal annualNominalRate, LocalDate expectedDeliveryDate, YearMonth trackingStartMonth,
        Long subsidyCents) {
}
