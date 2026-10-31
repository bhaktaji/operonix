package com.operonix.billing;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InvoiceRepository extends JpaRepository<Invoice, UUID>,
        JpaSpecificationExecutor<Invoice> {

    Optional<Invoice> findByIdAndTenantId(UUID id, UUID tenantId);

    @EntityGraph(attributePaths = "lines")
    @Query("select i from Invoice i where i.id = :id and i.tenantId = :tenantId")
    Optional<Invoice> findDetailed(@Param("id") UUID id, @Param("tenantId") UUID tenantId);

    @Query("""
            select i from Invoice i
            where i.jobId = :jobId and i.tenantId = :tenantId and i.status <> :voidStatus
            """)
    Optional<Invoice> findActiveByJobId(@Param("jobId") UUID jobId, @Param("tenantId") UUID tenantId,
                                        @Param("voidStatus") InvoiceStatus voidStatus);

    long countByTenantIdAndStatusIn(UUID tenantId, Collection<InvoiceStatus> statuses);

    @Query("""
            select coalesce(sum(i.total - i.amountPaid), 0) from Invoice i
            where i.tenantId = :tenantId and i.status in :statuses
            """)
    java.math.BigDecimal sumOutstanding(@Param("tenantId") UUID tenantId,
                                        @Param("statuses") Collection<InvoiceStatus> statuses);

    @Query("""
            select coalesce(sum(i.total - i.amountPaid), 0) from Invoice i
            where i.tenantId = :tenantId and i.status in :statuses and i.dueDate < :today
            """)
    java.math.BigDecimal sumOverdue(@Param("tenantId") UUID tenantId,
                                    @Param("statuses") Collection<InvoiceStatus> statuses,
                                    @Param("today") LocalDate today);

    @Query("""
            select coalesce(s.name, l.description), sum(l.lineSubtotal) from Invoice i
            join i.lines l left join ServiceItem s on s.id = l.serviceItemId
            where i.tenantId = :tenantId and i.status <> :voidStatus
              and i.issueDate >= :from and i.issueDate < :to
            group by coalesce(s.name, l.description)
            order by sum(l.lineSubtotal) desc
            """)
    List<Object[]> revenueByService(@Param("tenantId") UUID tenantId, @Param("from") LocalDate from,
                                    @Param("to") LocalDate to,
                                    @Param("voidStatus") InvoiceStatus voidStatus, Pageable pageable);
}
