package com.operonix.document;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Per-tenant numbering format (ERD §3.19). The default rule reproduces the format that shipped in v1
 * ({@code INV-2026-0001}), so adding this table changed no existing number.
 */
@Entity
@Table(name = "document_number_rule")
@Getter
@Setter
@NoArgsConstructor
public class DocumentNumberRule {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id = UUID.randomUUID();

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "legal_entity_id")
    private UUID legalEntityId;

    @Enumerated(EnumType.STRING)
    @Column(name = "doc_type", nullable = false, length = 10)
    private DocumentType docType;

    @Column(name = "prefix", nullable = false, length = 12)
    private String prefix;

    @Column(name = "include_year", nullable = false)
    private boolean includeYear = true;

    @Column(name = "padding", nullable = false)
    private short padding = 4;

    @Column(name = "reset_policy", nullable = false, length = 10)
    private String resetPolicy = "YEARLY";

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
