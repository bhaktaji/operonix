package com.operonix.global;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CurrencyRepository extends JpaRepository<Currency, String> {

    List<Currency> findAllByActiveTrueOrderByCodeAsc();

    Optional<Currency> findByCodeAndActiveTrue(String code);
}