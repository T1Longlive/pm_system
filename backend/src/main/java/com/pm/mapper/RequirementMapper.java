package com.pm.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pm.entity.Requirement;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

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
}
