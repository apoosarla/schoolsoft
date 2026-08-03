package com.schoolsoft.file.api;

import jakarta.validation.constraints.NotBlank;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/files")
public class FileController {

    private final FileService files;
    public FileController(FileService files) { this.files = files; }

    public record IssueUploadRequest(@NotBlank String filename, String mimeType, Long sizeBytes) {}

    @PostMapping("/upload-ticket")
    public FileService.UploadTicket issueUpload(@RequestBody IssueUploadRequest req) {
        return files.issueUpload(req.filename(), req.mimeType(), req.sizeBytes());
    }

    @GetMapping("/{id}/download-ticket")
    public FileService.DownloadTicket issueDownload(@PathVariable UUID id) {
        return files.issueDownload(id);
    }
}
