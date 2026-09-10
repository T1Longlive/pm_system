package com.pm.service;

import com.pm.entity.Requirement;
import com.pm.vo.RequirementGroupAggVO;

import java.time.LocalDate;
import java.util.List;

/**
 * 需求阶段派生服务（状态派生化重构核心）：
 * 排期数据 + 日期 = 唯一真相源，需求 status 是它的实时投影（stage 纯函数派生）；
 * 仅归档/取消是人工落库决定（ARCHIVED/CANCELLED 不参与派生，由需求模块直写）。
 * 所有排期写路径（保存行/移除/加入月表/复制下月/参与人增删）末尾统一调用 {@link #syncStage} 对账，
 * 定时任务调用 {@link #syncAllStages} 捕捉纯时间流逝导致的变化
 */
public interface RequirementStageService {

    /**
     * 阶段派生纯函数（无副作用，只算不写）：
     * 已取消 → CANCELLED；已归档 → ARCHIVED；
     * 无非 VOID 排期行 或 存在未排满排期组（无工期/工期≤0/跨月格子合计<工期） → PENDING；
     * 开发窗口未结束（存在非 QA 行且「每行有格子且最大非 QA 格子日 ≥ 今天」） → DEV；
     * 测试窗口未结束（存在行且「每行有格子且最大格子日 ≥ 今天」，含 QA 行） → TEST；
     * 否则（全部行的格子日都已是过去） → ARCHIVING
     *
     * @param req     需求实体（读取 status 判断人工终态）
     * @param groups  该需求全部排期组聚合数据（无任何组 = 无非 VOID 行）
     * @param today   当前日期（驱动测试/归档窗口判定）
     */
    String deriveStage(Requirement req, List<RequirementGroupAggVO> groups, LocalDate today);

    /**
     * 单需求对账（排期写路径末尾调用）：按排期数据派生阶段，与库中 status 不一致则更新——
     * 写 STAGE_SYNC 日志（operatorId 为空时不写日志）并通知全部参与人；返回是否实际变更。
     * 终态（ARCHIVED/CANCELLED）不参与对账；并发保护：条件更新（eq 旧状态），失败即放弃本次对账
     */
    boolean syncStage(Long requirementId, Long operatorId);

    /**
     * 全量对账（定时任务用）：扫描全部非终态需求，逐个按排期数据+日期派生并对账，
     * 返回实际变更的需求数（定时任务触发，不写操作日志，仅通知）
     */
    int syncAllStages();
}
