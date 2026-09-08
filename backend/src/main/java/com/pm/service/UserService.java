package com.pm.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pm.dto.ResetPasswordDTO;
import com.pm.dto.UserCreateDTO;
import com.pm.dto.UserStatusDTO;
import com.pm.dto.UserUpdateDTO;
import com.pm.vo.UserOptionVO;
import com.pm.vo.UserVO;

import java.util.List;

/**
 * 人员管理服务：管理员对系统人员的增改与状态管理、成员下拉选项
 */
public interface UserService {

    /**
     * 人员分页列表（仅管理员）：keyword 模糊匹配姓名/用户名，status 过滤账号状态（1/0 可空）
     * 出参不含密码字段
     */
    Page<UserVO> pageUsers(String keyword, Integer status, long page, long size);

    /**
     * 新增人员（仅管理员）：用户名唯一校验，密码 BCrypt 加密（空则默认 Pm@123456），创建后强制改密
     */
    void createUser(UserCreateDTO dto);

    /**
     * 编辑人员姓名（仅管理员）
     */
    void updateUser(Long userId, UserUpdateDTO dto);

    /**
     * 重置密码（仅管理员）：空则重置为默认密码 Pm@123456，重置后再次强制改密
     */
    void resetPassword(Long userId, ResetPasswordDTO dto);

    /**
     * 停用/启用人员（仅管理员）：不允许停用自己
     */
    void updateStatus(Long operatorId, Long userId, UserStatusDTO dto);

    /**
     * 全部正常状态用户下拉选项（任何登录用户可调），供项目添加成员使用
     */
    List<UserOptionVO> listOptions();
}
