package com.operonix.global;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ExpenseRepository extends JpaRepository<Expense, UUID>,
        JpaSpecificationExecutor<Expense> {

    List<Expense> findByTenantIdAndJobIdOrderByExpenseDateDescCreatedAtDesc(UUID tenantId, UUID jobId);

    Optional<Expense> findByIdAndTenantId(UUID id, UUID tenantId);

    @Query("""
            select e.category, coalesce(sum(e.workspaceAmount), 0) from Expense e
            where e.tenantId = :tenantId and e.job.id = :jobId
            group by e.category
            """)
    List<Object[]> sumByCategoryForJob(@Param("tenantId") UUID tenantId, @Param("jobId") UUID jobId);

    /** Expenses with no job are business overhead; they are excluded from direct job profit. */
    @Query("""
            select coalesce(sum(e.workspaceAmount), 0) from Expense e
            where e.tenantId = :tenantId and e.job is null
              and e.expenseDate >= :from and e.expenseDate <= :to
            """)
    BigDecimal sumOverhead(@Param("tenantId") UUID tenantId, @Param("from") LocalDate from,
                           @Param("to") LocalDate to);

    @Query("""
            select e.category, coalesce(sum(e.workspaceAmount), 0) from Expense e
            where e.tenantId = :tenantId and e.expenseDate >= :from and e.expenseDate <= :to
            group by e.category
            """)
    List<Object[]> sumByCategory(@Param("tenantId") UUID tenantId, @Param("from") LocalDate from,
                                 @Param("to") LocalDate to);

    @Query("""
            select coalesce(sum(e.workspaceAmount), 0) from Expense e
            where e.tenantId = :tenantId and e.expenseDate >= :from and e.expenseDate <= :to
            """)
    BigDecimal sumAll(@Param("tenantId") UUID tenantId, @Param("from") LocalDate from,
                      @Param("to") LocalDate to);
}
