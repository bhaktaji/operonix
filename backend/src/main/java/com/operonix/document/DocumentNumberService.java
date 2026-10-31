package com.operonix.document;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class DocumentNumberService {

    private final DocumentSequenceRepository repository;

    public DocumentNumberService(DocumentSequenceRepository repository) {
        this.repository = repository;
    }

    /**
     * Allocates the next human-readable document number for a tenant/year. Must run inside the
     * caller's transaction so a rolled-back insert does not burn/emit a number for missing data.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public String next(UUID tenantId, DocumentType type, int year) {
        repository.ensureRow(tenantId, type.name(), year);
        DocumentSequence sequence = repository.lockRow(tenantId, type, year)
                .orElseThrow(() -> new IllegalStateException("Document sequence vanished during allocation"));
        int value = sequence.getNextValue();
        sequence.setNextValue(value + 1);
        return "%s-%d-%04d".formatted(type.prefix(), year, value);
    }
}
