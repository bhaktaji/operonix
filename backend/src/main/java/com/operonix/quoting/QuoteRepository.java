package com.operonix.quoting;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface QuoteRepository extends JpaRepository<Quote, UUID>,
        JpaSpecificationExecutor<Quote> {

    Optional<Quote> findByIdAndTenantId(UUID id, UUID tenantId);

    @EntityGraph(attributePaths = "lines")
    @Query("select q from Quote q where q.id = :id and q.tenantId = :tenantId")
    Optional<Quote> findDetailed(@Param("id") UUID id, @Param("tenantId") UUID tenantId);

    boolean existsByTenantIdAndNumber(UUID tenantId, String number);

    long countByTenantIdAndStatus(UUID tenantId, QuoteStatus status);

    @Query("""
            select count(q) from Quote q
            where q.tenantId = :tenantId and q.status in :statuses
            """)
    long countByStatuses(@Param("tenantId") UUID tenantId,
                         @Param("statuses") java.util.Collection<QuoteStatus> statuses);

    long countByTenantIdAndStatusIn(UUID tenantId, java.util.Collection<QuoteStatus> statuses);
}
