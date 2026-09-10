package com.pm.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pm.entity.Requirement;
import com.pm.vo.ReportRequirementStatusVO;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 需求表 Mapper
 */
public interface RequirementMapper extends BaseMapper<Requirement> {

    /**
     * 查询项目内需求编号（REQ-{缩写}-{4位流水}）的最大流水号，无需求返回 0。
     * 供 Redis 键 seq:req:{projectId} 不存在时回填初值使用
     */
    @Select("SELECT COALESCE(MAX(CAST(RIGHT(req_no, 4) AS UNSIGNED)), 0) FROM requirement WHERE project_id = #{projectId}")
    Long selectMaxSeq(@Param("projectId") Long projectId);

    /**
     * 报表-需求状态分布：全部项目 × 全部状态的需求个数（一次聚合，含终态；无需求的项目不返回）
     */
    @Select("SELECT r.project_id AS projectId, p.name AS projectName, r.status AS status, COUNT(*) AS count " +
            "FROM requirement r JOIN project p ON p.id = r.project_id " +
            "GROUP BY r.project_id, p.name, r.status ORDER BY r.project_id")
    List<ReportRequirementStatusVO> countGroupByProjectStatus();
}
