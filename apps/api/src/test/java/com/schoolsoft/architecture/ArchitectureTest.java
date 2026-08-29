package com.schoolsoft.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.List;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The module boundaries, as rules rather than as intentions.
 *
 * <p>Every bounded context is {@code com.schoolsoft.<module>} split into
 * {@code api} (what other modules and HTTP may call) and {@code internal}
 * (what only this module may). Spring Modulith's {@code package-info.java}
 * declares the shape; these rules are what makes reaching through it fail the
 * build instead of merely being impolite.</p>
 */
@Tag("harness")
class ArchitectureTest {

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_JARS)
            .importPackages("com.schoolsoft");

    @Test
    @DisplayName("no module reaches into another module's internals")
    void modules_do_not_reach_into_each_others_internals() {
        var violations = new TreeSet<String>();

        for (var clazz : CLASSES) {
            String ownModule = moduleOf(clazz.getPackageName());
            if (ownModule == null) continue;

            for (var dep : clazz.getDirectDependenciesFromSelf()) {
                String target = dep.getTargetClass().getPackageName();
                String targetModule = moduleOf(target);
                if (targetModule == null || targetModule.equals(ownModule)) continue;
                if (target.contains(".internal")) {
                    violations.add(clazz.getSimpleName() + " (" + ownModule + ") -> "
                            + dep.getTargetClass().getSimpleName() + " (" + targetModule + ".internal)");
                }
            }
        }

        assertThat(violations)
                .as("cross-module reads of an internal package — go through the module's api package "
                  + "or an event instead")
                .isEmpty();
    }

    @Test
    @DisplayName("platform depends on no business module")
    void platform_is_beneath_the_modules() {
        noClasses().that().resideInAPackage("com.schoolsoft.platform..")
                .should().dependOnClassesThat(
                        com.tngtech.archunit.base.DescribedPredicate.describe(
                                "reside in a business module",
                                javaClass -> {
                                    String m = moduleOf(javaClass.getPackageName());
                                    return m != null && !m.equals("platform");
                                }))
                .as("platform is the floor every module stands on; a dependency back up inverts that "
                  + "and makes the floor un-testable on its own")
                .allowEmptyShould(true)
                .check(CLASSES);
    }

    @Test
    @DisplayName("controllers live in an api package")
    void controllers_are_public_surface() {
        classes().that().areAnnotatedWith("org.springframework.web.bind.annotation.RestController")
                .should().resideInAPackage("..api..")
                .as("a controller in an internal package is an endpoint nobody browsing the module's "
                  + "published surface will find")
                .allowEmptyShould(true)
                .check(CLASSES);
    }

    /**
     * A transaction is a use-case decision — "these writes land together or
     * none of them do" — and the use case is not the repository. Left where it
     * is today, a repository method that grew a second write silently widened
     * its own transaction, and a caller that needed two repository calls to be
     * atomic could not say so.
     *
     * <p>The existing offenders are listed rather than fixed in one pass;
     * the list only shrinks. Adding to it needs a deliberate edit here.</p>
     */
    @Test
    @DisplayName("@Transactional sits on a use case, not on a repository")
    void transactions_are_declared_at_the_use_case() {
        List<String> knownOffenders = List.of(
                "AttendanceRepository",
                "ExamScheduleRepository",
                "FeesRepository",
                "PublicLookupRepository");

        var offenders = new TreeSet<String>();
        for (var clazz : CLASSES) {
            if (!clazz.getSimpleName().endsWith("Repository")) continue;
            boolean transactional = clazz.isAnnotatedWith("org.springframework.transaction.annotation.Transactional")
                    || clazz.getMethods().stream().anyMatch(m ->
                        m.isAnnotatedWith("org.springframework.transaction.annotation.Transactional"));
            if (transactional && !knownOffenders.contains(clazz.getSimpleName())) {
                offenders.add(clazz.getSimpleName());
            }
        }

        assertThat(offenders)
                .as("new repositories declaring their own transaction — put @Transactional on the "
                  + "service that owns the use case instead")
                .isEmpty();
    }

    /**
     * A refusal is a decision, and a decision buried in a SQL helper is one
     * nobody reviewing "who may do this" will find — which is how the API came
     * to ship with no authorization at all. Role checks belong in something
     * named for them: a {@code *Authorizer}, a use-case service, or a
     * {@code @PreAuthorize}.
     *
     * <p>{@link com.schoolsoft.iam.api.CampusScope} is deliberately not covered:
     * it answers "of what", not "may they", and its result is a {@code WHERE}
     * clause. That belongs next to the SQL, because a list read that forgets to
     * narrow itself is a leak.</p>
     */
    @Test
    @DisplayName("no repository decides authorization")
    void repositories_do_not_authorize() {
        var offenders = new TreeSet<String>();
        for (var clazz : CLASSES) {
            if (!clazz.getSimpleName().endsWith("Repository")) continue;
            boolean readsRoles = clazz.getDirectDependenciesFromSelf().stream()
                .anyMatch(d -> d.getTargetClass().getName().equals("com.schoolsoft.iam.api.Authz"));
            if (readsRoles) offenders.add(clazz.getSimpleName());
        }

        assertThat(offenders)
                .as("repositories reaching for Authz — move the check to an authorizer or the use case")
                .isEmpty();
    }

    /** {@code com.schoolsoft.<module>....} -> {@code <module>}, or null for the root package. */
    private static String moduleOf(String packageName) {
        if (!packageName.startsWith("com.schoolsoft.")) return null;
        String rest = packageName.substring("com.schoolsoft.".length());
        int dot = rest.indexOf('.');
        String head = dot < 0 ? rest : rest.substring(0, dot);
        return head.isEmpty() ? null : head;
    }
}
