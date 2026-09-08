package com.pm.common;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工种常量：合法工种集合与中文名映射，供项目成员管理校验使用。
 * 注意：PM 不是工种，项目创建者自动担任该项目项目经理，不写入 project_member.craft
 */
public final class CraftConstants {

    /** 策划 */
    public static final String PLANNER = "PLANNER";

    /** 前端开发 */
    public static final String FE = "FE";

    /** 后端开发 */
    public static final String BE = "BE";

    /** 运维 */
    public static final String OPS = "OPS";

    /** 测试 */
    public static final String QA = "QA";

    /** UI */
    public static final String UI = "UI";

    /** 全部合法工种集合（保持定义顺序，不可修改） */
    public static final Set<String> ALL_CRAFTS = Collections.unmodifiableSet(
            new LinkedHashSet<>(List.of(PLANNER, FE, BE, OPS, QA, UI)));

    /** 工种代码 -> 中文名映射 */
    public static final Map<String, String> CRAFT_NAMES;

    static {
        Map<String, String> names = new LinkedHashMap<>();
        names.put(PLANNER, "策划");
        names.put(FE, "前端开发");
        names.put(BE, "后端开发");
        names.put(OPS, "运维");
        names.put(QA, "测试");
        names.put(UI, "UI");
        CRAFT_NAMES = Collections.unmodifiableMap(names);
    }

    private CraftConstants() {
    }
}
