package com.pm.service;

import com.pm.dto.ScheduleMonthCreateDTO;
import com.pm.dto.ScheduleRequirementDTO;
import com.pm.dto.ScheduleRowSaveDTO;
import com.pm.vo.CopyRowResultVO;
import com.pm.vo.ScheduleMonthVO;
import com.pm.vo.ScheduleRowSaveResultVO;
import com.pm.vo.ScheduleViewVO;

import java.util.List;

/**
 * 排期管理服务：月度排期表 / 排期行生成 / 排期矩阵填写 / 跨月顺延 / 节点完成与需求状态推进
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
     * 把需求加入月表（仅该需求项目的 PM）：需求必须为 PENDING 待排期状态，
     * 按需求参与人生成排期行（每行 工种×负责人 唯一），返回生成行数；需求状态保持 PENDING（全部行排满后转 READY）
     */
    Integer addRequirement(Long userId, Long monthId, ScheduleRequirementDTO dto);

    /**
     * 保存排期行（仅项目 PM）：全量替换格子与计划工期（非 VOID 行随时可编辑）。
     * 校验：行非 VOID、乐观锁、格子归属月/可排班日/数值步进、同人同日合计≤1；
     * 跨月顺延检查（当月排不下剩余工期时自动建下月表并追加 is_overflow 行）；
     * 全部非 VOID 行排满时触发需求 PENDING→READY（通知全部参与人）并写 SCHEDULE_SAVE 日志；
     * 格子变化时通知行负责人 SCHEDULE_CHANGED
     */
    ScheduleRowSaveResultVO saveRow(Long userId, Long rowId, ScheduleRowSaveDTO dto);

    /**
     * 复制排期行到下月（仅项目 PM，VOID 行不可复制）：下月表不存在自动创建（created_by=操作人）；
     * 插入同需求/工种/负责人新行（工期带原值，status=TODO，is_overflow=0）；
     * 幂等：下月已存在同键行则不重复插入；返回下月表 ID 与是否新插入
     */
    CopyRowResultVO copyRowToNextMonth(Long userId, Long rowId);

    /**
     * 需求状态推进定时任务（每小时，全部日期驱动）：
     * READY→DEV（任一非 VOID 行存在格子日≤今天，不通知）；
     * DEV→TEST（全部非 QA 非 VOID 行格子日<今天，通知）；
     * TEST→ARCHIVING（全部非 VOID 行格子日<今天，通知）；返回本次推进的需求数
     */
    int advanceBySchedule();
}
