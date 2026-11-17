package com.pm.support;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.List;

/**
 * 集成测试基类：真实 MySQL 8 + Redis 7 容器，不依赖开发者本机环境。
 *
 * <p>设计取舍：
 * <ul>
 *   <li><b>不用 H2</b>：项目里有 {@code CAST(RIGHT(...) AS UNSIGNED)}、{@code DATE_FORMAT}
 *       等 MySQL 方言，H2 跑出来的绿是假绿；</li>
 *   <li><b>不用 testcontainers-junit-jupiter 扩展</b>：容器在静态块中手动启动，
 *       避免 JUnit 6 与扩展的版本兼容问题；</li>
 *   <li><b>容器按 JVM 共享</b>：静态字段只启动一次，多个 IT 类复用，减少启动开销。</li>
 * </ul>
 *
 * <p>⚠️ 命名约定：集成测试类必须以 {@code IT} 结尾，由 maven-failsafe-plugin 在
 * {@code mvn verify} 阶段执行；{@code mvn test} 与镜像构建不会碰它们。
 *
 * <p>⚠️ 本地运行前置条件：Docker Desktop 已启动。未启动时类初始化会失败，
 * 这是预期行为（不要用 @Disabled 掩盖）。
 *
 * <p>Windows 排障：若 PATH 中存在以 {@code \\?\} 开头的条目（部分 IDE/插件会注入），
 * Testcontainers 扫描可执行文件时会抛 {@code InvalidPathException}。
 * 运行前剔除该条目，并显式指定 Docker Desktop 管道：
 * <pre>
 *   $env:PATH = (($env:PATH -split ';') | Where-Object { $_ -notmatch '^\\\\\?\\' }) -join ';'
 *   $env:DOCKER_HOST = 'npipe:////./pipe/dockerDesktopLinuxEngine'
 * </pre>
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    protected static final MySQLContainer<?> MYSQL;
    protected static final GenericContainer<?> REDIS;

    static {
        MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
                .withDatabaseName("pm_system")
                // 与 init.sql 同源建表，避免测试 schema 与生产漂移
                .withInitScript("db/init.sql");
        MYSQL.start();

        REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                .withExposedPorts(6379);
        REDIS.start();
    }

    @DynamicPropertySource
    static void registerContainerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);

        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "");

        // 附件落临时目录，测试结束由系统回收
        registry.add("pm.upload-dir",
                () -> System.getProperty("java.io.tmpdir") + "/pm-upload-test");
    }

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    /**
     * 清空业务表（无物理外键，顺序不敏感；保留 calendar_day，启动时已预生成）。
     * 每个集成测试在造数前调用，保证用例之间互不影响。
     */
    protected void cleanBusinessTables() {
        for (String table : List.of("schedule_cell", "schedule_row", "schedule_month",
                "requirement_participant", "attachment", "notification", "biz_operation_log",
                "requirement", "project_member", "project", "bug", "`user`")) {
            jdbcTemplate.execute("DELETE FROM " + table);
        }
    }
}
