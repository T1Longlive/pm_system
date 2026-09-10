package com.pm.service;

import com.pm.dto.ScheduleMonthCreateDTO;
import com.pm.dto.ScheduleRequirementDTO;
import com.pm.dto.ScheduleRowSaveDTO;
import com.pm.vo.CopyRowResultVO;
import com.pm.vo.ScheduleIncompleteVO;
import com.pm.vo.ScheduleMonthVO;
import com.pm.vo.ScheduleRowSaveResultVO;
import com.pm.vo.ScheduleViewVO;

import java.util.List;

/**
 * 排期管理服务：月度排期表 / 排期行生成 / 排期矩阵填写 / 跨月顺延 / 需求状态对账触发
 * 需求状态为派生值：本服务全部写路径末尾统一调 RequirementStageService.syncStage 对账，
 * 本服务不直接写需求 status（归档/取消由需求模块人工落库）
 */
public interface ScheduleService {

    /**
     * 月表列表（按年月升序，任何登录用户可看）
     */
    List<ScheduleMonthVO> listMonths();

    /**
     * 创建月度排期表（管理员或任意项目 PM）：已存在则 400「该月排期表已存在」，返回新建月表
     */
    ScheduleMonthVO createMonth(Long userId, ScheduleMonthCreateDTO dto);

    /**
     * 月表排期视图（全局可见，任何登录用户可看）：月份 + 整月日历 + 按需求分组的排期行（VOID 行也返回）
     */
    ScheduleViewVO getMonthView(Long monthId);

    /**
     * 把需求加入月表（仅该需求项目的 PM）：需求必须为非终态（未归档未取消），
     * 按需求参与人生成排期行（每行 工种×负责人 唯一），返回生成行数；
     * 生成后按排期数据对账需求状态（工期跨月继承，已排统计跨月动态求和）
     */
    Integer addRequirement(Long userId, Long monthId, ScheduleRequirementDTO dto);

    /**
     * 把需求从月表移除（仅该需求项目的 PM）：删除该需求在该月的全部排期行及其格子
     * （其他月份的行不受影响）；移除后按剩余排期数据对账需求状态
     * （全部月份无行 → 待排期；部分删除导致组不再排满 → 派生自动回退）；
     * 写 SCHEDULE_REMOVE 日志并通知参与人；返回删除的行数
     */
    Integer removeRequirement(Long userId, Long monthId, Long requirementId);

    /**
     * 保存排期行（仅项目 PM）：全量替换格子与计划工期（终态需求只读，VOID 行拒绝编辑）。
     * 校验：乐观锁、格子归属月/可排班日/数值步进、组内跨月合计≤工期、同人同日合计≤1；
     * 跨月顺延检查（当月排不下剩余工期时自动建下月表并追加 is_overflow 行）；
     * 保存后按「排期数据+日期」对账需求状态（排满自动推进、不再排满自动回退）；
     * 格子变化时通知行负责人 SCHEDULE_CHANGED
     */
    ScheduleRowSaveResultVO saveRow(Long userId, Long rowId, ScheduleRowSaveDTO dto);

    /**
     * 整个需求添加到下月（仅项目 PM，行所在需求为准；终态需求拒绝）：
     * 下月表不存在自动创建（created_by=操作人）；
     * 该需求全部排期组（工种×负责人，跨全部月份去重）逐组插入下月新行（格子空白、工期带原值跨月共用，
     * status=TODO，is_overflow=0）；幂等：下月已存在的组跳过；返回下月表 ID、是否有新插入、插入行数
     */
    CopyRowResultVO copyRequirementToNextMonth(Long userId, Long rowId);

    /**
     * 排期未完成的需求列表（前端排期界面退出拦截用），仅返回当前用户可修复的需求——
     * 即当前用户为项目 PM（项目创建者）；口径：非终态且排期组未排满、
     * 且存在已开始的开发工种（格子日≤今天，开发并行节点黄/绿）的需求——
     * 开发已开始不可回退，须强制完成排期后才能离开排期界面（不依赖需求状态字段，PENDING 同样拦截）
     */
    List<ScheduleIncompleteVO> listIncompleteRequirements(Long userId);
}
