package com.operonix.scheduling;

import com.operonix.audit.AuditAction;
import com.operonix.audit.AuditService;
import com.operonix.common.api.PageRequests;
import com.operonix.common.api.PageResponse;
import com.operonix.common.error.ApiException;
import com.operonix.common.error.ErrorCode;
import com.operonix.config.OperonixProperties;
import com.operonix.customer.Customer;
import com.operonix.customer.CustomerService;
import com.operonix.customer.ServiceLocation;
import com.operonix.customer.dto.CustomerDtos;
import com.operonix.document.DocumentNumberService;
import com.operonix.document.DocumentType;
import com.operonix.identity.AppUser;
import com.operonix.identity.AppUserRepository;
import com.operonix.identity.Role;
import com.operonix.quoting.Quote;
import com.operonix.quoting.QuoteService;
import com.operonix.quoting.dto.QuoteDtos;
import com.operonix.scheduling.dto.JobDtos;
import com.operonix.security.CurrentUser;
import com.operonix.tenancy.Tenant;
import com.operonix.tenancy.TenantService;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class JobService {

    private static final Duration MIN_DURATION = Duration.ofMinutes(15);
    private static final Duration MAX_DURATION = Duration.ofHours(24);
    private static final Duration MAX_HORIZON = Duration.ofDays(730);
    private static final Map<String, String> SORTS = Map.of(
            "scheduledStart", "scheduledStart",
            "createdAt", "createdAt",
            "number", "number");

    private final JobRepository jobs;
    private final JobAssignmentRepository assignments;
    private final AppUserRepository users;
    private final CustomerService customers;
    private final QuoteService quotes;
    private final TenantService tenants;
    private final DocumentNumberService numbers;
    private final AuditService audit;
    private final CurrentUser currentUser;
    private final OperonixProperties properties;

    public JobService(JobRepository jobs, JobAssignmentRepository assignments, AppUserRepository users,
                      CustomerService customers, QuoteService quotes, TenantService tenants,
                      DocumentNumberService numbers, AuditService audit, CurrentUser currentUser,
                      OperonixProperties properties) {
        this.jobs = jobs;
        this.assignments = assignments;
        this.users = users;
        this.customers = customers;
        this.quotes = quotes;
        this.tenants = tenants;
        this.numbers = numbers;
        this.audit = audit;
        this.currentUser = currentUser;
        this.properties = properties;
    }

    // ------------------------------------------------------------------------ reads

    @Transactional(readOnly = true)
    public PageResponse<JobDtos.Response> list(LocalDate from, LocalDate to, JobStatus status,
                                               UUID technicianId, UUID customerId, boolean mine,
                                               int page, int size, String sort) {
        UUID tenantId = currentUser.tenantId();
        Tenant tenant = tenants.require(tenantId);
        ZoneId zone = ZoneId.of(tenant.getTimezone());
        if (from != null && to != null && to.isBefore(from)) {
            throw ApiException.validation("to", "must not be before from");
        }
        UUID scopedTechnician = currentUser.role() == Role.TECHNICIAN
                ? currentUser.id()
                : mine ? currentUser.id() : technicianId;

        Instant fromInstant = from == null ? null : from.atStartOfDay(zone).toInstant();
        Instant toInstant = to == null ? null : to.plusDays(1).atStartOfDay(zone).toInstant();
        Pageable pageable = PageRequests.of(page, size, sort, SORTS, "scheduledStart,asc");

        // Built as a Specification so absent filters add no SQL, avoiding PostgreSQL's
        // "could not determine data type of parameter" on `:param is null` predicates.
        Specification<Job> specification = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("tenantId"), tenantId));
            if (fromInstant != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("scheduledStart"), fromInstant));
            }
            if (toInstant != null) {
                predicates.add(cb.lessThan(root.get("scheduledStart"), toInstant));
            }
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (customerId != null) {
                predicates.add(cb.equal(root.get("customerId"), customerId));
            }
            if (scopedTechnician != null) {
                Subquery<UUID> assigned = query.subquery(UUID.class);
                Root<JobAssignment> assignment = assigned.from(JobAssignment.class);
                assigned.select(assignment.get("job").get("id"))
                        .where(cb.equal(assignment.get("job"), root),
                                cb.equal(assignment.get("userId"), scopedTechnician));
                predicates.add(cb.exists(assigned));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        Page<Job> result = jobs.findAll(specification, pageable);
        List<JobDtos.Response> content = toResponses(result.getContent());
        return new PageResponse<>(content, result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages());
    }

    @Transactional(readOnly = true)
    public JobDtos.Response get(UUID jobId) {
        Job job = requireReadable(jobId);
        return toResponses(List.of(job)).get(0);
    }

    // ----------------------------------------------------------------------- writes

    @Transactional
    public JobDtos.Response create(JobDtos.ScheduleRequest request) {
        UUID tenantId = currentUser.tenantId();
        Tenant tenant = tenants.require(tenantId);
        Customer customer = customers.requireActive(tenantId, request.customerId());
        ServiceLocation location = customers.requireLocation(tenantId, customer.getId(), request.locationId());
        validateWindow(request.scheduledStart(), request.scheduledEnd());
        List<AppUser> technicians = requireAssignableUsers(tenantId, request.technicianIds());
        assertNoConflicts(tenantId, ids(technicians), request.scheduledStart(), request.scheduledEnd(), null);

        Job job = buildJob(tenant, customer, location, request.title(), request.scheduledStart(),
                request.scheduledEnd(), request.notes(), null, Recurrence.NONE, null);
        replaceAssignments(job, technicians);
        jobs.save(job);

        audit.record(AuditAction.JOB_CREATED, "Job", job.getId(),
                Map.of("number", job.getNumber(), "customerId", customer.getId().toString(),
                        "scheduledStart", job.getScheduledStart().toString()));
        return toResponses(List.of(job)).get(0);
    }

    @Transactional
    public List<JobDtos.Response> createSeries(JobDtos.SeriesRequest request) {
        UUID tenantId = currentUser.tenantId();
        Tenant tenant = tenants.require(tenantId);
        Recurrence recurrence = parseRecurrence(request.recurrence());
        if (recurrence == Recurrence.NONE) {
            throw ApiException.validation("recurrence", "must be WEEKLY, BIWEEKLY or MONTHLY");
        }
        int occurrences = request.occurrences();
        if (occurrences < 2 || occurrences > properties.maxSeriesOccurrences()) {
            throw ApiException.validation("occurrences",
                    "must be between 2 and " + properties.maxSeriesOccurrences());
        }
        Customer customer = customers.requireActive(tenantId, request.customerId());
        ServiceLocation location = customers.requireLocation(tenantId, customer.getId(), request.locationId());
        validateWindow(request.scheduledStart(), request.scheduledEnd());
        List<AppUser> technicians = requireAssignableUsers(tenantId, request.technicianIds());
        Set<UUID> technicianIds = ids(technicians);

        ZoneId zone = ZoneId.of(tenant.getTimezone());
        ZonedDateTime firstStart = request.scheduledStart().atZone(zone);
        Duration duration = Duration.between(request.scheduledStart(), request.scheduledEnd());

        List<Job> series = new ArrayList<>(occurrences);
        UUID seriesId = null;
        for (int i = 0; i < occurrences; i++) {
            Instant start = shift(firstStart, recurrence, i).toInstant();
            Instant end = start.plus(duration);
            validateWindow(start, end);
            assertNoConflicts(tenantId, technicianIds, start, end, null);
            Job job = buildJob(tenant, customer, location, request.title(), start, end, request.notes(),
                    null, recurrence, seriesId);
            replaceAssignments(job, technicians);
            jobs.save(job);
            if (seriesId == null) {
                seriesId = job.getId();
                job.setSeriesId(seriesId);
            }
            series.add(job);
        }

        audit.record(AuditAction.JOB_CREATED, "Job", seriesId,
                Map.of("recurrence", recurrence.name(), "occurrences", occurrences,
                        "customerId", customer.getId().toString()));
        return toResponses(series);
    }

    @Transactional
    public JobDtos.Response update(UUID jobId, JobDtos.ScheduleRequest request) {
        UUID tenantId = currentUser.tenantId();
        Job job = requireDetailed(tenantId, jobId);
        if (job.getStatus() != JobStatus.SCHEDULED) {
            throw ApiException.immutable("a job in status " + job.getStatus() + " can no longer be rescheduled");
        }
        Customer customer = customers.requireActive(tenantId, request.customerId());
        ServiceLocation location = customers.requireLocation(tenantId, customer.getId(), request.locationId());
        validateWindow(request.scheduledStart(), request.scheduledEnd());
        List<AppUser> technicians = requireAssignableUsers(tenantId, request.technicianIds());
        assertNoConflicts(tenantId, ids(technicians), request.scheduledStart(), request.scheduledEnd(), job.getId());

        job.setCustomerId(customer.getId());
        job.setLocationId(location.getId());
        job.setTitle(defaultTitle(request.title(), customer));
        job.setScheduledStart(request.scheduledStart());
        job.setScheduledEnd(request.scheduledEnd());
        job.setNotes(trimToNull(request.notes()));
        replaceAssignments(job, technicians);

        audit.record(AuditAction.JOB_UPDATED, "Job", job.getId(),
                Map.of("number", job.getNumber(), "scheduledStart", job.getScheduledStart().toString()));
        return toResponses(List.of(job)).get(0);
    }

    @Transactional
    public JobDtos.Response assign(UUID jobId, UUID userId) {
        UUID tenantId = currentUser.tenantId();
        Job job = requireDetailed(tenantId, jobId);
        requireReschedulable(job);
        if (assignments.existsByJobIdAndUserId(jobId, userId)) {
            throw ApiException.conflict(ErrorCode.CONFLICT, "This technician is already assigned to the job");
        }
        AppUser user = requireAssignableUsers(tenantId, List.of(userId)).getFirst();
        assertNoConflicts(tenantId, Set.of(userId), job.getScheduledStart(), job.getScheduledEnd(), job.getId());

        JobAssignment assignment = new JobAssignment();
        assignment.setUserId(user.getId());
        job.addAssignment(assignment);
        audit.record(AuditAction.JOB_ASSIGNED, "Job", jobId,
                Map.of("userId", userId.toString(), "number", job.getNumber()));
        return toResponses(List.of(job)).get(0);
    }

    @Transactional
    public JobDtos.Response unassign(UUID jobId, UUID userId) {
        UUID tenantId = currentUser.tenantId();
        Job job = requireDetailed(tenantId, jobId);
        requireReschedulable(job);
        JobAssignment assignment = job.getAssignments().stream()
                .filter(candidate -> candidate.getUserId().equals(userId))
                .findFirst()
                .orElseThrow(() -> ApiException.notFound("Assignment"));
        job.removeAssignment(assignment);
        audit.record(AuditAction.JOB_UNASSIGNED, "Job", jobId,
                Map.of("userId", userId.toString(), "number", job.getNumber()));
        return toResponses(List.of(job)).get(0);
    }

    @Transactional
    public JobDtos.Response transition(UUID jobId, JobDtos.TransitionRequest request) {
        UUID tenantId = currentUser.tenantId();
        Job job = requireReadable(jobId);
        JobStatus target = parseTransitionTarget(request.to());
        String note = trimToNull(request.note());

        if (target == JobStatus.CANCELLED && (note == null || note.length() < 3)) {
            throw ApiException.validation("note", "a cancellation reason of at least 3 characters is required");
        }
        boolean allowed = switch (job.getStatus()) {
            case SCHEDULED -> target == JobStatus.IN_PROGRESS || target == JobStatus.CANCELLED;
            case IN_PROGRESS -> target == JobStatus.COMPLETED || target == JobStatus.CANCELLED;
            default -> false;
        };
        if (!allowed) {
            throw ApiException.of(ErrorCode.INVALID_STATE_TRANSITION,
                    "cannot move a job from " + job.getStatus() + " to " + target);
        }

        job.setStatus(target);
        if (target == JobStatus.COMPLETED) {
            job.setCompletedAt(Instant.now());
        }
        if (note != null) {
            job.setNotes(appendNote(job.getNotes(), note));
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("number", job.getNumber());
        detail.put("to", target.name());
        if (note != null) {
            detail.put("note", note);
        }
        audit.record(tenantId, currentUser.id(), AuditAction.JOB_STATUS_CHANGED, "Job", jobId, detail);
        return toResponses(List.of(job)).get(0);
    }

    @Transactional
    public JobDtos.Response convertQuote(UUID quoteId, QuoteDtos.ConvertRequest request) {
        UUID tenantId = currentUser.tenantId();
        Tenant tenant = tenants.require(tenantId);
        Quote quote = quotes.requireConvertible(quoteId);
        validateWindow(request.scheduledStart(), request.scheduledEnd());
        List<AppUser> technicians = requireAssignableUsers(tenantId, request.technicianIds());
        Customer customer = customers.requireActive(tenantId, quote.getCustomerId());
        ServiceLocation location = quote.getLocationId() == null
                ? customers.defaultLocation(tenantId, customer.getId())
                : customers.requireLocation(tenantId, customer.getId(), quote.getLocationId());
        assertNoConflicts(tenantId, ids(technicians), request.scheduledStart(), request.scheduledEnd(), null);

        Job job = buildJob(tenant, customer, location, request.title(), request.scheduledStart(),
                request.scheduledEnd(), quote.getNotes(), quote.getId(), Recurrence.NONE, null);
        replaceAssignments(job, technicians);
        jobs.save(job);
        quotes.markConverted(quoteId, job.getId());

        audit.record(AuditAction.JOB_CREATED, "Job", job.getId(),
                Map.of("number", job.getNumber(), "quoteId", quoteId.toString(),
                        "customerId", customer.getId().toString()));
        return toResponses(List.of(job)).get(0);
    }

    public record InvoiceContext(UUID jobId, UUID customerId, UUID quoteId, String number, String notes) {
    }

    /** Loads the job facts billing needs without exposing the Job entity across modules. */
    @Transactional(readOnly = true)
    public InvoiceContext requireInvoiceable(UUID jobId) {
        Job job = requireDetailed(currentUser.tenantId(), jobId);
        if (job.getStatus() == JobStatus.CANCELLED) {
            throw ApiException.immutable("a cancelled job cannot be invoiced");
        }
        return new InvoiceContext(job.getId(), job.getCustomerId(), job.getQuoteId(), job.getNumber(),
                job.getNotes());
    }

    /** Used by reporting to count jobs in a window. */
    @Transactional(readOnly = true)
    public long countScheduledBetween(UUID tenantId, Instant from, Instant to) {
        return jobs.countByTenantIdAndScheduledStartGreaterThanEqualAndScheduledStartLessThan(tenantId, from, to);
    }

    @Transactional(readOnly = true)
    public long countByStatus(UUID tenantId, JobStatus status) {
        return jobs.countByTenantIdAndStatus(tenantId, status);
    }

    @Transactional(readOnly = true)
    public List<JobDtos.Response> upcoming(UUID tenantId, Instant from) {
        return toResponses(jobs.findTop5ByTenantIdAndStatusAndScheduledStartGreaterThanEqualOrderByScheduledStartAsc(
                tenantId, JobStatus.SCHEDULED, from));
    }

    // -------------------------------------------------------------------- internals

    private Job requireDetailed(UUID tenantId, UUID jobId) {
        return jobs.findDetailed(jobId, tenantId).orElseThrow(() -> ApiException.notFound("Job"));
    }

    private Job requireReadable(UUID jobId) {
        UUID tenantId = currentUser.tenantId();
        Job job = requireDetailed(tenantId, jobId);
        if (currentUser.role() == Role.TECHNICIAN && job.getAssignments().stream()
                .noneMatch(assignment -> assignment.getUserId().equals(currentUser.id()))) {
            // Technicians must not be able to probe for jobs they are not assigned to.
            throw ApiException.notFound("Job");
        }
        return job;
    }

    private void requireReschedulable(Job job) {
        if (job.getStatus() == JobStatus.COMPLETED || job.getStatus() == JobStatus.CANCELLED) {
            throw ApiException.immutable("a " + job.getStatus() + " job can no longer be changed");
        }
    }

    private Job buildJob(Tenant tenant, Customer customer, ServiceLocation location, String title,
                         Instant start, Instant end, String notes, UUID quoteId, Recurrence recurrence,
                         UUID seriesId) {
        Job job = new Job();
        job.setTenantId(tenant.getId());
        job.setCustomerId(customer.getId());
        job.setLocationId(location.getId());
        job.setQuoteId(quoteId);
        job.setTitle(defaultTitle(title, customer));
        job.setScheduledStart(start);
        job.setScheduledEnd(end);
        job.setStatus(JobStatus.SCHEDULED);
        job.setRecurrence(recurrence);
        job.setSeriesId(seriesId);
        job.setNotes(trimToNull(notes));
        job.setCreatedBy(currentUser.id());
        job.setNumber(numbers.next(tenant.getId(), DocumentType.JOB,
                start.atZone(ZoneId.of(tenant.getTimezone())).getYear()));
        return job;
    }

    private String defaultTitle(String title, Customer customer) {
        String base = title == null || title.isBlank() ? "Cleaning service — " + customer.getName() : title.trim();
        return base.length() > 160 ? base.substring(0, 160) : base;
    }

    private void replaceAssignments(Job job, List<AppUser> technicians) {
        Set<UUID> target = technicians.stream().map(AppUser::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        job.getAssignments().removeIf(assignment -> !target.contains(assignment.getUserId()));
        Set<UUID> present = job.getAssignments().stream().map(JobAssignment::getUserId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        for (AppUser user : technicians) {
            if (present.add(user.getId())) {
                JobAssignment assignment = new JobAssignment();
                assignment.setUserId(user.getId());
                job.addAssignment(assignment);
            }
        }
    }

    private List<AppUser> requireAssignableUsers(UUID tenantId, List<UUID> requested) {
        if (requested == null || requested.isEmpty()) {
            return List.of();
        }
        Set<UUID> distinct = new LinkedHashSet<>(requested);
        if (distinct.size() != requested.size()) {
            throw ApiException.validation("technicianIds", "contains duplicate users");
        }
        List<AppUser> found = users.findAllByTenantIdAndIdIn(tenantId, distinct);
        if (found.size() != distinct.size()) {
            throw ApiException.validation("technicianIds", "contains a user outside this workspace");
        }
        found.stream().filter(user -> !user.isActive()).findFirst().ifPresent(user -> {
            throw ApiException.validation("technicianIds", user.getFullName() + " is deactivated");
        });
        return found;
    }

    private void assertNoConflicts(UUID tenantId, Collection<UUID> userIds, Instant start, Instant end,
                                   UUID excludeJobId) {
        if (userIds == null || userIds.isEmpty()) {
            return;
        }
        List<Job> overlapping = jobs.findConflicts(tenantId, userIds, start, end, JobStatus.CANCELLED,
                excludeJobId);
        if (overlapping.isEmpty()) {
            return;
        }
        Map<UUID, String> names = users.findAllByTenantIdAndIdIn(tenantId, userIds).stream()
                .collect(Collectors.toMap(AppUser::getId, AppUser::getFullName, (a, b) -> a));
        List<JobDtos.Conflict> conflicts = new ArrayList<>();
        for (Job job : overlapping) {
            for (JobAssignment assignment : job.getAssignments()) {
                if (userIds.contains(assignment.getUserId())) {
                    conflicts.add(new JobDtos.Conflict(assignment.getUserId(),
                            names.getOrDefault(assignment.getUserId(), "Unknown user"),
                            job.getId(), job.getNumber(), job.getScheduledStart(), job.getScheduledEnd()));
                }
            }
        }
        if (!conflicts.isEmpty()) {
            throw ApiException.of(ErrorCode.JOB_SCHEDULE_CONFLICT,
                    "One or more technicians are already booked in this window")
                    .withProperty("conflicts", conflicts);
        }
    }

    private void validateWindow(Instant start, Instant end) {
        if (start == null || end == null) {
            throw ApiException.validation("scheduledStart", "start and end are required");
        }
        if (!end.isAfter(start)) {
            throw ApiException.validation("scheduledEnd", "must be after scheduledStart");
        }
        Duration duration = Duration.between(start, end);
        if (duration.compareTo(MIN_DURATION) < 0) {
            throw ApiException.validation("scheduledEnd", "a job must last at least 15 minutes");
        }
        if (duration.compareTo(MAX_DURATION) > 0) {
            throw ApiException.validation("scheduledEnd", "a job cannot exceed 24 hours");
        }
        if (start.isAfter(Instant.now().plus(MAX_HORIZON))) {
            throw ApiException.validation("scheduledStart", "cannot be more than 2 years in the future");
        }
    }

    private ZonedDateTime shift(ZonedDateTime firstStart, Recurrence recurrence, int index) {
        if (index == 0) {
            return firstStart;
        }
        return switch (recurrence) {
            case WEEKLY -> firstStart.plusWeeks(index);
            case BIWEEKLY -> firstStart.plusWeeks(2L * index);
            case MONTHLY -> firstStart.plusMonths(index);
            case NONE -> firstStart;
        };
    }

    private List<JobDtos.Response> toResponses(List<Job> jobList) {
        if (jobList.isEmpty()) {
            return List.of();
        }
        // Flush pending writes so generated createdAt/updatedAt values are present in the response.
        if (!TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            jobs.flush();
        }
        UUID tenantId = currentUser.tenantId();
        List<UUID> jobIds = jobList.stream().map(Job::getId).toList();
        List<JobAssignment> allAssignments = assignments.findAllByTenantIdAndJobIdIn(tenantId, jobIds);
        Map<UUID, List<UUID>> technicianIdsByJob = new HashMap<>();
        for (JobAssignment assignment : allAssignments) {
            technicianIdsByJob.computeIfAbsent(assignment.getJob().getId(), key -> new ArrayList<>())
                    .add(assignment.getUserId());
        }
        Set<UUID> userIds = allAssignments.stream().map(JobAssignment::getUserId).collect(Collectors.toSet());
        Map<UUID, AppUser> userMap = userIds.isEmpty() ? Map.of()
                : users.findAllByTenantIdAndIdIn(tenantId, userIds).stream()
                .collect(Collectors.toMap(AppUser::getId, Function.identity(), (a, b) -> a));
        Map<UUID, CustomerDtos.Summary> customerSummary = customers.summaries(tenantId,
                jobList.stream().map(Job::getCustomerId).collect(Collectors.toSet()));
        Map<UUID, CustomerDtos.LocationResponse> locationSummary = customers.locationSummaries(tenantId,
                jobList.stream().map(Job::getLocationId).collect(Collectors.toSet()));

        List<JobDtos.Response> responses = new ArrayList<>(jobList.size());
        for (Job job : jobList) {
            List<UUID> assigned = technicianIdsByJob.getOrDefault(job.getId(), List.of());
            List<JobDtos.TechnicianResponse> technicians = assigned.stream()
                    .map(userId -> {
                        AppUser user = userMap.get(userId);
                        return new JobDtos.TechnicianResponse(userId, user == null ? "Unknown user" : user.getFullName());
                    })
                    .toList();
            responses.add(JobDtos.Response.from(job, customerSummary.get(job.getCustomerId()),
                    locationSummary.get(job.getLocationId()), technicians));
        }
        return responses;
    }

    private Set<UUID> ids(List<AppUser> technicians) {
        return technicians.stream().map(AppUser::getId).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private Recurrence parseRecurrence(String value) {
        try {
            return Recurrence.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw ApiException.validation("recurrence", "must be WEEKLY, BIWEEKLY or MONTHLY");
        }
    }

    private JobStatus parseTransitionTarget(String value) {
        JobStatus target;
        try {
            target = JobStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw ApiException.validation("to", "must be IN_PROGRESS, COMPLETED or CANCELLED");
        }
        if (target == JobStatus.SCHEDULED) {
            throw ApiException.validation("to", "must be IN_PROGRESS, COMPLETED or CANCELLED");
        }
        return target;
    }

    private String appendNote(String existing, String note) {
        String combined = existing == null || existing.isBlank() ? note : existing + "\n" + note;
        return combined.length() > 2000 ? combined.substring(combined.length() - 2000) : combined;
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
