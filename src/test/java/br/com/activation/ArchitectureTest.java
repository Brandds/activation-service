package br.com.activation;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class ArchitectureTest {
    @Test void domainAndUseCasesDoNotDependOnFrameworksOrAdapters() {
        var classes = new ClassFileImporter().importPackages("br.com.activation");
        noClasses().that().resideInAnyPackage("..domain..", "..application..")
                .should().dependOnClassesThat().resideInAnyPackage("org.springframework..", "jakarta..", "..infrastructure..")
                .check(classes);
        noClasses().that().resideInAPackage("..domain..").should().dependOnClassesThat()
                .resideInAPackage("..application..").check(classes);
    }
}
