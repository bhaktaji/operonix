package com.operonix.billing;

import com.operonix.billing.dto.InvoiceDtos;
import com.operonix.common.api.PageResponse;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/invoices")
@PreAuthorize("hasAnyRole('OWNER','ADMIN','MANAGER')")
public class InvoiceController {

    private final InvoiceService invoiceService;

    public InvoiceController(InvoiceService invoiceService) {
        this.invoiceService = invoiceService;
    }

    @GetMapping
    public PageResponse<InvoiceDtos.Response> list(@RequestParam(required = false) String status,
                                                   @RequestParam(required = false) UUID customerId,
                                                   @RequestParam(required = false) Boolean overdue,
                                                   @RequestParam(required = false) String q,
                                                   @RequestParam(defaultValue = "0") int page,
                                                   @RequestParam(defaultValue = "20") int size,
                                                   @RequestParam(required = false) String sort) {
        return invoiceService.list(status, customerId, overdue, q, page, size, sort);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public InvoiceDtos.Response create(@Valid @RequestBody InvoiceDtos.CreateRequest request) {
        return invoiceService.create(request);
    }

    @GetMapping("/{invoiceId}")
    public InvoiceDtos.Response get(@PathVariable UUID invoiceId) {
        return invoiceService.get(invoiceId);
    }

    @PutMapping("/{invoiceId}")
    public InvoiceDtos.Response update(@PathVariable UUID invoiceId,
                                       @Valid @RequestBody InvoiceDtos.UpdateRequest request) {
        return invoiceService.update(invoiceId, request);
    }

    @PostMapping("/{invoiceId}/send")
    public InvoiceDtos.Response send(@PathVariable UUID invoiceId) {
        return invoiceService.send(invoiceId);
    }

    @PostMapping("/{invoiceId}/void")
    public InvoiceDtos.Response voidInvoice(@PathVariable UUID invoiceId,
                                            @Valid @RequestBody InvoiceDtos.VoidRequest request) {
        return invoiceService.voidInvoice(invoiceId, request);
    }

    @PostMapping("/{invoiceId}/payments")
    @ResponseStatus(HttpStatus.CREATED)
    public InvoiceDtos.PaymentResponse recordPayment(@PathVariable UUID invoiceId,
                                                     @Valid @RequestBody InvoiceDtos.PaymentRequest request) {
        return invoiceService.addPayment(invoiceId, request);
    }

    @DeleteMapping("/{invoiceId}/payments/{paymentId}")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN')")
    public InvoiceDtos.Response reversePayment(@PathVariable UUID invoiceId, @PathVariable UUID paymentId) {
        return invoiceService.reversePayment(invoiceId, paymentId);
    }
}
