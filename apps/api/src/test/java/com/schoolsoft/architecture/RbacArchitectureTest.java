package com.schoolsoft.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.schoolsoft.platform.security.Perm;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The drift guard on authorization.
 *
 * <p>Every one of these rules exists because of the same failure: an endpoint
 * that nothing guards. Before {@code V026__role_perms.sql} the API had 29
 * controllers and no {@code @PreAuthorize} at all — {@code .anyRequest()
 * .authenticated()} was the whole model, so a guardian's token could grant
 * itself the {@code it_admin} role. A rule that fails the build is the only
 * thing that keeps the 269th endpoint from being the next one.</p>
 *
 * <p>Runs in the {@code harness} group, which is part of the blocking CI
 * gate — a structural rule that only runs when somebody remembers to run it
 * is not a rule.</p>
 */
@Tag("harness")
class RbacArchitectureTest {

    private static final JavaClasses CONTROLLERS = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_JARS)
            .importPackages("com.schoolsoft");

    private static final String PRE_AUTHORIZE = "org.springframework.security.access.prepost.PreAuthorize";

    private static final List<String> HTTP_MAPPINGS = List.of(
            "org.springframework.web.bind.annotation.GetMapping",
            "org.springframework.web.bind.annotation.PostMapping",
            "org.springframework.web.bind.annotation.PutMapping",
            "org.springframework.web.bind.annotation.PatchMapping",
            "org.springframework.web.bind.annotation.DeleteMapping",
            "org.springframework.web.bind.annotation.RequestMapping");

    /**
     * The grammar of a gate. Anything else — {@code hasAuthority(...)}, a bare
     * {@code true}, a hand-rolled SpEL expression — fails, because the point of
     * the {@link Perm} vocabulary is that the set of gates is enumerable.
     *
     * <p>{@code hasRole('PLATFORM_ADMIN')} is the one role literal allowed: a
     * platform admin is not a tenant role and holds no {@code staff_role} row,
     * so it cannot be expressed as a permission.</p>
     */
    private static final Pattern ALLOWED = Pattern.compile(
            "^(?:@perm\\.can\\('[a-z0-9_.]+'\\)"
          + "|@perm\\.canAny\\('[a-z0-9_.]+'(?:,\\s*'[a-z0-9_.]+')+\\)"
          + "|@perm\\.canAnyOf\\('[a-z0-9_.]+',\\s*'[a-z0-9_.]+\\.own'\\)"
          + "|isAuthenticated\\(\\)"
          + "|permitAll\\(\\)"
          + "|hasRole\\('PLATFORM_ADMIN'\\))$");

    private static final Pattern PERM_CODE = Pattern.compile("'([a-z0-9_.]+)'");

    /**
     * Paths the security filter chain serves without a token
     * ({@code SecurityConfig} permitAll + {@code TenantResolverFilter}
     * shouldNotFilter). {@code permitAll()} is legal only on a method whose
     * controller sits under one of these; anywhere else it is a hole.
     */
    private static final List<String> PUBLIC_PREFIXES =
            List.of("/v1/auth/", "/v1/public/", "/v1/webhooks/");

    @Test
    @DisplayName("every HTTP-mapped controller method declares @PreAuthorize")
    void every_mapped_method_is_gated() {
        var ungated = mappedMethods()
                .filter(m -> !m.isAnnotatedWith(PRE_AUTHORIZE))
                .map(RbacArchitectureTest::describe)
                .sorted()
                .toList();

        assertThat(ungated)
                .as("HTTP endpoints with no @PreAuthorize — any authenticated token can call these")
                .isEmpty();
    }

    @Test
    @DisplayName("@PreAuthorize values use the permission vocabulary, not ad-hoc SpEL")
    void gates_use_the_vocabulary() {
        var bad = mappedMethods()
                .filter(m -> m.isAnnotatedWith(PRE_AUTHORIZE))
                .filter(m -> !ALLOWED.matcher(gateOf(m)).matches())
                .map(m -> describe(m) + " -> " + gateOf(m))
                .sorted()
                .toList();

        assertThat(bad)
                .as("gates that bypass the Perm vocabulary")
                .isEmpty();
    }

    @Test
    @DisplayName("every permission code named in a gate exists in Perm")
    void gate_codes_resolve() {
        var unknown = new TreeSet<String>();
        mappedMethods()
                .filter(m -> m.isAnnotatedWith(PRE_AUTHORIZE))
                .forEach(m -> {
                    for (String code : codesIn(gateOf(m))) {
                        if (Perm.byCode(code).isEmpty()) unknown.add(code + "  (" + describe(m) + ")");
                    }
                });

        assertThat(unknown)
                .as("gates naming a permission that Perm does not define — these always deny")
                .isEmpty();
    }

    @Test
    @DisplayName("every permission code granted in a migration exists in Perm")
    void granted_codes_resolve() throws IOException {
        var unknown = new TreeSet<String>();
        for (Path sql : migrations()) {
            String body = stripComments(Files.readString(sql));
            if (!body.contains("role_perm")) continue;
            Matcher m = PERM_CODE.matcher(body);
            while (m.find()) {
                String code = m.group(1);
                // Role codes and column names are quoted the same way; only
                // strings shaped like a permission (dotted) are candidates.
                if (code.contains(".") && Perm.byCode(code).isEmpty()) {
                    unknown.add(code + "  (" + sql.getFileName() + ")");
                }
            }
        }

        assertThat(unknown)
                .as("permissions granted in SQL that Perm no longer defines — dead grants")
                .isEmpty();
    }

    @Test
    @DisplayName("every permission in Perm is granted to somebody or gates something")
    void no_orphan_permissions() throws IOException {
        Set<String> gated = new LinkedHashSet<>();
        mappedMethods()
                .filter(m -> m.isAnnotatedWith(PRE_AUTHORIZE))
                .forEach(m -> gated.addAll(codesIn(gateOf(m))));

        var orphans = Stream.of(Perm.values())
                .map(Perm::code)
                .filter(code -> !gated.contains(code))
                .sorted()
                .toList();

        assertThat(orphans)
                .as("permissions no endpoint checks — a permission nobody enforces is a false promise "
                  + "to whoever granted it")
                .isEmpty();
    }

    @Test
    @DisplayName("permitAll() appears only on controllers the filter chain serves anonymously")
    void permit_all_only_where_the_chain_agrees() {
        var bad = mappedMethods()
                .filter(m -> m.isAnnotatedWith(PRE_AUTHORIZE))
                .filter(m -> "permitAll()".equals(gateOf(m)))
                .filter(m -> PUBLIC_PREFIXES.stream().noneMatch(p -> basePathOf(m).startsWith(p)))
                .map(RbacArchitectureTest::describe)
                .sorted()
                .toList();

        assertThat(bad)
                .as("permitAll() on an authenticated path — the annotation opens what the chain closed")
                .isEmpty();
    }

    /**
     * {@code canAnyOf(staff, own)} is the promise that the handler narrows the
     * read to the caller. The narrowing lives in {@code SelfScope}, so a
     * controller that makes the promise must hold one.
     */
    @Test
    @DisplayName("a controller gating on a .own permission holds a SelfScope")
    void self_scoped_gates_have_a_scoper() {
        var missing = CONTROLLERS.stream()
                .filter(c -> c.getName().endsWith("Controller"))
                .filter(c -> c.getMethods().stream()
                        .anyMatch(m -> m.isAnnotatedWith(PRE_AUTHORIZE) && gateOf(m).contains("canAnyOf")))
                .filter(c -> c.getAllFields().stream()
                        .noneMatch(f -> f.getRawType().getName().endsWith("SelfScope")))
                .map(c -> c.getSimpleName())
                .sorted()
                .toList();

        assertThat(missing)
                .as("controllers that accept a .own permission but cannot narrow the read to the caller")
                .isEmpty();
    }

    // ===== helpers =====

    private static Stream<JavaMethod> mappedMethods() {
        return CONTROLLERS.stream()
                .filter(c -> c.isAnnotatedWith("org.springframework.web.bind.annotation.RestController"))
                .flatMap(c -> c.getMethods().stream())
                .filter(m -> HTTP_MAPPINGS.stream().anyMatch(m::isAnnotatedWith));
    }

    private static String gateOf(JavaMethod m) {
        return (String) m.getAnnotationOfType(PRE_AUTHORIZE).get("value").orElse("");
    }

    private static String basePathOf(JavaMethod m) {
        var owner = m.getOwner();
        if (!owner.isAnnotatedWith("org.springframework.web.bind.annotation.RequestMapping")) return "";
        Object value = owner.getAnnotationOfType("org.springframework.web.bind.annotation.RequestMapping")
                .get("value").orElse(new String[0]);
        String[] paths = (String[]) value;
        return paths.length == 0 ? "" : paths[0] + "/";
    }

    private static List<String> codesIn(String gate) {
        var out = new ArrayList<String>();
        Matcher m = PERM_CODE.matcher(gate);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    /** SQL line comments quote permission codes when they explain them; only the statements count. */
    private static String stripComments(String sql) {
        return sql.lines()
                .map(line -> {
                    int c = line.indexOf("--");
                    return c < 0 ? line : line.substring(0, c);
                })
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private static List<Path> migrations() throws IOException {
        Path dir = Path.of("src/main/resources/db/migration/chain");
        try (var files = Files.list(dir)) {
            return files.filter(p -> p.toString().endsWith(".sql")).sorted().toList();
        }
    }

    private static String describe(JavaMethod m) {
        return m.getOwner().getSimpleName() + "#" + m.getName();
    }
}
