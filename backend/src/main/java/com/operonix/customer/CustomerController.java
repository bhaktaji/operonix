package com.operonix.customer;

import com.operonix.common.api.PageRequests;
import com.operonix.common.api.PageResponse;
import com.operonix.customer.dto.CustomerDtos;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/customers")
@PreAuthorize("hasAnyRole('OWNER','ADMIN','MANAGER')")
public class CustomerController {

    private static final Map<String, String> SORTS = Map.of(
            "name", "name", "createdAt", "createdAt", "updatedAt", "updatedAt");

    private final CustomerService customerService;

    public CustomerController(CustomerService customerService) {
        this.customerService = customerService;
    }

    @GetMapping
    public PageResponse<CustomerDtos.Summary> list(@RequestParam(required = false) String q,
                                                   @RequestParam(required = false) String status,
                                                   @RequestParam(defaultValue = "0") int page,
                                                   @RequestParam(defaultValue = "20") int size,
                                                   @RequestParam(required = false) String sort) {
        Pageable pageable = PageRequests.of(page, size, sort, SORTS, "name,asc");
        return customerService.list(q, status, pageable);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CustomerDtos.Response create(@Valid @RequestBody CustomerDtos.CreateRequest request) {
        return customerService.create(request);
    }

    @GetMapping("/{customerId}")
    public CustomerDtos.Response get(@PathVariable UUID customerId) {
        return customerService.get(customerId);
    }

    @PutMapping("/{customerId}")
    public CustomerDtos.Response update(@PathVariable UUID customerId,
                                        @Valid @RequestBody CustomerDtos.UpdateRequest request) {
        return customerService.update(customerId, request);
    }

    @PostMapping("/{customerId}/archive")
    public CustomerDtos.Response archive(@PathVariable UUID customerId) {
        return customerService.archive(customerId);
    }

    @PostMapping("/{customerId}/restore")
    public CustomerDtos.Response restore(@PathVariable UUID customerId) {
        return customerService.restore(customerId);
    }

    @GetMapping("/{customerId}/locations")
    public List<CustomerDtos.LocationResponse> locations(@PathVariable UUID customerId) {
        return customerService.locations(customerId);
    }

    @PostMapping("/{customerId}/locations")
    @ResponseStatus(HttpStatus.CREATED)
    public CustomerDtos.LocationResponse addLocation(@PathVariable UUID customerId,
                                                     @Valid @RequestBody CustomerDtos.LocationRequest request) {
        return customerService.addLocation(customerId, request);
    }
}
