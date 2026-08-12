package com.schoolsoft.certification;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Keeps the scenario catalogue and the suite mechanically in step.
 *
 * {@code docs/certification-test-scenarios.md} is the contract; every id in it
 * must have exactly one {@code cert_<ID>_…} test method carrying the priority
 * tag the catalogue assigns, and no test may claim an id the catalogue does not
 * list. Adding a scenario to the document therefore fails the build until
 * someone writes the test — even if that test lands {@code @Disabled} naming
 * its gap.
 *
 * Also writes {@code target/certification-status.md}: the passing set, and the
 * disabled set with the reason each one carries. That file is the remaining
 * work, generated rather than maintained.
 */
class CatalogueSyncTest {

    private static final Pattern CATALOGUE_ROW =
        Pattern.compile("^\\|\\s*([A-Z]{2,4})-(\\d{2})\\s*\\|(.*)\\|\\s*(P[123])\\s*\\|\\s*$");
    private static final Pattern TEST_METHOD =
        Pattern.compile("void\\s+cert_([A-Z]{2,4})_(\\d{2})_(\\w+)\\s*\\(");
    private static final Pattern PRIORITY_TAG = Pattern.compile("@Tag\\(\"(P[123])\"\\)");
    private static final Pattern DISABLED_REASON =
        Pattern.compile("@Disabled\\(\\s*\"(.*?)\"\\s*\\)", Pattern.DOTALL);

    private record Scenario(String id, String priority, String summary) {}

    private record TestCase(String id, String priority, String methodName, String className, String disabledReason) {}

    @Test @Tag("harness")
    void everyCatalogueScenarioHasExactlyOneTestWithTheRightPriority() throws IOException {
        Map<String, Scenario> catalogue = readCatalogue();
        Map<String, TestCase> tests = readTests();

        assertThat(catalogue).isNotEmpty();
        assertThat(tests.keySet())
            .describedAs("scenario ids in the suite vs the catalogue")
            .containsExactlyInAnyOrderElementsOf(catalogue.keySet());

        List<String> priorityMismatches = new ArrayList<>();
        for (var entry : catalogue.entrySet()) {
            TestCase test = tests.get(entry.getKey());
            if (!entry.getValue().priority().equals(test.priority())) {
                priorityMismatches.add(entry.getKey() + ": catalogue says " + entry.getValue().priority()
                    + ", test is tagged " + test.priority());
            }
        }
        assertThat(priorityMismatches).isEmpty();

        writeStatusReport(catalogue, tests);
    }

    // ----------------------------------------------------------------- inputs

    private Map<String, Scenario> readCatalogue() throws IOException {
        Path doc = repoRoot().resolve("docs/certification-test-scenarios.md");
        assertThat(doc).exists();

        Map<String, Scenario> scenarios = new LinkedHashMap<>();
        boolean inGapTable = false;
        for (String line : Files.readAllLines(doc, StandardCharsets.UTF_8)) {
            // §23 lists GAP rows in the same table shape; stop before it.
            if (line.startsWith("## 23.")) inGapTable = true;
            if (inGapTable) continue;
            Matcher m = CATALOGUE_ROW.matcher(line);
            if (m.matches()) {
                String id = m.group(1) + "-" + m.group(2);
                scenarios.put(id, new Scenario(id, m.group(4), m.group(3).trim()));
            }
        }
        return scenarios;
    }

    private Map<String, TestCase> readTests() throws IOException {
        Path testRoot = Path.of("src/test/java/com/schoolsoft/certification");
        Map<String, TestCase> tests = new TreeMap<>();
        try (Stream<Path> files = Files.walk(testRoot)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                Matcher m = TEST_METHOD.matcher(source);
                while (m.find()) {
                    String id = m.group(1) + "-" + m.group(2);
                    String annotations = source.substring(annotationBlockStart(source, m.start()), m.start());
                    Matcher tag = PRIORITY_TAG.matcher(annotations);
                    Matcher disabled = DISABLED_REASON.matcher(annotations);
                    TestCase existing = tests.put(id, new TestCase(
                        id,
                        tag.find() ? tag.group(1) : "untagged",
                        m.group(0),
                        file.getFileName().toString(),
                        disabled.find() ? normalise(disabled.group(1)) : null
                    ));
                    assertThat(existing)
                        .describedAs("scenario %s is claimed by more than one test", id)
                        .isNull();
                }
            }
        }
        return tests;
    }

    /** Walks back to the blank line before a method's annotation block. */
    private int annotationBlockStart(String source, int methodStart) {
        int blankLine = source.lastIndexOf("\n\n", methodStart);
        return blankLine < 0 ? 0 : blankLine;
    }

    private String normalise(String annotationLiteral) {
        return annotationLiteral
            .replaceAll("\"\\s*\\+\\s*\"", "")     // re-join a split string literal
            .replaceAll("\\s+", " ")
            .trim();
    }

    // ----------------------------------------------------------------- output

    private void writeStatusReport(Map<String, Scenario> catalogue, Map<String, TestCase> tests) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("# Certification status");
        lines.add("");
        lines.add("Generated by `CatalogueSyncTest` — do not edit by hand.");
        lines.add("");

        long executable = tests.values().stream().filter(t -> t.disabledReason() == null).count();
        lines.add("| | Scenarios |");
        lines.add("|---|---|");
        lines.add("| In catalogue | " + catalogue.size() + " |");
        lines.add("| Executable today | " + executable + " |");
        lines.add("| Disabled (remaining work) | " + (tests.size() - executable) + " |");
        lines.add("");

        lines.add("## Executable");
        lines.add("");
        lines.add("| ID | Priority | Scenario |");
        lines.add("|----|----------|----------|");
        catalogue.forEach((id, scenario) -> {
            if (tests.get(id).disabledReason() == null) {
                lines.add("| " + id + " | " + scenario.priority() + " | " + scenario.summary() + " |");
            }
        });
        lines.add("");

        lines.add("## Disabled");
        lines.add("");
        lines.add("| ID | Priority | Blocked by |");
        lines.add("|----|----------|-----------|");
        catalogue.forEach((id, scenario) -> {
            String reason = tests.get(id).disabledReason();
            if (reason != null) {
                lines.add("| " + id + " | " + scenario.priority() + " | " + reason + " |");
            }
        });

        // Two copies on purpose: target/ is the CI artifact, docs/ is the
        // committed snapshot people read without downloading a build.
        Path artifact = Path.of("target/certification-status.md");
        Files.createDirectories(artifact.getParent());
        Files.write(artifact, lines, StandardCharsets.UTF_8);
        Files.write(repoRoot().resolve("docs/certification-status.md"), lines, StandardCharsets.UTF_8);
    }

    private Path repoRoot() {
        // Surefire runs with apps/api as the working directory.
        return Path.of("").toAbsolutePath().getParent().getParent();
    }
}
