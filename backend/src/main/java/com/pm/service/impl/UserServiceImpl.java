package com.pm.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pm.common.BizException;
import com.pm.dto.ResetPasswordDTO;
import com.pm.dto.UserCreateDTO;
import com.pm.dto.UserStatusDTO;
import com.pm.dto.UserUpdateDTO;
import com.pm.entity.User;
import com.pm.mapper.UserMapper;
import com.pm.service.UserService;
import com.pm.vo.UserOptionVO;
import com.pm.vo.UserVO;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 人员管理服务实现
 */
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    /** 默认初始密码：创建/重置密码时未指定则使用，首次登录强制修改 */
    private static final String DEFAULT_PASSWORD = "Pm@123456";

    private final UserMapper userMapper;

    /** BCrypt 编解码器（无状态工具，直接实例化） */
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Override
    public Page<UserVO> pageUsers(String keyword, Integer status, long page, long size) {
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(keyword)) {
            String kw = keyword.trim();
            // keyword 模糊匹配姓名或用户名
            wrapper.and(w -> w.like(User::getRealName, kw).or().like(User::getUsername, kw));
        }
        if (status != null) {
            wrapper.eq(User::getStatus, status);
        }
        wrapper.orderByDesc(User::getId);
        Page<User> userPage = userMapper.selectPage(new Page<>(page, size), wrapper);
        Page<UserVO> voPage = new Page<>(userPage.getCurrent(), userPage.getSize(), userPage.getTotal());
        // 仅映射白名单字段，密码绝不出参
        voPage.setRecords(userPage.getRecords().stream().map(this::toUserVO).toList());
        return voPage;
    }

    @Override
    public void createUser(UserCreateDTO dto) {
        // 用户名唯一性校验
        Long count = userMapper.selectCount(
                new LambdaQueryWrapper<User>().eq(User::getUsername, dto.getUsername()));
        if (count != null && count > 0) {
            throw new BizException(400, "用户名已存在");
        }
        User user = new User();
        user.setUsername(dto.getUsername());
        user.setRealName(dto.getRealName());
        // 密码为空使用默认初始密码，BCrypt 加密落库
        String rawPassword = StringUtils.hasText(dto.getPassword()) ? dto.getPassword() : DEFAULT_PASSWORD;
        user.setPassword(passwordEncoder.encode(rawPassword));
        user.setIsAdmin(0);
        user.setStatus(1);
        // 默认密码或自定义密码都需首次登录强制改密
        user.setForceChangePwd(1);
        userMapper.insert(user);
    }

    @Override
    public void updateUser(Long userId, UserUpdateDTO dto) {
        requireUser(userId);
        User update = new User();
        update.setId(userId);
        update.setRealName(dto.getRealName());
        userMapper.updateById(update);
    }

    @Override
    public void resetPassword(Long userId, ResetPasswordDTO dto) {
        requireUser(userId);
        User update = new User();
        update.setId(userId);
        // 密码为空重置为默认密码，BCrypt 加密落库
        String rawPassword = StringUtils.hasText(dto.getPassword()) ? dto.getPassword() : DEFAULT_PASSWORD;
        update.setPassword(passwordEncoder.encode(rawPassword));
        // 重置后再次强制改密
        update.setForceChangePwd(1);
        userMapper.updateById(update);
    }

    @Override
    public void updateStatus(Long operatorId, Long userId, UserStatusDTO dto) {
        requireUser(userId);
        // 不允许停用自己
        if (dto.getStatus() == 0 && userId.equals(operatorId)) {
            throw new BizException(400, "不允许停用自己");
        }
        User update = new User();
        update.setId(userId);
        update.setStatus(dto.getStatus());
        userMapper.updateById(update);
    }

    @Override
    public List<UserOptionVO> listOptions() {
        // 全部正常状态用户，供项目添加成员下拉选择
        return userMapper.selectList(new LambdaQueryWrapper<User>()
                        .eq(User::getStatus, 1)
                        .orderByAsc(User::getId))
                .stream().map(this::toOptionVO).toList();
    }

    /**
     * 按 id 查询用户，不存在抛 404
     */
    private User requireUser(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(404, "用户不存在");
        }
        return user;
    }

    /**
     * 实体转列表 VO（剔除密码等敏感字段）
     */
    private UserVO toUserVO(User user) {
        UserVO vo = new UserVO();
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setRealName(user.getRealName());
        vo.setIsAdmin(user.getIsAdmin());
        vo.setStatus(user.getStatus());
        vo.setCreatedAt(user.getCreatedAt());
        return vo;
    }

    /**
     * 实体转下拉选项 VO
     */
    private UserOptionVO toOptionVO(User user) {
        UserOptionVO vo = new UserOptionVO();
        vo.setId(user.getId());
        vo.setRealName(user.getRealName());
        vo.setUsername(user.getUsername());
        return vo;
    }
}
