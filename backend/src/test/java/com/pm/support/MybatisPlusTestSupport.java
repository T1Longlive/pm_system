package com.pm.support;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import org.apache.ibatis.builder.MapperBuilderAssistant;

/**
 * 纯单元测试里的 MyBatis-Plus 支撑。
 *
 * <p>背景：{@code LambdaUpdateWrapper.set(Entity::getField, value)} 需要先有实体的
 * TableInfo 缓存；该缓存平时由 Spring 启动时 Mapper 扫描建立，不启动上下文就会抛
 * 「can not find lambda cache for this entity」。
 *
 * <p>本工具只做最小初始化，让写路径（条件更新、字段 set）可以在无容器、无数据库的
 * 单测里被验证。集成测试不需要调用它（真实上下文已初始化）。
 */
public final class MybatisPlusTestSupport {

    private MybatisPlusTestSupport() {
    }

    public static void initLambdaCache(Class<?>... entities) {
        MybatisConfiguration configuration = new MybatisConfiguration();
        GlobalConfig globalConfig = GlobalConfigUtils.defaults();
        GlobalConfigUtils.setGlobalConfig(configuration, globalConfig);
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        for (Class<?> entity : entities) {
            TableInfoHelper.initTableInfo(assistant, entity);
        }
    }
}
