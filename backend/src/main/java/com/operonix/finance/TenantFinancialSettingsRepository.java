package com.operonix.finance;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface TenantFinancialSettingsRepository extends JpaRepository<TenantFinancialSettings, UUID> {
}
