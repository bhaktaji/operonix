package com.operonix.finance;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tax")
public class TaxController {

    private final TaxService tax;

    public TaxController(TaxService tax) {
        this.tax = tax;
    }

    /** Platform catalogue: the jurisdictions a workspace can build a profile from. */
    @GetMapping("/definitions")
    public List<FinanceDtos.TaxDefinitionResponse> definitions() {
        return tax.listDefinitions();
    }

    @GetMapping("/profiles")
    public List<FinanceDtos.TaxProfileResponse> profiles() {
        return tax.listProfiles();
    }

    @PostMapping("/profiles")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    public FinanceDtos.TaxProfileResponse create(@Valid @RequestBody FinanceDtos.TaxProfileRequest request) {
        return tax.create(request);
    }

    @PutMapping("/profiles/{id}")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN')")
    public FinanceDtos.TaxProfileResponse update(@PathVariable UUID id,
                                                 @Valid @RequestBody FinanceDtos.TaxProfileRequest request) {
        return tax.update(id, request);
    }

    @DeleteMapping("/profiles/{id}")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        tax.delete(id);
    }
}
