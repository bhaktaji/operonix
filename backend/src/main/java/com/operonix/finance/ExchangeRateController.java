package com.operonix.finance;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/exchange-rates")
public class ExchangeRateController {

    private final ExchangeRateService rates;

    public ExchangeRateController(ExchangeRateService rates) {
        this.rates = rates;
    }

    @GetMapping
    public List<FinanceDtos.ExchangeRateResponse> list() {
        return rates.list();
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('OWNER','ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    public FinanceDtos.ExchangeRateResponse create(
            @Valid @RequestBody FinanceDtos.ExchangeRateRequest request) {
        return rates.create(request);
    }
}
