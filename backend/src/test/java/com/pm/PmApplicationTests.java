package com.pm;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 上下文加载冒烟测试。
 *
 * <p>⚠️ <b>已 @Disabled：它需要真实 MySQL，云端构建机上跑不了。</b>
 *
 * <p>{@code @SpringBootTest} 会加载完整 Spring 上下文，其中数据源、MyBatis-Plus、
 * Redis 都要真实可连。云端构建环境没有这些，测试必然失败 —— 这正是当初不得不在
 * 打包命令里加 {@code -DskipTests} 的原因，而那等于把整个测试门禁关掉了。
 *
 * <p>现在的分工是：
 * <ul>
 *   <li><b>云端流水线</b>只跑不依赖外部资源的纯单元测试
 *       （{@code RequirementStageServiceImplTest}、{@code JwtUtilTest}），
 *       测试失败 → 镜像构建失败 → 部署被 {@code dependsOn} 挡住；</li>
 *   <li><b>本地开发</b>需要验证上下文能起来时，临时去掉 {@code @Disabled} 再跑 ——
 *       本地有 MySQL/Redis 才能过。</li>
 * </ul>
 *
 * <p>想让它也能在云端跑，需要引入 Testcontainers 或 H2 内存库并准备测试用建表脚本 ——
 * 那是一件事，不是顺手能做的。等确实需要"端到端冒烟"时再单独做。
 */
@Disabled("需要真实 MySQL/Redis，云端构建环境不具备；见类注释说明")
@SpringBootTest
class PmApplicationTests {

    @Test
    void contextLoads() {
    }
}
