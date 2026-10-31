package com.operonix.quoting;

import com.operonix.common.api.PageResponse;
import com.operonix.quoting.dto.QuoteDtos;
import com.operonix.scheduling.JobService;
import com.operonix.scheduling.dto.JobDtos;
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
@RequestMapping("/api/v1/quotes")
@PreAuthorize("hasAnyRole('OWNER','ADMIN','MANAGER')")
public class QuoteController {

    private final QuoteService quoteService;
    private final JobService jobService;

    public QuoteController(QuoteService quoteService, JobService jobService) {
        this.quoteService = quoteService;
        this.jobService = jobService;
    }

    @GetMapping
    public PageResponse<QuoteDtos.Response> list(@RequestParam(required = false) String status,
                                                 @RequestParam(required = false) UUID customerId,
                                                 @RequestParam(required = false) String q,
                                                 @RequestParam(defaultValue = "0") int page,
                                                 @RequestParam(defaultValue = "20") int size,
                                                 @RequestParam(required = false) String sort) {
        return quoteService.list(status, customerId, q, page, size, sort);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public QuoteDtos.Response create(@Valid @RequestBody QuoteDtos.CreateRequest request) {
        return quoteService.create(request);
    }

    @GetMapping("/{quoteId}")
    public QuoteDtos.Response get(@PathVariable UUID quoteId) {
        return quoteService.get(quoteId);
    }

    @PutMapping("/{quoteId}")
    public QuoteDtos.Response update(@PathVariable UUID quoteId,
                                     @Valid @RequestBody QuoteDtos.UpdateRequest request) {
        return quoteService.update(quoteId, request);
    }

    @DeleteMapping("/{quoteId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('OWNER','ADMIN')")
    public void delete(@PathVariable UUID quoteId) {
        quoteService.delete(quoteId);
    }

    @PostMapping("/{quoteId}/send")
    public QuoteDtos.Response send(@PathVariable UUID quoteId) {
        return quoteService.send(quoteId);
    }

    @PostMapping("/{quoteId}/accept")
    public QuoteDtos.Response accept(@PathVariable UUID quoteId) {
        return quoteService.accept(quoteId);
    }

    @PostMapping("/{quoteId}/decline")
    public QuoteDtos.Response decline(@PathVariable UUID quoteId) {
        return quoteService.decline(quoteId);
    }

    @PostMapping("/{quoteId}/convert")
    @ResponseStatus(HttpStatus.CREATED)
    public JobDtos.Response convert(@PathVariable UUID quoteId,
                                    @Valid @RequestBody QuoteDtos.ConvertRequest request) {
        return jobService.convertQuote(quoteId, request);
    }
}
