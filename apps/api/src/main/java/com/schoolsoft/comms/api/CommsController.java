package com.schoolsoft.comms.api;

import com.schoolsoft.comms.internal.CommsRepository;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/comms")
public class CommsController {

    private final CommsRepository repo;
    public CommsController(CommsRepository repo) { this.repo = repo; }

    // -------------------------- Announcements --------------------------

    @GetMapping("/announcements")
    public List<AnnouncementDto> announcements(@RequestParam UUID schoolId) {
        return repo.list(schoolId);
    }

    public record CreateAnnouncementRequest(
        @NotNull UUID schoolId, @NotBlank String scopeType, List<UUID> scopeIds, @NotBlank String title,
        @NotBlank String body, List<String> channels, UUID createdByUserId
    ) {}

    @PostMapping("/announcements")
    public AnnouncementDto createAnnouncement(@RequestBody CreateAnnouncementRequest req) {
        return repo.create(req.schoolId(), req.scopeType(), req.scopeIds(), req.title(), req.body(), req.channels(), req.createdByUserId());
    }

    @PostMapping("/announcements/{id}/publish")
    public AnnouncementDto publish(@PathVariable UUID id) {
        return repo.publish(id);
    }

    @PostMapping("/announcements/{id}/read")
    public ResponseEntity<Void> markRead(@PathVariable UUID id, @RequestParam UUID userAccountId) {
        repo.markRead(id, userAccountId);
        return ResponseEntity.noContent().build();
    }

    // -------------------------- Messaging --------------------------

    @GetMapping("/threads")
    public List<MessageThreadDto> threads(@RequestParam UUID userAccountId) {
        return repo.threadsForParticipant(userAccountId);
    }

    public record CreateThreadRequest(@NotNull UUID schoolId, UUID subjectStudentId, @NotNull List<UUID> participants) {}

    @PostMapping("/threads")
    public MessageThreadDto createThread(@RequestBody CreateThreadRequest req) {
        return repo.createThread(req.schoolId(), req.subjectStudentId(), req.participants());
    }

    @GetMapping("/threads/{id}/messages")
    public List<MessageDto> messages(@PathVariable UUID id) {
        return repo.listMessages(id);
    }

    public record SendMessageRequest(@NotNull UUID senderUserId, @NotBlank String body) {}

    @PostMapping("/threads/{id}/messages")
    public MessageDto sendMessage(@PathVariable UUID id, @RequestBody SendMessageRequest req) {
        return repo.sendMessage(id, req.senderUserId(), req.body());
    }
}
