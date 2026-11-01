package com.operonix.global;

import com.operonix.global.dto.CostingDtos;
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

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Costing API: vendors, time logs, expenses and job profitability.
 *
 * <p>Reads are open to every signed-in role because a technician needs to see the job they are on.
 * Every monetary write is restricted to manager and above.
 */
@RestController
@RequestMapping("/api/v1")
public class CostingController {

    private final CostingService costing;

    public CostingController(CostingService costing) {
        this.costing = costing;
    }

    // ------------------------------------------------------------------ vendors

    @GetMapping("/vendors")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','MANAGER')")
    public List<CostingDtos.VendorResponse> vendors(@RequestParam(defaultValue = "false") boolean activeOnly) {
        return costing.listVendors(activeOnly);
    }

    @PostMapping("/vendors")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','MANAGER')")
    public CostingDtos.VendorResponse createVendor(@Valid @RequestBody CostingDtos.VendorRequest request) {
        return costing.createVendor(request);
    }

    @PutMapping("/vendors/{vendorId}")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','MANAGER')")
    public CostingDtos.VendorResponse updateVendor(@PathVariable UUID vendorId,
                                                   @Valid @RequestBody CostingDtos.VendorRequest request) {
        return costing.updateVendor(vendorId, request);
    }

    // ------------------------------------------------------------------ job costing

    @GetMapping("/jobs/{jobId}/costing")
    public CostingDtos.JobCostingView costing(@PathVariable UUID jobId) {
        return costing.view(jobId);
    }

    @GetMapping("/jobs/{jobId}/profitability")
    public CostingDtos.ProfitabilityResponse profitability(@PathVariable UUID jobId) {
        return costing.profitability(jobId);
    }

    @PostMapping("/jobs/{jobId}/costs/recompute")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','MANAGER')")
    public CostingDtos.JobCostResponse recompute(@PathVariable UUID jobId) {
        return costing.recompute(jobId);
    }

    // ------------------------------------------------------------------ work logs

    @GetMapping("/jobs/{jobId}/work-logs")
    public List<CostingDtos.WorkLogResponse> workLogs(@PathVariable UUID jobId) {
        return costing.view(jobId).workLogs();
    }

    @PostMapping("/jobs/{jobId}/work-logs")
    @ResponseStatus(HttpStatus.CREATED)
    public CostingDtos.WorkLogResponse addWorkLog(@PathVariable UUID jobId,
                                                  @Valid @RequestBody CostingDtos.WorkLogRequest request) {
        return costing.addWorkLog(jobId, request);
    }

    public record StopRequest(Instant endAt) {
    }

    @PostMapping("/work-logs/{workLogId}/stop")
    public CostingDtos.WorkLogResponse stopWorkLog(@PathVariable UUID workLogId,
                                                   @RequestBody(required = false) StopRequest request) {
        return costing.stopWorkLog(workLogId, request == null ? null : request.endAt());
    }

    @DeleteMapping("/work-logs/{workLogId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteWorkLog(@PathVariable UUID workLogId) {
        costing.deleteWorkLog(workLogId);
    }

    // ------------------------------------------------------------------ expenses

    @GetMapping("/jobs/{jobId}/expenses")
    public List<CostingDtos.ExpenseResponse> expenses(@PathVariable UUID jobId) {
        return costing.listExpenses(jobId);
    }

    @PostMapping("/jobs/{jobId}/expenses")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','MANAGER')")
    public CostingDtos.ExpenseResponse addExpense(@PathVariable UUID jobId,
                                                  @Valid @RequestBody CostingDtos.ExpenseRequest request) {
        return costing.addExpense(jobId, request);
    }

    /** Business overhead: an expense with no job attached. */
    @PostMapping("/expenses")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','MANAGER')")
    public CostingDtos.ExpenseResponse addOverhead(@Valid @RequestBody CostingDtos.ExpenseRequest request) {
        return costing.addExpense(null, request);
    }

    @DeleteMapping("/expenses/{expenseId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','MANAGER')")
    public void deleteExpense(@PathVariable UUID expenseId) {
        costing.deleteExpense(expenseId);
    }
}
