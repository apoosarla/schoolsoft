package com.schoolsoft.curriculum.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schoolsoft.curriculum.api.CurriculumDto;
import com.schoolsoft.curriculum.api.CurriculumNodeDto;
import com.schoolsoft.curriculum.api.CurriculumTemplateDto;
import com.schoolsoft.curriculum.api.LearningOutcomeDto;
import com.schoolsoft.platform.web.NotFoundException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Backs the Curriculum Engine (design doc §9). Master templates live in
 * {@code platform.curriculum_template} — read via the {@code platform}
 * fallback on every connection's search_path (see {@code TenantAwareDataSource}).
 * Cloning a template walks its JSON tree and materialises rows (with computed
 * path/depth) into the current chain schema's {@code curriculum_node} table.
 */
@Repository
public class CurriculumRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public CurriculumRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    private static final RowMapper<CurriculumDto> CURRICULUM = (rs, i) -> new CurriculumDto(
        UUID.fromString(rs.getString("id")),
        UUID.fromString(rs.getString("school_id")),
        rs.getString("board_code"),
        rs.getString("strategy_code"),
        rs.getString("name"),
        rs.getString("version"),
        rs.getString("grade_id") == null ? null : UUID.fromString(rs.getString("grade_id")),
        rs.getString("subject_id") == null ? null : UUID.fromString(rs.getString("subject_id")),
        rs.getString("source_template_id") == null ? null : UUID.fromString(rs.getString("source_template_id")),
        rs.getBoolean("is_published"),
        rs.getTimestamp("created_at").toInstant()
    );

    private static final String CURRICULUM_COLS =
        "id, school_id, board_code, strategy_code, name, version, grade_id, subject_id, source_template_id, " +
        "is_published, created_at";

    // -------------------------- Templates (platform, read-only) --------------------------

    public List<CurriculumTemplateDto> listTemplates(String boardCode) {
        String sql = "SELECT id, board_code, strategy_code, name, version, grade_band " +
            "FROM platform.curriculum_template" + (boardCode == null ? "" : " WHERE board_code = ?") +
            " ORDER BY board_code, name";
        RowMapper<CurriculumTemplateDto> mapper = (rs, i) -> new CurriculumTemplateDto(
            UUID.fromString(rs.getString("id")),
            rs.getString("board_code"),
            rs.getString("strategy_code"),
            rs.getString("name"),
            rs.getString("version"),
            rs.getString("grade_band")
        );
        return boardCode == null ? jdbc.query(sql, mapper) : jdbc.query(sql, mapper, boardCode);
    }

    // -------------------------- Curriculum --------------------------

    public List<CurriculumDto> listCurricula(UUID schoolId) {
        return jdbc.query(
            "SELECT " + CURRICULUM_COLS + " FROM curriculum WHERE school_id = ? ORDER BY created_at DESC",
            CURRICULUM, schoolId
        );
    }

    public Optional<CurriculumDto> findCurriculum(UUID id) {
        var rows = jdbc.query("SELECT " + CURRICULUM_COLS + " FROM curriculum WHERE id = ?", CURRICULUM, id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public CurriculumDto createCurriculum(
        UUID schoolId, String boardCode, String strategyCode, String name, String version,
        UUID gradeId, UUID subjectId
    ) {
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO curriculum (id, school_id, board_code, strategy_code, name, version, grade_id, subject_id) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            id, schoolId, boardCode, strategyCode, name, version, gradeId, subjectId
        );
        return findCurriculum(id).orElseThrow();
    }

    public void publish(UUID curriculumId) {
        int updated = jdbc.update("UPDATE curriculum SET is_published = TRUE WHERE id = ?", curriculumId);
        if (updated == 0) throw new NotFoundException("Curriculum not found: " + curriculumId);
    }

    /**
     * Clones a platform template's JSON tree into a new curriculum owned by
     * {@code schoolId}. Template payload shape: {@code {"nodes": [{"type","code",
     * "name","children":[...],"learningOutcomes":[{"code","statement","bloomLevel"}]}]}}.
     */
    public CurriculumDto cloneFromTemplate(UUID schoolId, UUID templateId, UUID gradeId, UUID subjectId) {
        var templateRows = jdbc.query(
            "SELECT id, board_code, strategy_code, name, version, payload FROM platform.curriculum_template WHERE id = ?",
            (rs, i) -> new Object[]{
                rs.getString("board_code"), rs.getString("strategy_code"),
                rs.getString("name"), rs.getString("version"), rs.getString("payload")
            },
            templateId
        );
        if (templateRows.isEmpty()) throw new NotFoundException("Curriculum template not found: " + templateId);
        Object[] templateRow = templateRows.get(0);

        String boardCode = (String) templateRow[0];
        String strategyCode = (String) templateRow[1];
        String name = (String) templateRow[2];
        String version = (String) templateRow[3];
        String payloadJson = (String) templateRow[4];

        UUID curriculumId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO curriculum (id, school_id, board_code, strategy_code, name, version, grade_id, subject_id, source_template_id) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            curriculumId, schoolId, boardCode, strategyCode, name, version, gradeId, subjectId, templateId
        );

        JsonNode root;
        try {
            root = json.readTree(payloadJson);
        } catch (Exception e) {
            throw new IllegalStateException("Malformed template payload for " + templateId, e);
        }
        JsonNode nodes = root.get("nodes");
        if (nodes != null && nodes.isArray()) {
            int order = 0;
            for (JsonNode node : nodes) {
                insertNodeTree(curriculumId, null, "", 0, node, order++);
            }
        }

        return findCurriculum(curriculumId).orElseThrow();
    }

    private void insertNodeTree(UUID curriculumId, UUID parentId, String parentPath, int depth, JsonNode node, int sortOrder) {
        UUID nodeId = UUID.randomUUID();
        String path = parentPath + "/" + nodeId;
        String nodeType = node.path("type").asText();
        String code = node.hasNonNull("code") ? node.get("code").asText() : null;
        String name = node.path("name").asText();

        jdbc.update(
            "INSERT INTO curriculum_node (id, curriculum_id, parent_id, node_type, code, name, sort_order, path, depth) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            nodeId, curriculumId, parentId, nodeType, code, name, sortOrder, path, depth
        );

        JsonNode los = node.get("learningOutcomes");
        if (los != null && los.isArray()) {
            int loOrder = 0;
            for (JsonNode lo : los) {
                jdbc.update(
                    "INSERT INTO learning_outcome (id, curriculum_node_id, code, statement, bloom_level, sort_order) " +
                    "VALUES (?, ?, ?, ?, ?, ?)",
                    UUID.randomUUID(), nodeId,
                    lo.hasNonNull("code") ? lo.get("code").asText() : null,
                    lo.path("statement").asText(),
                    lo.hasNonNull("bloomLevel") ? lo.get("bloomLevel").asText() : null,
                    loOrder++
                );
            }
        }

        JsonNode children = node.get("children");
        if (children != null && children.isArray()) {
            int childOrder = 0;
            for (JsonNode child : children) {
                insertNodeTree(curriculumId, nodeId, path, depth + 1, child, childOrder++);
            }
        }
    }

    // -------------------------- Nodes / Learning Outcomes (manual authoring) --------------------------

    public List<CurriculumNodeDto> listNodes(UUID curriculumId) {
        return jdbc.query(
            "SELECT id, curriculum_id, parent_id, node_type, code, name, sort_order, path, depth " +
            "FROM curriculum_node WHERE curriculum_id = ? ORDER BY path",
            (rs, i) -> new CurriculumNodeDto(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("curriculum_id")),
                rs.getString("parent_id") == null ? null : UUID.fromString(rs.getString("parent_id")),
                rs.getString("node_type"),
                rs.getString("code"),
                rs.getString("name"),
                rs.getInt("sort_order"),
                rs.getString("path"),
                rs.getInt("depth")
            ),
            curriculumId
        );
    }

    public CurriculumNodeDto addNode(UUID curriculumId, UUID parentId, String nodeType, String code, String name, int sortOrder) {
        String parentPath = "";
        int depth = 0;
        if (parentId != null) {
            var parents = jdbc.query(
                "SELECT path, depth FROM curriculum_node WHERE id = ?",
                (rs, i) -> new Object[]{rs.getString("path"), rs.getInt("depth")},
                parentId
            );
            if (parents.isEmpty()) throw new NotFoundException("Parent node not found: " + parentId);
            Object[] parent = parents.get(0);
            parentPath = (String) parent[0];
            depth = (int) parent[1] + 1;
        }
        UUID nodeId = UUID.randomUUID();
        String path = parentPath + "/" + nodeId;
        jdbc.update(
            "INSERT INTO curriculum_node (id, curriculum_id, parent_id, node_type, code, name, sort_order, path, depth) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            nodeId, curriculumId, parentId, nodeType, code, name, sortOrder, path, depth
        );
        return new CurriculumNodeDto(nodeId, curriculumId, parentId, nodeType, code, name, sortOrder, path, depth);
    }

    public List<LearningOutcomeDto> listLearningOutcomes(UUID nodeId) {
        return jdbc.query(
            "SELECT id, curriculum_node_id, code, statement, bloom_level, sort_order " +
            "FROM learning_outcome WHERE curriculum_node_id = ? ORDER BY sort_order",
            LO_MAPPER, nodeId
        );
    }

    private static final RowMapper<LearningOutcomeDto> LO_MAPPER = (rs, i) -> new LearningOutcomeDto(
        UUID.fromString(rs.getString("id")),
        UUID.fromString(rs.getString("curriculum_node_id")),
        rs.getString("code"),
        rs.getString("statement"),
        rs.getString("bloom_level"),
        rs.getInt("sort_order")
    );

    public LearningOutcomeDto addLearningOutcome(UUID nodeId, String code, String statement, String bloomLevel, int sortOrder) {
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO learning_outcome (id, curriculum_node_id, code, statement, bloom_level, sort_order) " +
            "VALUES (?, ?, ?, ?, ?, ?)",
            id, nodeId, code, statement, bloomLevel, sortOrder
        );
        return new LearningOutcomeDto(id, nodeId, code, statement, bloomLevel, sortOrder);
    }
}
