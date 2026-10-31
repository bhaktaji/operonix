package com.operonix.billing;

import com.operonix.billing.dto.InvoiceDtos;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Billing-side endpoint for "invoice this job"; kept out of scheduling to avoid a module cycle. */
@RestController
@RequestMapping("/api/v1/jobs")
@PreAuthorize("hasAnyRole('OWNER','ADMIN','MANAGER')")
public class JobInvoiceController {

    private final InvoiceService invoiceService;

    public JobInvoiceController(InvoiceService invoiceService) {
        this.invoiceService = invoiceService;
    }

    @PostMapping("/{jobId}/invoice")
    @ResponseStatus(HttpStatus.CREATED)
    public InvoiceDtos.Response invoiceJob(@PathVariable UUID jobId,
                                           @Valid @RequestBody InvoiceDtos.JobInvoiceRequest request) {
        return invoiceService.createFromJob(jobId, request);
    }
}
