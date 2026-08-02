package com.schoolsoft.search.api;

import com.schoolsoft.platform.tenancy.TenantContext;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Stub. Real impl will use OpenSearch via spring-data-opensearch. Per-tenant
 * index aliases follow the pattern {@code mcb-{chain}-{entity}-{ay}}.
 */
@Service
public class SearchService {
    private static final Logger log = LoggerFactory.getLogger(SearchService.class);

    public void index(String entity, String id, Map<String, Object> doc) {
        var snap = TenantContext.get();
        log.debug("[search/stub] index {} {}/{} → {}", aliasFor(entity, snap), id, doc);
    }

    public List<Map<String, Object>> query(String entity, String q) {
        return List.of();
    }

    private String aliasFor(String entity, TenantContext.Snapshot snap) {
        String chain = snap == null ? "_" : (snap.chainSchema() == null ? "_" : snap.chainSchema());
        return "mcb-" + chain + "-" + entity;
    }
}
