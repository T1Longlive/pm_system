package com.pm.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pm.dto.BugCreateDTO;
import com.pm.dto.BugImageAddDTO;
import com.pm.dto.BugUpdateDTO;
import com.pm.vo.BugListVO;

import java.util.List;

/**
 * Bug 管理服务（Excel 化编辑模型）：字段在表格单元格内直接编辑，
 * 所有登录用户可编辑未归档行；归档/重新打开限项目经理或管理员；删除所有人可用。
 * 处理进度：空=未处理/DONE已处理/HOLD暂不处理/SKIP不处理；
 * 验收进度：空=未验收/PASSED验收通过/REJECTED验收不通过
 */
public interface BugService {

    /**
     * Bug 分页列表（按项目查询，archived=0 总表 / 1 归档表）。
     * keyword 模糊匹配功能模块或编号；size 上限 1000（一页全量 Excel 模式）
     */
    Page<BugListVO> pageBugs(Long userId, Long projectId, Integer archived, String keyword, long page, long size);

    /**
     * 创建 Bug（任何登录用户）：最小行——编号自动生成（BUG-{项目缩写}-{4位流水}），
     * 测试人员=当前用户，其余字段留空；返回新行完整 VO（前端直接插入列表顶部）
     */
    BugListVO createBug(Long userId, BugCreateDTO dto);

    /**
     * 编辑 Bug（任何登录用户，仅未归档行）：全字段可空部分更新；
     * 清空哨兵：null=不修改，字符串 ""=清空，数值/ID 0=清空
     */
    void updateBug(Long userId, Long bugId, BugUpdateDTO dto);

    /**
     * 归档 Bug（项目经理或管理员，无状态限制）：置 archived=1 + 归档时间/人
     */
    void archiveBug(Long userId, Long bugId);

    /**
     * 重新打开 Bug（项目经理或管理员，仅已归档）：回总表并置空处理进度与验收进度
     */
    void reopenBug(Long userId, Long bugId);

    /**
     * 删除 Bug（任何登录用户，归档行也可删）：物理删除行并连带删除截图附件记录
     */
    void deleteBug(Long userId, Long bugId);

    /**
     * 批量追加问题截图（任何登录用户，未归档行）：
     * 落 attachment 表（biz_type=BUG），返回新附件 id 列表（与入参顺序一致）
     */
    List<Long> addImages(Long userId, Long bugId, BugImageAddDTO dto);

    /**
     * 删除单张问题截图（任何登录用户，未归档行；校验附件归属）
     */
    void deleteImage(Long userId, Long bugId, Long attachmentId);
}
