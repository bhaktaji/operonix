package com.operonix.catalog;

import com.operonix.catalog.dto.ServiceItemDtos;
import jakarta.validation.Valid;
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
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/services")
public class CatalogController {

    private final CatalogService catalogService;

    public CatalogController(CatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @GetMapping
    public List<ServiceItemDtos.Response> list(@RequestParam(required = false) String q,
                                               @RequestParam(required = false) Boolean active) {
        return catalogService.list(q, active);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','MANAGER')")
    public ServiceItemDtos.Response create(@Valid @RequestBody ServiceItemDtos.Request request) {
        return catalogService.create(request);
    }

    @PutMapping("/{serviceId}")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','MANAGER')")
    public ServiceItemDtos.Response update(@PathVariable UUID serviceId,
                                          @Valid @RequestBody ServiceItemDtos.Request request) {
        return catalogService.update(serviceId, request);
    }
}
