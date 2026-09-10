package com.pm.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pm.dto.RequirementCancelDTO;
import com.pm.dto.RequirementCreateDTO;
import com.pm.dto.RequirementUpdateDTO;
import com.pm.vo.OperationLogVO;
import com.pm.vo.RequirementDetailVO;
import com.pm.vo.RequirementListVO;

import java.util.List;

/**
 * 需求管理服务：列表/详情对任何登录用户可见（全局可见性模型），写操作按角色校验。
 * 需求状态为派生值（RequirementStageService 对账）：本模块仅负责创建（初始 PENDING）、编辑、
 * 取消（任意非终态→CANCELLED）、归档（仅 ARCHIVING→ARCHIVED）两个人工落库决定；
 * 其余流转由排期写路径与定时任务统一对账
 */
public interface RequirementService {

    /**
     * 需求分页列表（全局汇总视图：projectId 空=全部项目，任何登录用户可见）。
     * status/priority/plannerId 精确过滤，keyword 模糊匹配标题或编号；
     * excludeTerminal=true 时排除终态（ARCHIVED/CANCELLED，需求池场景：未归档需求一直可选）
     */
    Page<RequirementListVO> pageRequirements(Long userId, Long projectId, String status, Integer priority,
                                             Long plannerId, String keyword, boolean excludeTerminal,
                                             long page, long size);

    /**
     * 需求详情（任何登录用户可看）：基础字段 + description + 参与人平铺 + 附件 + myRole
     */
    RequirementDetailVO getRequirementDetail(Long userId, Long requirementId);

    /**
     * 需求操作日志时间线（id 倒序）
     */
    List<OperationLogVO> listLogs(Long requirementId);

    /**
     * 创建需求（该项目 PM 或 PLANNER 工种成员），初始状态 PENDING，返回新需求 id。
     * req_no = REQ-{项目缩写}-{4位流水}（Redis INCR，缺失时从 DB 回填）；
     * 通知所有被添加参与人 REQ_PARTICIPANT_ADDED
     */
    Long createRequirement(Long userId, RequirementCreateDTO dto);

    /**
     * 编辑需求（项目 PM 或需求创建人）：
     * 乐观锁校验 updatedAt（毫秒时间戳）+ 终态校验 + 参与人 diff + 附件全量替换 + 变更摘要留痕
     */
    void updateRequirement(Long userId, Long requirementId, RequirementUpdateDTO dto);

    /**
     * 取消需求（仅项目 PM，非终态才可取消）：置 CANCELLED + 取消人/时间/原因；
     * 通知全部参与人 + 策划 REQ_CANCELLED（内容含原因）
     */
    void cancelRequirement(Long userId, Long requirementId, RequirementCancelDTO dto);

    /**
     * 归档需求（仅项目 PM，仅 ARCHIVING 状态可归档）：置 ARCHIVED + 归档时间；
     * 通知全部参与人 REQ_ARCHIVED
     */
    void archiveRequirement(Long userId, Long requirementId);

    /**
     * 项目成员移除联动（需求文档 5.4.5）：把目标用户从该项目全部非终态需求的参与人中移除
     * （craft 为空=全部工种，否则仅该工种），联动将其排期行标 VOID、通知本人并按排期数据
     * 对账需求状态；终态需求（已归档/已取消）历史冻结不联动；返回受影响的需求个数
     */
    int removeParticipantByProjectMember(Long projectId, Long targetUserId, String craft, Long operatorId);
}
