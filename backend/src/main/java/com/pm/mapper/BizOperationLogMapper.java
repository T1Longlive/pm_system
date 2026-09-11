package com.pm.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pm.entity.BizOperationLog;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 统一操作留痕表 Mapper
 */
public interface BizOperationLogMapper extends BaseMapper<BizOperationLog> {

    /**
     * 日志操作人去重（SQL 层 DISTINCT，替代全表扫描到内存再去重）；
     * projectIds 为空集合时返回空（1=0 防止全表泄露），管理员传全部项目 id
     */
    @Select("<script>" +
            "SELECT DISTINCT operator_id FROM biz_operation_log" +
            "<where>" +
            "<choose>" +
            "<when test='projectIds != null and !projectIds.isEmpty()'>" +
            "project_id IN <foreach collection='projectIds' item='pid' open='(' separator=',' close=')'>#{pid}</foreach>" +
            "</when>" +
            "<otherwise>1 = 0</otherwise>" +
            "</choose>" +
            "</where>" +
            "</script>")
    List<Long> selectDistinctOperatorIds(@Param("projectIds") List<Long> projectIds);
}
