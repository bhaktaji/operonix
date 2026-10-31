package com.operonix.document;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DocumentNumberRuleRepository extends JpaRepository<DocumentNumberRule, UUID> {

    List<DocumentNumberRule> findAllByTenantId(UUID tenantId);

    Optional<DocumentNumberRule> findFirstByTenantIdAndDocTypeAndLegalEntityIdIsNull(UUID tenantId,
                                                                                    DocumentType docType);

    Optional<DocumentNumberRule> findFirstByTenantIdAndDocTypeAndLegalEntityId(UUID tenantId,
                                                                              DocumentType docType,
                                                                              UUID legalEntityId);
}
