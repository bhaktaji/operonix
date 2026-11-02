package com.operonix.customer;

import com.operonix.audit.AuditAction;
import com.operonix.audit.AuditService;
import com.operonix.common.api.PageRequests;
import com.operonix.common.api.PageResponse;
import com.operonix.common.error.ApiException;
import com.operonix.common.error.ErrorCode;
import com.operonix.common.time.WorkspaceZone;
import com.operonix.customer.dto.CustomerDtos;
import com.operonix.security.CurrentUser;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class CustomerService {

    private final CustomerRepository customers;
    private final ServiceLocationRepository locations;
    private final CustomerActivityPort activityPort;
    private final AuditService audit;
    private final CurrentUser currentUser;

    public CustomerService(CustomerRepository customers, ServiceLocationRepository locations,
                           CustomerActivityPort activityPort, AuditService audit, CurrentUser currentUser) {
        this.customers = customers;
        this.locations = locations;
        this.activityPort = activityPort;
        this.audit = audit;
        this.currentUser = currentUser;
    }

    @Transactional(readOnly = true)
    public PageResponse<CustomerDtos.Summary> list(String q, String status, Pageable pageable) {
        UUID tenantId = currentUser.tenantId();
        CustomerStatus parsed = parseStatus(status);
        String pattern = PageRequests.containsPattern(q);
        Specification<Customer> specification = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("tenantId"), tenantId));
            if (parsed != null) {
                predicates.add(cb.equal(root.get("status"), parsed));
            }
            if (pattern != null) {
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("name")), pattern),
                        cb.like(cb.lower(cb.coalesce(root.<String>get("email"), "")), pattern),
                        cb.like(cb.lower(cb.coalesce(root.<String>get("contactName"), "")), pattern)));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        return PageResponse.of(customers.findAll(specification, pageable)).map(CustomerDtos.Summary::from);
    }

    @Transactional
    public CustomerDtos.Response create(CustomerDtos.CreateRequest request) {
        UUID tenantId = currentUser.tenantId();
        Customer customer = new Customer();
        customer.setTenantId(tenantId);
        apply(customer, request.name(), request.contactName(), request.email(), request.phone(),
                request.notes(), request.billingAddress());
        customers.save(customer);

        if (request.primaryLocation() != null) {
            createLocation(customer, request.primaryLocation(), true);
        }
        audit.record(AuditAction.CUSTOMER_CREATED, "customer", customer.getId(),
                Map.of("name", customer.getName()));
        return response(customer);
    }

    @Transactional
    public CustomerDtos.Response update(UUID customerId, CustomerDtos.UpdateRequest request) {
        Customer customer = require(customerId);
        apply(customer, request.name(), request.contactName(), request.email(), request.phone(),
                request.notes(), request.billingAddress());
        audit.record(AuditAction.CUSTOMER_UPDATED, "customer", customer.getId(),
                Map.of("name", customer.getName()));
        return response(customer);
    }

    @Transactional(readOnly = true)
    public CustomerDtos.Response get(UUID customerId) {
        return response(require(customerId));
    }

    @Transactional
    public CustomerDtos.Response archive(UUID customerId) {
        Customer customer = require(customerId);
        if (activityPort.hasLiveJobs(customer.getTenantId(), customerId)) {
            throw ApiException.conflict(ErrorCode.CONFLICT,
                    "This customer still has scheduled or in-progress jobs");
        }
        customer.setStatus(CustomerStatus.ARCHIVED);
        audit.record(AuditAction.CUSTOMER_ARCHIVED, "customer", customer.getId(),
                Map.of("name", customer.getName()));
        return response(customer);
    }

    @Transactional
    public CustomerDtos.Response restore(UUID customerId) {
        Customer customer = require(customerId);
        customer.setStatus(CustomerStatus.ACTIVE);
        audit.record(AuditAction.CUSTOMER_RESTORED, "customer", customer.getId(),
                Map.of("name", customer.getName()));
        return response(customer);
    }

    @Transactional
    public CustomerDtos.LocationResponse addLocation(UUID customerId, CustomerDtos.LocationRequest request) {
        Customer customer = require(customerId);
        long existing = locations.countByTenantIdAndCustomerId(customer.getTenantId(), customerId);
        ServiceLocation location = createLocation(customer, request, existing == 0);
        audit.record(AuditAction.LOCATION_CREATED, "service_location", location.getId(),
                Map.of("customerId", customerId.toString(), "label", location.getLabel()));
        return CustomerDtos.LocationResponse.from(location);
    }

    @Transactional
    public CustomerDtos.LocationResponse updateLocation(UUID locationId, CustomerDtos.LocationRequest request) {
        UUID tenantId = currentUser.tenantId();
        ServiceLocation location = locations.findByIdAndTenantId(locationId, tenantId)
                .orElseThrow(() -> ApiException.notFound("Location"));
        if (request.isPrimary() && !location.isPrimary()) {
            locations.clearPrimary(location.getCustomerId());
        }
        applyLocation(location, request, request.isPrimary() || location.isPrimary());
        audit.record(AuditAction.LOCATION_UPDATED, "service_location", location.getId(),
                Map.of("customerId", location.getCustomerId().toString(), "label", location.getLabel()));
        return CustomerDtos.LocationResponse.from(location);
    }

    @Transactional
    public void deleteLocation(UUID locationId) {
        UUID tenantId = currentUser.tenantId();
        ServiceLocation location = locations.findByIdAndTenantId(locationId, tenantId)
                .orElseThrow(() -> ApiException.notFound("Location"));
        if (activityPort.isLocationInLiveUse(tenantId, location.getCustomerId(), locationId)) {
            throw ApiException.conflict(ErrorCode.CONFLICT,
                    "This location is still used by scheduled or in-progress jobs");
        }
        boolean wasPrimary = location.isPrimary();
        UUID customerId = location.getCustomerId();
        locations.delete(location);
        if (wasPrimary) {
            locations.findAllByTenantIdAndCustomerIdOrderByPrimaryDescLabelAsc(tenantId, customerId).stream()
                    .findFirst()
                    .ifPresent(next -> next.setPrimary(true));
        }
        audit.record(AuditAction.LOCATION_DELETED, "service_location", locationId,
                Map.of("customerId", customerId.toString()));
    }

    @Transactional(readOnly = true)
    public List<CustomerDtos.LocationResponse> locations(UUID customerId) {
        require(customerId);
        return locations
                .findAllByTenantIdAndCustomerIdOrderByPrimaryDescLabelAsc(currentUser.tenantId(), customerId)
                .stream().map(CustomerDtos.LocationResponse::from).toList();
    }

    // ---------------------------------------------------------------- cross-module API

    @Transactional(readOnly = true)
    public Customer require(UUID customerId) {
        return customers.findByIdAndTenantId(customerId, currentUser.tenantId())
                .orElseThrow(() -> ApiException.notFound("Customer"));
    }

    @Transactional(readOnly = true)
    public Customer requireActive(UUID tenantId, UUID customerId) {
        Customer customer = customers.findByIdAndTenantId(customerId, tenantId)
                .orElseThrow(() -> ApiException.notFound("Customer"));
        if (customer.getStatus() != CustomerStatus.ACTIVE) {
            throw ApiException.immutable("This customer is archived and cannot receive new work");
        }
        return customer;
    }

    @Transactional(readOnly = true)
    public ServiceLocation requireLocation(UUID tenantId, UUID customerId, UUID locationId) {
        ServiceLocation location = locations.findByIdAndTenantId(locationId, tenantId)
                .orElseThrow(() -> ApiException.notFound("Location"));
        if (!location.getCustomerId().equals(customerId)) {
            throw ApiException.validation("locationId", "does not belong to the selected customer");
        }
        return location;
    }

    @Transactional(readOnly = true)
    public ServiceLocation defaultLocation(UUID tenantId, UUID customerId) {
        return locations.findAllByTenantIdAndCustomerIdOrderByPrimaryDescLabelAsc(tenantId, customerId).stream()
                .findFirst()
                .orElseThrow(() -> ApiException.validation("locationId",
                        "add a service location for this customer first"));
    }

    @Transactional(readOnly = true)
    public Map<UUID, CustomerDtos.Summary> summaries(UUID tenantId, Collection<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        return customers.findAllByTenantIdAndIdIn(tenantId, ids).stream()
                .collect(Collectors.toMap(Customer::getId, CustomerDtos.Summary::from, (a, b) -> a));
    }

    @Transactional(readOnly = true)
    public Map<UUID, CustomerDtos.LocationResponse> locationSummaries(UUID tenantId, Collection<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        return locations.findAllByTenantIdAndIdIn(tenantId, ids).stream()
                .collect(Collectors.toMap(ServiceLocation::getId, CustomerDtos.LocationResponse::from, (a, b) -> a));
    }

    @Transactional(readOnly = true)
    public CustomerDtos.LocationResponse locationResponse(ServiceLocation location) {
        return CustomerDtos.LocationResponse.from(location);
    }

    // ---------------------------------------------------------------- internals

    private CustomerDtos.Response response(Customer customer) {
        // Flush pending writes so generated createdAt/updatedAt values are present in the response.
        if (!TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            customers.flush();
        }
        List<ServiceLocation> customerLocations = locations
                .findAllByTenantIdAndCustomerIdOrderByPrimaryDescLabelAsc(customer.getTenantId(), customer.getId());
        return CustomerDtos.Response.from(customer, customerLocations);
    }

    private ServiceLocation createLocation(Customer customer, CustomerDtos.LocationRequest request,
                                           boolean forcePrimary) {
        ServiceLocation location = new ServiceLocation();
        location.setTenantId(customer.getTenantId());
        location.setCustomerId(customer.getId());
        boolean primary = request.isPrimary() || forcePrimary;
        if (primary) {
            locations.clearPrimary(customer.getId());
        }
        applyLocation(location, request, primary);
        return locations.save(location);
    }

    private void applyLocation(ServiceLocation location, CustomerDtos.LocationRequest request, boolean primary) {
        location.setLabel(trim(request.label()));
        location.setLine1(trim(request.line1()));
        location.setLine2(trimToNull(request.line2()));
        location.setCity(trim(request.city()));
        location.setRegion(trimToNull(request.region()));
        location.setPostalCode(trim(request.postalCode()));
        location.setCountry(request.country().trim().toUpperCase(Locale.ROOT));
        location.setAccessNotes(trimToNull(request.accessNotes()));
        location.setTimezone(normalizeTimezone(request.timezone()));
        location.setPrimary(primary);
    }

    private void apply(Customer customer, String name, String contactName, String email, String phone,
                       String notes, CustomerDtos.Address address) {
        customer.setName(trim(name));
        customer.setContactName(trimToNull(contactName));
        customer.setEmail(email == null || email.isBlank() ? null : email.trim().toLowerCase(Locale.ROOT));
        customer.setPhone(trimToNull(phone));
        customer.setNotes(trimToNull(notes));
        CustomerDtos.Address cleaned = cleanAddress(address);
        customer.setBillingLine1(cleaned == null ? null : cleaned.line1());
        customer.setBillingLine2(cleaned == null ? null : cleaned.line2());
        customer.setBillingCity(cleaned == null ? null : cleaned.city());
        customer.setBillingRegion(cleaned == null ? null : cleaned.region());
        customer.setBillingPostalCode(cleaned == null ? null : cleaned.postalCode());
        customer.setBillingCountry(cleaned == null ? null : cleaned.country());
    }

    private CustomerDtos.Address cleanAddress(CustomerDtos.Address address) {
        if (address == null) {
            return null;
        }
        if (isBlank(address.line1()) || isBlank(address.city()) || isBlank(address.postalCode())
                || isBlank(address.country())) {
            throw ApiException.validation("billingAddress",
                    "line1, city, postalCode and country are required when a billing address is provided");
        }
        return new CustomerDtos.Address(trim(address.line1()), trimToNull(address.line2()), trim(address.city()),
                trimToNull(address.region()), trim(address.postalCode()),
                address.country().trim().toUpperCase(Locale.ROOT));
    }

    private CustomerStatus parseStatus(String status) {
        if (status == null || status.isBlank() || status.equalsIgnoreCase("ALL")) {
            return null;
        }
        try {
            return CustomerStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw ApiException.validation("status", "must be ACTIVE, ARCHIVED or ALL");
        }
    }

    /**
     * A location time zone must be a real IANA id; blank means "same as the workspace", which is what
     * an existing single-region location gets.
     */
    private String normalizeTimezone(String timezone) {
        if (isBlank(timezone)) {
            return null;
        }
        String value = timezone.trim();
        if (!WorkspaceZone.isValid(value)) {
            throw ApiException.validation("timezone", "must be an IANA time zone such as America/Los_Angeles");
        }
        return value;
    }

    private String trim(String value) {
        return value == null ? null : value.trim();
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
