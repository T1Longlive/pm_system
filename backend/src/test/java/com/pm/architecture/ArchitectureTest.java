package com.pm.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 架构守护测试：用可执行的规则固定分层约定，防止后续改动悄悄破坏架构。
 *
 * <p>只校验「依赖方向」这类确定性的结构约束，不校验具体实现细节。
 * 规则失败时 ArchUnit 会打印完整依赖链路，直接指向违规类。
 */
class ArchitectureTest {

    /**
     * 只导入主代码（target/classes）：
     * importPackages 会连同 classpath 上的 test-classes 一起扫描，而测试代码（如切片测试装配）
     * 本就需要 mock Mapper，不能纳入生产分层约束，否则规则会被测试代码误伤。
     */
    private static final JavaClasses CLASSES = new ClassFileImporter()
            .importPackages("com.pm")
            .that(DescribedPredicate.describe("生产代码（排除 test-classes）",
                    javaClass -> javaClass.getSource()
                            .map(source -> !source.getUri().toString().contains("test-classes"))
                            .orElse(true)));

    @Test
    @DisplayName("Controller 不得直接依赖 Mapper（必须经 Service 层）")
    void controllersMustNotDependOnMappers() {
        noClasses()
                .that().resideInAPackage("com.pm.controller..")
                .should().dependOnClassesThat().resideInAPackage("com.pm.mapper..")
                .because("Controller 只负责协议转换与参数校验，数据访问统一走 Service")
                .check(CLASSES);
    }

    @Test
    @DisplayName("Service 不得反向依赖 Controller")
    void servicesMustNotDependOnControllers() {
        noClasses()
                .that().resideInAPackage("com.pm.service..")
                .should().dependOnClassesThat().resideInAPackage("com.pm.controller..")
                .because("依赖方向必须自上而下：controller → service → mapper")
                .check(CLASSES);
    }

    @Test
    @DisplayName("Entity/DTO/VO 不得依赖 Controller/Service/Mapper（领域模型保持纯净）")
    void domainModelsMustNotDependOnUpperLayers() {
        noClasses()
                .that().resideInAnyPackage("com.pm.entity..", "com.pm.dto..", "com.pm.vo..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("com.pm.controller..", "com.pm.service..", "com.pm.mapper..")
                .because("实体与传输对象只承载数据，不感知业务流程与持久化")
                .check(CLASSES);
    }

    @Test
    @DisplayName("Mapper 不得依赖 Service/Controller")
    void mappersMustNotDependOnUpperLayers() {
        noClasses()
                .that().resideInAPackage("com.pm.mapper..")
                .should().dependOnClassesThat().resideInAnyPackage("com.pm.service..", "com.pm.controller..")
                .because("数据访问层只依赖实体，不能反向调用业务层")
                .check(CLASSES);
    }
}
