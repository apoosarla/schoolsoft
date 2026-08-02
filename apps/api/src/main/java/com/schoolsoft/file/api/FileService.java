package com.schoolsoft.file.api;

import com.schoolsoft.platform.tenancy.TenantContext;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * File metadata + signed URL hand-out. The actual S3/MinIO call is stubbed —
 * we return a presigned-looking URL that the upload helper script can use.
 *
 * In production the signing happens against the bucket configured per
 * {@code platform.region_config}; here we point at the local MinIO instance.
 */
@Service
public class FileService {

    private final JdbcTemplate jdbc;
    private final String defaultBucket;
    private final String publicBase;

    public FileService(
        JdbcTemplate jdbc,
        @Value("${schoolsoft.file.bucket:schoolsoft-dev}") String bucket,
        @Value("${schoolsoft.file.public-base:http://localhost:9000}") String publicBase
    ) {
        this.jdbc = jdbc;
        this.defaultBucket = bucket;
        this.publicBase = publicBase;
    }

    public record UploadTicket(UUID fileId, String uploadUrl, String objectKey, Instant expiresAt) {}
    public record DownloadTicket(UUID fileId, String url, Instant expiresAt) {}

    public UploadTicket issueUpload(String filename, String mimeType, Long sizeBytes) {
        var snap = TenantContext.require();
        UUID id = UUID.randomUUID();
        String key = "schools/" + (snap.schoolId() == null ? "_chain" : snap.schoolId()) + "/" + id + "-" + filename;
        jdbc.update(
            "INSERT INTO file_object (id, school_id, bucket, object_key, mime_type, size_bytes, uploaded_by_user_id) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?)",
            id, snap.schoolId(), defaultBucket, key, mimeType, sizeBytes, snap.userAccountId()
        );
        Instant exp = Instant.now().plus(Duration.ofMinutes(15));
        return new UploadTicket(id, publicBase + "/" + defaultBucket + "/" + key + "?sig=stub", key, exp);
    }

    public DownloadTicket issueDownload(UUID fileId) {
        Map<String, Object> row = jdbc.queryForMap(
            "SELECT bucket, object_key FROM file_object WHERE id = ?", fileId
        );
        Instant exp = Instant.now().plus(Duration.ofMinutes(15));
        return new DownloadTicket(
            fileId,
            publicBase + "/" + row.get("bucket") + "/" + row.get("object_key") + "?sig=stub",
            exp
        );
    }
}
