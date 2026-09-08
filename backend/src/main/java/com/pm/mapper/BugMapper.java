package com.pm.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pm.entity.Bug;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 缺陷表 Mapper
 */
public interface BugMapper extends BaseMapper<Bug> {

    /**
     * 查询项目内 Bug 编号（BUG-{缩写}-{4位流水}）的最大流水号，无 Bug 返回 0。
     * 供 Redis 键 seq:bug:{projectId} 不存在时回填初值使用
     */
    @Select("SELECT COALESCE(MAX(CAST(RIGHT(bug_no, 4) AS UNSIGNED)), 0) FROM bug WHERE project_id = #{projectId}")
    Long selectMaxSeq(@Param("projectId") Long projectId);
}
