package com.operonix.customer;

import com.operonix.customer.dto.CustomerDtos;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/locations")
@PreAuthorize("hasAnyRole('OWNER','ADMIN','MANAGER')")
public class LocationController {

    private final CustomerService customerService;

    public LocationController(CustomerService customerService) {
        this.customerService = customerService;
    }

    @PutMapping("/{locationId}")
    public CustomerDtos.LocationResponse update(@PathVariable UUID locationId,
                                                @Valid @RequestBody CustomerDtos.LocationRequest request) {
        return customerService.updateLocation(locationId, request);
    }

    @DeleteMapping("/{locationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID locationId) {
        customerService.deleteLocation(locationId);
    }
}
