package com.operonix.document;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface DocumentSequenceRepository extends JpaRepository<DocumentSequence, DocumentSequenceId> {

    @Modifying
    @Query(value = """
            INSERT INTO document_sequence (tenant_id, doc_type, year, next_value)
            VALUES (:tenantId, :docType, :year, 1)
            ON CONFLICT (tenant_id, doc_type, year) DO NOTHING
            """, nativeQuery = true)
    void ensureRow(@Param("tenantId") UUID tenantId, @Param("docType") String docType,
                   @Param("year") int year);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select s from DocumentSequence s
            where s.id.tenantId = :tenantId and s.id.docType = :docType and s.id.year = :year
            """)
    Optional<DocumentSequence> lockRow(@Param("tenantId") UUID tenantId,
                                       @Param("docType") DocumentType docType,
                                       @Param("year") int year);
}
