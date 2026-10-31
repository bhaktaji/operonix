package com.operonix.document;

import com.operonix.audit.AuditAction;
import com.operonix.audit.AuditService;
import com.operonix.common.error.ApiException;
import com.operonix.finance.FinanceDtos;
import com.operonix.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Allocates human-readable document numbers (LLD §5.2, §17.6).
 *
 * <p>The format is data ({@code document_number_rule}), not a hardcoded string. The default rule is
 * exactly what v1 produced — {@code INV-2026-0001} — so tenants that never touch this keep their
 * numbers, which the migration backfill guarantees.
 */
@Service
public class DocumentNumberService {

    private final DocumentSequenceRepository sequences;
    private final DocumentNumberRuleRepository rules;
    private final AuditService audit;
    private final CurrentUser currentUser;

    public DocumentNumberService(DocumentSequenceRepository sequences, DocumentNumberRuleRepository rules,
                                 AuditService audit, CurrentUser currentUser) {
        this.sequences = sequences;
        this.rules = rules;
        this.audit = audit;
        this.currentUser = currentUser;
    }

    /**
     * Allocates the next human-readable document number for a tenant/year. Must run inside the
     * caller's transaction so a rolled-back insert does not burn/emit a number for missing data.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public String next(UUID tenantId, DocumentType type, int year) {
        return next(tenantId, type, year, null);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public String next(UUID tenantId, DocumentType type, int year, UUID legalEntityId) {
        DocumentNumberRule rule = ruleFor(tenantId, type, legalEntityId);
        // A rule that never resets stores its counter under year 0, so the number keeps climbing
        // across years while still using this table's (tenant, type, year) primary key.
        int sequenceYear = rule.getResetPolicy().equals("NEVER") ? 0 : year;
        sequences.ensureRow(tenantId, type.name(), sequenceYear);
        DocumentSequence sequence = sequences.lockRow(tenantId, type, sequenceYear)
                .orElseThrow(() -> new IllegalStateException("Document sequence vanished during allocation"));
        int value = sequence.getNextValue();
        sequence.setNextValue(value + 1);
        return format(rule, year, value);
    }

    /** The current format for each document type, plus a sample of what the next number looks like. */
    @Transactional(readOnly = true)
    public List<FinanceDtos.NumberRuleResponse> rules() {
        UUID tenantId = currentUser.tenantId();
        int year = LocalDate.now().getYear();
        List<FinanceDtos.NumberRuleResponse> responses = new ArrayList<>();
        for (DocumentType type : DocumentType.values()) {
            DocumentNumberRule rule = ruleFor(tenantId, type, null);
            responses.add(new FinanceDtos.NumberRuleResponse(type.name(), rule.getPrefix(),
                    rule.isIncludeYear(), rule.getPadding(), rule.getResetPolicy(),
                    format(rule, year, 1)));
        }
        return responses;
    }

    @Transactional
    public FinanceDtos.NumberRuleResponse updateRule(DocumentType type, FinanceDtos.NumberRuleRequest request) {
        UUID tenantId = currentUser.tenantId();
        DocumentNumberRule rule = rules.findFirstByTenantIdAndDocTypeAndLegalEntityIdIsNull(tenantId, type)
                .orElseGet(() -> {
                    DocumentNumberRule created = defaultRule(tenantId, type);
                    return rules.save(created);
                });
        rule.setPrefix(request.prefix().trim().toUpperCase(java.util.Locale.ROOT));
        if (request.includeYear() != null) {
            rule.setIncludeYear(request.includeYear());
        }
        if (request.padding() != null) {
            rule.setPadding(request.padding().shortValue());
        }
        if (request.resetPolicy() != null) {
            rule.setResetPolicy(request.resetPolicy());
        }
        rules.save(rule);
        audit.record(AuditAction.WORKSPACE_UPDATED, "document_number_rule", rule.getId(),
                Map.of("docType", type.name(), "prefix", rule.getPrefix(),
                        "section", "numbering"));
        return new FinanceDtos.NumberRuleResponse(type.name(), rule.getPrefix(), rule.isIncludeYear(),
                rule.getPadding(), rule.getResetPolicy(),
                format(rule, LocalDate.now().getYear(), 1));
    }

    /** Rule for this tenant (and optional entity), falling back to the v1-compatible default. */
    DocumentNumberRule ruleFor(UUID tenantId, DocumentType type, UUID legalEntityId) {
        if (legalEntityId != null) {
            var entityRule = rules.findFirstByTenantIdAndDocTypeAndLegalEntityId(tenantId, type, legalEntityId);
            if (entityRule.isPresent()) {
                return entityRule.get();
            }
        }
        return rules.findFirstByTenantIdAndDocTypeAndLegalEntityIdIsNull(tenantId, type)
                .orElseGet(() -> defaultRule(tenantId, type));
    }

    private DocumentNumberRule defaultRule(UUID tenantId, DocumentType type) {
        DocumentNumberRule rule = new DocumentNumberRule();
        rule.setTenantId(tenantId);
        rule.setDocType(type);
        rule.setPrefix(type.prefix());
        rule.setIncludeYear(true);
        rule.setPadding((short) 4);
        rule.setResetPolicy("YEARLY");
        return rule;
    }

    private String format(DocumentNumberRule rule, int year, int value) {
        String number = String.valueOf(value);
        StringBuilder builder = new StringBuilder(rule.getPrefix());
        if (rule.isIncludeYear()) {
            builder.append('-').append(year);
        }
        builder.append('-');
        for (int i = number.length(); i < rule.getPadding(); i++) {
            builder.append('0');
        }
        return builder.append(number).toString();
    }

    /** Guard for callers that build a rule request directly (keeps validation in one place). */
    static void requirePrefix(String prefix) {
        if (prefix == null || !prefix.matches("^[A-Za-z0-9/_-]{1,12}$")) {
            throw ApiException.validation("prefix", "letters, digits, / _ and - only");
        }
    }
}
