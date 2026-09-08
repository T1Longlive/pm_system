package com.pm.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pm.entity.Project;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 项目表 Mapper
 */
public interface ProjectMapper extends BaseMapper<Project> {

    /**
     * 查询用户在指定项目兼任的全部工种（project_member 按工种建行），无记录返回空集合。
     * 供需求模块判断创建权限（PLANNER 工种）等场景使用
     */
    @Select("SELECT craft FROM project_member WHERE project_id = #{projectId} AND user_id = #{userId}")
    List<String> selectCrafts(@Param("projectId") Long projectId, @Param("userId") Long userId);
}
