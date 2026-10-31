package com.operonix.billing;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    List<Payment> findAllByTenantIdAndInvoiceIdOrderByPaidAtAsc(UUID tenantId, UUID invoiceId);

    List<Payment> findAllByTenantIdAndInvoiceIdInOrderByPaidAtAsc(UUID tenantId, List<UUID> invoiceIds);

    Optional<Payment> findByIdAndTenantIdAndInvoiceId(UUID id, UUID tenantId, UUID invoiceId);

    @Query("""
            select coalesce(sum(p.amount), 0) from Payment p
            where p.tenantId = :tenantId and p.paidAt >= :from and p.paidAt < :to
            """)
    BigDecimal sumBetween(@Param("tenantId") UUID tenantId, @Param("from") Instant from,
                          @Param("to") Instant to);

    @Query("""
            select p from Payment p
            where p.tenantId = :tenantId and p.paidAt >= :from and p.paidAt < :to
            """)
    List<Payment> findAllBetween(@Param("tenantId") UUID tenantId, @Param("from") Instant from,
                                 @Param("to") Instant to);

    @Query("""
            select p.invoice.customerId, coalesce(sum(p.amount), 0) from Payment p
            where p.tenantId = :tenantId and p.paidAt >= :from and p.paidAt < :to
            group by p.invoice.customerId
            """)
    List<Object[]> revenueByCustomer(@Param("tenantId") UUID tenantId, @Param("from") Instant from,
                                     @Param("to") Instant to);
}
