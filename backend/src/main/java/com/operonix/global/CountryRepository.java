package com.operonix.global;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CountryRepository extends JpaRepository<Country, String> {

    List<Country> findAllByActiveTrueOrderByNameAsc();

    Optional<Country> findByCodeAndActiveTrue(String code);
}