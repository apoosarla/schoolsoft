package com.schoolsoft.notification.api;

import com.schoolsoft.notification.internal.NotificationDeviceRepository;
import com.schoolsoft.platform.tenancy.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Push token registry for mobile app sessions. The owning account comes from
 * the bearer token, not the request body, so a session can only register or
 * drop its own devices.
 */
@RestController
@RequestMapping("/v1/notifications/devices")
public class PushDeviceController {

    private final NotificationDeviceRepository repo;

    public PushDeviceController(NotificationDeviceRepository repo) { this.repo = repo; }

    public record RegisterDeviceRequest(
        @NotBlank String token,
        @Pattern(regexp = "android|ios|web") String platform
    ) {}

    @PostMapping
    public PushDeviceDto register(@Valid @RequestBody RegisterDeviceRequest req) {
        var d = repo.upsert(TenantContext.require().userAccountId(), req.token(), req.platform());
        return new PushDeviceDto(d.id(), d.userAccountId(), d.platform(), d.createdAt(), d.lastSeenAt());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> unregister(@PathVariable UUID id) {
        repo.delete(id, TenantContext.require().userAccountId());
        return ResponseEntity.noContent().build();
    }
}
