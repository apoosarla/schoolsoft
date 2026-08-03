package com.schoolsoft.device.api;

import com.schoolsoft.device.internal.DeviceRepository;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/devices")
public class DeviceController {

    private final DeviceRepository repo;
    public DeviceController(DeviceRepository repo) { this.repo = repo; }

    @GetMapping
    public List<DeviceDto> list(@RequestParam UUID schoolId, @RequestParam(required = false) String kind) {
        return repo.list(schoolId, kind);
    }

    public record RegisterRequest(
        @NotNull UUID schoolId, @NotBlank String kind, String vendor, String model,
        @NotBlank String serialNo, String location, String apiKey
    ) {}

    @PostMapping
    public DeviceDto register(@RequestBody RegisterRequest req) {
        return repo.register(req.schoolId(), req.kind(), req.vendor(), req.model(), req.serialNo(), req.location(), req.apiKey());
    }

    public record StudentEventRequest(
        @NotNull UUID schoolId, @NotNull UUID studentId, @NotNull UUID sectionId, LocalDate onDate, @NotBlank String source
    ) {}

    /** Ingestion endpoint a school-side biometric/RFID bridge posts to once it has resolved a read to a student. */
    @PostMapping("/{deviceId}/events/student")
    public DeviceDto studentEvent(@PathVariable UUID deviceId, @RequestBody StudentEventRequest req) {
        return repo.ingestStudentEvent(
            deviceId, req.schoolId(), req.studentId(), req.sectionId(),
            req.onDate() == null ? LocalDate.now() : req.onDate(), req.source()
        );
    }

    public record StaffEventRequest(@NotNull UUID schoolId, @NotNull UUID staffId, LocalDate onDate, boolean checkIn) {}

    @PostMapping("/{deviceId}/events/staff")
    public DeviceDto staffEvent(@PathVariable UUID deviceId, @RequestBody StaffEventRequest req) {
        return repo.ingestStaffEvent(
            deviceId, req.schoolId(), req.staffId(), req.onDate() == null ? LocalDate.now() : req.onDate(), req.checkIn()
        );
    }
}
