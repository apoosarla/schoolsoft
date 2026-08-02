package com.schoolsoft.platform.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Symmetric JWT (HS256) for MVP. Per design §17 — self-host Kratos/Hydra
 * eventually; in the meantime this keeps tokens issuer-controlled.
 *
 * Claims carried:
 *  - sub  : user_account.id
 *  - cid  : chain.id (UUID) OR 'platform' string for platform admins
 *  - cs   : chain schema_name (denormalised so resolver avoids a DB lookup)
 *  - sid  : school_id (nullable for chain-wide actors)
 *  - st   : subject_type ('staff' | 'guardian' | 'student' | 'platform_admin')
 *  - typ  : 'access' | 'refresh'
 */
@Service
public class JwtService {

    private final SecretKey key;
    private final long accessTtlMinutes;
    private final long refreshTtlDays;

    public JwtService(
            @Value("${mcb.jwt.secret}") String secret,
            @Value("${mcb.jwt.access-ttl-minutes:15}") long accessTtlMinutes,
            @Value("${mcb.jwt.refresh-ttl-days:30}") long refreshTtlDays
    ) {
        if (secret.length() < 32) {
            throw new IllegalStateException("mcb.jwt.secret must be ≥ 32 chars");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTtlMinutes = accessTtlMinutes;
        this.refreshTtlDays = refreshTtlDays;
    }

    public String issueAccess(UUID userAccountId, String chainIdOrPlatform, String chainSchema,
                              UUID schoolId, String subjectType) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userAccountId.toString())
                .claims(Map.of(
                    "cid", chainIdOrPlatform,
                    "cs",  chainSchema,
                    "sid", schoolId == null ? "" : schoolId.toString(),
                    "st",  subjectType,
                    "typ", "access"
                ))
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(accessTtlMinutes, ChronoUnit.MINUTES)))
                .signWith(key)
                .compact();
    }

    public String issueRefresh(UUID userAccountId, String chainIdOrPlatform, String chainSchema) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userAccountId.toString())
                .claims(Map.of("cid", chainIdOrPlatform, "cs", chainSchema, "typ", "refresh"))
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(refreshTtlDays, ChronoUnit.DAYS)))
                .signWith(key)
                .compact();
    }

    public Claims parse(String token) throws JwtException {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }
}
