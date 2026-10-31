package com.operonix.finance;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ExchangeRateRepository extends JpaRepository<ExchangeRate, UUID> {

    Optional<ExchangeRate> findTopByBaseAndQuoteAndAsOfLessThanEqualOrderByAsOfDesc(
            String base, String quote, LocalDate asOf);

    List<ExchangeRate> findAllByOrderByAsOfDescBaseAscQuoteAsc();

    boolean existsByBaseAndQuoteAndAsOf(String base, String quote, LocalDate asOf);
}
