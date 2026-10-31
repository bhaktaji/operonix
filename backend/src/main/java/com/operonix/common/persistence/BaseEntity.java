package com.operonix.common.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Transient;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.UUID;

/**
 * Identifier and creation timestamp shared by all persisted aggregates.
 *
 * <p>Ids are assigned in the constructor, so a freshly built entity has a non-null id. Spring Data's
 * default newness check would then treat it as detached and route {@code save()} through
 * {@code merge()}, which returns a copy: the instance the service keeps would never receive the
 * generated {@code createdAt}/{@code updatedAt} values, and every insert would cost an extra select.
 * Implementing {@link Persistable} tells Spring Data the truth instead.
 */
@MappedSuperclass
@Getter
@Setter
public abstract class BaseEntity implements Persistable<UUID> {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id = UUID.randomUUID();

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Transient
    private boolean newEntity = true;

    @Override
    public boolean isNew() {
        return newEntity;
    }

    @PrePersist
    @PostLoad
    void markPersisted() {
        this.newEntity = false;
    }
}