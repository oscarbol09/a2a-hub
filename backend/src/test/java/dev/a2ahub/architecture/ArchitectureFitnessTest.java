package dev.a2ahub.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(packages = "dev.a2ahub", importOptions = ImportOption.DoNotIncludeTests.class)
public class ArchitectureFitnessTest {

    @ArchTest
    static final ArchRule servicesShouldNotDependOnControllers = noClasses()
            .that().haveSimpleNameEndingWith("Service")
            .should().dependOnClassesThat().resideInAPackage("..api..");

    @ArchTest
    static final ArchRule controllersShouldOnlyBeAccessedFromWebLayer = classes()
            .that().resideInAPackage("..api..")
            .should().onlyBeAccessed().byClassesThat().resideInAnyPackage("..api..", "dev.a2ahub..");

    @ArchTest
    static final ArchRule securityShouldNotDependOnControllers = noClasses()
            .that().resideInAPackage("..security..")
            .should().dependOnClassesThat().resideInAPackage("..api..");

    @ArchTest
    static final ArchRule repositoriesShouldBeInterfaces = classes()
            .that().haveSimpleNameEndingWith("Repository")
            .should().beInterfaces();

    @ArchTest
    static final ArchRule controllersShouldNotDependOnRepositories = noClasses()
            .that().resideInAPackage("..api..")
            .should().dependOnClassesThat().haveSimpleNameEndingWith("Repository");
}
