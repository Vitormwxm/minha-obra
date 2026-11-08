package br.com.minhaobra.controller;

import br.com.minhaobra.dto.Finance;
import br.com.minhaobra.service.FinanceService;
import br.com.minhaobra.service.SessionContextService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/finance")
public class FinanceController {
    private final FinanceService finance;
    private final SessionContextService sessions;

    public FinanceController(FinanceService finance, SessionContextService sessions) {
        this.finance = finance;
        this.sessions = sessions;
    }

    @GetMapping
    public ResponseEntity<Finance.Ledger> ledger(Authentication auth) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(finance.ledger(sessions.principal(auth)));
    }

    @PutMapping("/financing")
    public Finance.Revision saveFinancing(Authentication auth, @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody Finance.FinancingInput input) {
        return finance.saveFinancing(sessions.principal(auth), key, input);
    }

    @PutMapping("/months/{month}")
    public Finance.Revision saveMonth(Authentication auth, @PathVariable String month,
            @RequestHeader("Idempotency-Key") UUID key, @Valid @RequestBody Finance.MonthInput input) {
        return finance.saveMonth(sessions.principal(auth), month, key, input);
    }

    @DeleteMapping("/months/{month}")
    public ResponseEntity<Void> deleteMonth(Authentication auth, @PathVariable String month,
            @RequestHeader("Idempotency-Key") UUID key, @Valid @RequestBody Finance.DeleteMonth input) {
        finance.deleteMonth(sessions.principal(auth), month, key, input);
        return ResponseEntity.noContent().build();
    }
}
