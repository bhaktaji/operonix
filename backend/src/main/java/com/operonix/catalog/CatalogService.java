package com.operonix.catalog;

import com.operonix.audit.AuditAction;
import com.operonix.audit.AuditService;
import com.operonix.catalog.dto.ServiceItemDtos;
import com.operonix.common.api.PageRequests;
import com.operonix.common.error.ApiException;
import com.operonix.common.error.ErrorCode;
import com.operonix.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class CatalogService {

    private final ServiceItemRepository items;
    private final CurrentUser currentUser;
    private final AuditService audit;
    private final com.operonix.finance.TaxService tax;

    public CatalogService(ServiceItemRepository items, CurrentUser currentUser, AuditService audit,
                          com.operonix.finance.TaxService tax) {
        this.items = items;
        this.currentUser = currentUser;
        this.audit = audit;
        this.tax = tax;
    }

    @Transactional(readOnly = true)
    public List<ServiceItemDtos.Response> list(String q, Boolean active) {
        return items.search(currentUser.tenantId(), active, PageRequests.containsPattern(q)).stream()
                .map(ServiceItemDtos.Response::from)
                .toList();
    }

    @Transactional
    public ServiceItemDtos.Response create(ServiceItemDtos.Request request) {
        UUID tenantId = currentUser.tenantId();
        String name = request.name().trim();
        if (items.existsByTenantIdAndNameIgnoreCase(tenantId, name)) {
            throw ApiException.conflict(ErrorCode.CONFLICT, "A service with this name already exists");
        }
        ServiceItem item = new ServiceItem();
        item.setTenantId(tenantId);
        apply(item, request, name);
        items.save(item);
        audit.record(AuditAction.SERVICE_CREATED, "service_item", item.getId(), Map.of("name", name));
        items.flush();
        return ServiceItemDtos.Response.from(item);
    }

    @Transactional
    public ServiceItemDtos.Response update(UUID serviceId, ServiceItemDtos.Request request) {
        UUID tenantId = currentUser.tenantId();
        ServiceItem item = items.findByIdAndTenantId(serviceId, tenantId)
                .orElseThrow(() -> ApiException.notFound("Service"));
        String name = request.name().trim();
        if (items.existsByTenantIdAndNameIgnoreCaseAndIdNot(tenantId, name, serviceId)) {
            throw ApiException.conflict(ErrorCode.CONFLICT, "A service with this name already exists");
        }
        apply(item, request, name);
        audit.record(AuditAction.SERVICE_UPDATED, "service_item", item.getId(),
                Map.of("name", name, "active", item.isActive()));
        items.flush();
        return ServiceItemDtos.Response.from(item);
    }

    @Transactional(readOnly = true)
    public ServiceItem require(UUID tenantId, UUID serviceId) {
        return items.findByIdAndTenantId(serviceId, tenantId)
                .orElseThrow(() -> ApiException.validation("serviceItemId", "service not found"));
    }

    private void apply(ServiceItem item, ServiceItemDtos.Request request, String name) {
        item.setName(name);
        item.setDescription(request.description() == null || request.description().isBlank()
                ? null : request.description().trim());
        item.setUnit(request.unit());
        item.setBasePrice(request.basePrice().max(BigDecimal.ZERO));
        item.setDurationMinutes(request.durationMinutes());
        item.setTaxRate(request.taxRate());
        if (request.taxProfileId() != null) {
            // Rejects a profile from another workspace before it can be attached.
            tax.require(currentUser.tenantId(), request.taxProfileId());
        }
        item.setTaxProfileId(request.taxProfileId());
        item.setActive(request.active());
    }
}
