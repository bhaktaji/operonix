package com.operonix.security;

import com.operonix.config.OperonixProperties;
import com.operonix.identity.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

@Service
public class JwtService {

    private static final String DEV_MARKER = "dev-only";

    private final SecretKey key;
    private final String issuer;
    private final String audience;
    private final Duration accessTtl;
    private final Duration refreshTtl;
    private final Clock clock;

    public JwtService(OperonixProperties properties, Environment environment, Clock clock) {
        OperonixProperties.Jwt config = properties.jwt();
        String secret = config.secret() == null ? "" : config.secret().trim();
        if (secret.isEmpty()) {
            throw new IllegalStateException(
                    "OPERONIX_JWT_SECRET is required (minimum 32 bytes). Refusing to start without a signing secret.");
        }
        if (secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("OPERONIX_JWT_SECRET must be at least 32 bytes long");
        }
        if (environment.acceptsProfiles(Profiles.of("prod")) && secret.contains(DEV_MARKER)) {
            throw new IllegalStateException("The development JWT secret cannot be used with the prod profile");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.issuer = config.issuer();
        this.audience = config.audience();
        this.accessTtl = config.accessTokenTtl();
        this.refreshTtl = config.refreshTokenTtl();
        this.clock = clock;
        if (accessTtl.isNegative() || accessTtl.isZero()) {
            throw new IllegalStateException("operonix.jwt.access-token-ttl must be positive");
        }
        if (refreshTtl.isNegative() || refreshTtl.isZero()) {
            throw new IllegalStateException("operonix.jwt.refresh-token-ttl must be positive");
        }
    }

    public record IssuedToken(String value, long expiresInSeconds) {
    }

    public IssuedToken issue(AuthenticatedUser user) {
        Instant now = clock.instant();
        Instant expiry = now.plus(accessTtl);
        String token = Jwts.builder()
                .issuer(issuer)
                .audience().add(audience).and()
                .subject(user.id().toString())
                .claim("tid", user.tenantId().toString())
                .claim("role", user.role().name())
                .claim("email", user.email())
                .claim("name", user.fullName())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .id(UUID.randomUUID().toString())
                .signWith(key, Jwts.SIG.HS256)
                .compact();
        return new IssuedToken(token, accessTtl.toSeconds());
    }

    /**
     * @throws io.jsonwebtoken.ExpiredJwtException if the token is expired
     * @throws io.jsonwebtoken.JwtException        if signature/claims are invalid
     */
    public AuthenticatedUser parse(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .requireIssuer(issuer)
                .requireAudience(audience)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        return new AuthenticatedUser(
                UUID.fromString(claims.getSubject()),
                UUID.fromString(claims.get("tid", String.class)),
                Role.valueOf(claims.get("role", String.class)),
                claims.get("email", String.class),
                claims.get("name", String.class));
    }

    public Duration accessTtl() {
        return accessTtl;
    }

    public Duration refreshTtl() {
        return refreshTtl;
    }
}
