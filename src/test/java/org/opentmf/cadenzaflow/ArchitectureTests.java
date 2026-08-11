package org.opentmf.cadenzaflow;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaParameter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.library.GeneralCodingRules;
import com.tngtech.archunit.library.ProxyRules;
import java.lang.annotation.Annotation;
import java.util.List;
import org.slf4j.Logger;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.MatrixVariable;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Architecture-discipline tests (pattern donor: dnms-store). Baseline coding rules,
 * logging and web-binding discipline, proxy self-call guards, and identity-plugin
 * containment.
 */
@AnalyzeClasses(
    packagesOf = OpenTmfCadenzaFlowApplication.class,
    importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTests {

  // ---------------------------------------------------------------------------
  // General coding rules
  // ---------------------------------------------------------------------------

  @ArchTest
  static final ArchRule noClasses_shouldAccessStandardStreams =
      GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;

  @ArchTest
  static final ArchRule noClasses_shouldThrowGenericExceptions =
      GeneralCodingRules.NO_CLASSES_SHOULD_THROW_GENERIC_EXCEPTIONS;

  @ArchTest
  static final ArchRule noClasses_shouldUseFieldInjection =
      GeneralCodingRules.NO_CLASSES_SHOULD_USE_FIELD_INJECTION;

  // ---------------------------------------------------------------------------
  // Logging discipline: one facade (SLF4J) and one field name, so a log
  // statement reads the same in every file
  // ---------------------------------------------------------------------------

  @ArchTest
  static final ArchRule noClasses_shouldUseJavaUtilLogging =
      GeneralCodingRules.NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;

  @ArchTest
  static final ArchRule noClasses_shouldUseJodatime =
      GeneralCodingRules.NO_CLASSES_SHOULD_USE_JODATIME;

  @ArchTest
  static final ArchRule noClasses_shouldDependOn_bannedLoggingBackends =
      noClasses()
          .should().dependOnClassesThat()
          .resideInAnyPackage(
              "org.apache.logging.log4j..",
              "org.jboss.logging..",
              "org.testcontainers.shaded..")
          .because(
              "SLF4J is the only logging facade, and shaded Testcontainers types are"
                  + " an implementation detail of that library, not an API");

  @ArchTest
  static final ArchRule loggerFields_shouldBeNamed_log =
      fields()
          .that().haveRawType(Logger.class)
          .should().haveName("log")
          .because("a single name keeps log statements greppable across the codebase");

  // ---------------------------------------------------------------------------
  // Web binding: the build does not pass -parameters, so a binding annotation
  // without an explicit name resolves to nothing at runtime
  // ---------------------------------------------------------------------------

  @ArchTest
  static final ArchRule requestBindingAnnotations_shouldDeclareAnExplicitName =
      methods()
          .should(declareExplicitNamesOnBindingAnnotations())
          .because(
              "without -parameters the parameter name is erased, so an unnamed"
                  + " @PathVariable/@RequestParam binds to nothing");

  // ---------------------------------------------------------------------------
  // Proxy self-call rules: a direct self-call bypasses the Spring proxy, so the
  // annotation silently does nothing
  // ---------------------------------------------------------------------------

  @ArchTest
  static final ArchRule noClasses_shouldDirectlySelfCall_Cacheable =
      ProxyRules
          .no_classes_should_directly_call_other_methods_declared_in_the_same_class_that_are_annotated_with(
              Cacheable.class);

  @ArchTest
  static final ArchRule noClasses_shouldDirectlySelfCall_Transactional =
      ProxyRules
          .no_classes_should_directly_call_other_methods_declared_in_the_same_class_that_are_annotated_with(
              Transactional.class);

  // ---------------------------------------------------------------------------
  // Identity-provider containment: the rest of the application stays
  // provider-agnostic; only the two plugin adapters in config touch the
  // provider-specific identity libraries
  // ---------------------------------------------------------------------------

  @ArchTest
  static final ArchRule identityProviderLibraries_stayInside_configAdapters =
      noClasses()
          .that().resideOutsideOfPackage("org.opentmf.cadenzaflow.config")
          .should().dependOnClassesThat()
          .resideInAnyPackage(
              "org.cadenzaflow.bpm.extension.keycloak..",
              "org.cadenzaflow.bpm.extension.entra..");

  // ---------------------------------------------------------------------------
  // Support
  // ---------------------------------------------------------------------------

  private static final List<Class<? extends Annotation>> REQUEST_BINDING_ANNOTATIONS =
      List.of(
          PathVariable.class,
          RequestParam.class,
          RequestHeader.class,
          CookieValue.class,
          MatrixVariable.class);

  private static ArchCondition<JavaMethod> declareExplicitNamesOnBindingAnnotations() {
    return new ArchCondition<>("declare an explicit name on every request-binding annotation") {
      @Override
      public void check(JavaMethod method, ConditionEvents events) {
        for (JavaParameter parameter : method.getParameters()) {
          for (Class<? extends Annotation> annotationType : REQUEST_BINDING_ANNOTATIONS) {
            if (parameter.isAnnotatedWith(annotationType)
                && !declaresName(parameter.getAnnotationOfType(annotationType))) {
              events.add(
                  SimpleConditionEvent.violated(
                      method,
                      "%s declares @%s without a name in %s"
                          .formatted(
                              method.getFullName(),
                              annotationType.getSimpleName(),
                              method.getSourceCodeLocation())));
            }
          }
        }
      }
    };
  }

  /** True when either the {@code name} or the {@code value} alias carries a non-empty name. */
  private static boolean declaresName(Annotation annotation) {
    return !attribute(annotation, "name").isEmpty() || !attribute(annotation, "value").isEmpty();
  }

  private static String attribute(Annotation annotation, String name) {
    try {
      return (String) annotation.annotationType().getMethod(name).invoke(annotation);
    } catch (ReflectiveOperationException | ClassCastException e) {
      return "";
    }
  }
}
