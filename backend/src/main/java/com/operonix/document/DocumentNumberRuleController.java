package com.operonix.document;

import com.operonix.finance.FinanceDtos;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;

/** Per-workspace document numbering (LLD §17.6). */
@RestController
@RequestMapping("/api/v1/number-rules")
public class DocumentNumberRuleController {

    private final DocumentNumberService numbers;

    public DocumentNumberRuleController(DocumentNumberService numbers) {
        this.numbers = numbers;
    }

    @GetMapping
    public List<FinanceDtos.NumberRuleResponse> list() {
        return numbers.rules();
    }

    @PutMapping("/{docType}")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN')")
    public FinanceDtos.NumberRuleResponse update(@PathVariable String docType,
                                                 @Valid @RequestBody FinanceDtos.NumberRuleRequest request) {
        DocumentType type = parse(docType);
        return numbers.updateRule(type, request);
    }

    private DocumentType parse(String value) {
        try {
            return DocumentType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw com.operonix.common.error.ApiException.validation("docType",
                    "must be QUOTE, JOB or INVOICE");
        }
    }
}
