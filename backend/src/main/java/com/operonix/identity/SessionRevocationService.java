package com.operonix.identity;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Revocations triggered by a security event (refresh-token reuse, deactivation) must outlive the
 * caller's transaction rollback, so they run in their own transaction.
 */
@Service
public class SessionRevocationService {

    private final RefreshTokenRepository repository;

    public SessionRevocationService(RefreshTokenRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int revokeAll(UUID userId, Instant now) {
        return repository.revokeAllForUser(userId, now);
    }

    /** Revokes one rotation chain only: the compromised session, not every session the user owns. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int revokeFamily(UUID familyId, Instant now) {
        return repository.revokeFamily(familyId, now);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void revokeHash(String tokenHash, Instant now) {
        repository.findByTokenHash(tokenHash).ifPresent(token -> {
            if (token.getRevokedAt() == null) {
                token.setRevokedAt(now);
                repository.save(token);
            }
        });
    }
}
