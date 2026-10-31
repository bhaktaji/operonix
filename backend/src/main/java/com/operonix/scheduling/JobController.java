package com.operonix.scheduling;

import com.operonix.common.api.PageResponse;
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

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/jobs")
@PreAuthorize("hasAnyRole('OWNER','ADMIN','MANAGER','TECHNICIAN')")
public class JobController {

    private final JobService jobService;

    public JobController(JobService jobService) {
        this.jobService = jobService;
    }

    @GetMapping
    public PageResponse<JobDtos.Response> list(@RequestParam(required = false) LocalDate from,
                                               @RequestParam(required = false) LocalDate to,
                                               @RequestParam(required = false) JobStatus status,
                                               @RequestParam(required = false) UUID technicianId,
                                               @RequestParam(required = false) UUID customerId,
                                               @RequestParam(defaultValue = "false") boolean mine,
                                               @RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "20") int size,
                                               @RequestParam(required = false) String sort) {
        return jobService.list(from, to, status, technicianId, customerId, mine, page, size, sort);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','MANAGER')")
    public JobDtos.Response create(@Valid @RequestBody JobDtos.ScheduleRequest request) {
        return jobService.create(request);
    }

    @PostMapping("/series")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','MANAGER')")
    public List<JobDtos.Response> createSeries(@Valid @RequestBody JobDtos.SeriesRequest request) {
        return jobService.createSeries(request);
    }

    @GetMapping("/{jobId}")
    public JobDtos.Response get(@PathVariable UUID jobId) {
        return jobService.get(jobId);
    }

    @PutMapping("/{jobId}")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','MANAGER')")
    public JobDtos.Response update(@PathVariable UUID jobId,
                                   @Valid @RequestBody JobDtos.ScheduleRequest request) {
        return jobService.update(jobId, request);
    }

    @PostMapping("/{jobId}/assignments")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','MANAGER')")
    public JobDtos.Response assign(@PathVariable UUID jobId,
                                   @Valid @RequestBody JobDtos.AssignmentRequest request) {
        return jobService.assign(jobId, request.userId());
    }

    @DeleteMapping("/{jobId}/assignments/{userId}")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','MANAGER')")
    public JobDtos.Response unassign(@PathVariable UUID jobId, @PathVariable UUID userId) {
        return jobService.unassign(jobId, userId);
    }

    @PostMapping("/{jobId}/transitions")
    public JobDtos.Response transition(@PathVariable UUID jobId,
                                       @Valid @RequestBody JobDtos.TransitionRequest request) {
        return jobService.transition(jobId, request);
    }
}
