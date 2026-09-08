package com.pm.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pm.common.BizException;
import com.pm.dto.ChangePasswordDTO;
import com.pm.dto.LoginDTO;
import com.pm.dto.LoginVO;
import com.pm.dto.UserVO;
import com.pm.entity.User;
import com.pm.mapper.UserMapper;
import com.pm.security.JwtUtil;
import com.pm.service.AuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 认证服务实现
 */
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final UserMapper userMapper;
    private final JwtUtil jwtUtil;

    /** BCrypt 编解码器（无状态工具，直接实例化） */
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Override
    public LoginVO login(LoginDTO loginDTO) {
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, loginDTO.getUsername()));
        // 用户不存在与密码错误统一提示，避免暴露账号是否存在
        if (user == null || !passwordEncoder.matches(loginDTO.getPassword(), user.getPassword())) {
            throw new BizException("用户名或密码错误");
        }
        if (user.getStatus() != 1) {
            throw new BizException("账号已停用，请联系管理员");
        }
        LoginVO vo = new LoginVO();
        vo.setToken(jwtUtil.generateToken(user.getId(), user.getUsername()));
        vo.setUserId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setRealName(user.getRealName());
        vo.setIsAdmin(user.getIsAdmin());
        // 强制改密标记：1=前端登录后必须先引导用户修改初始密码
        vo.setForceChangePwd(user.getForceChangePwd());
        return vo;
    }

    @Override
    public void changePassword(Long userId, ChangePasswordDTO changePasswordDTO) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException("用户不存在");
        }
        if (!passwordEncoder.matches(changePasswordDTO.getOldPassword(), user.getPassword())) {
            throw new BizException("原密码错误");
        }
        User update = new User();
        update.setId(userId);
        update.setPassword(passwordEncoder.encode(changePasswordDTO.getNewPassword()));
        // 修改成功后清除强制改密标记
        update.setForceChangePwd(0);
        userMapper.updateById(update);
    }

    @Override
    public UserVO me(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException("用户不存在");
        }
        UserVO vo = new UserVO();
        vo.setUserId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setRealName(user.getRealName());
        vo.setIsAdmin(user.getIsAdmin());
        vo.setForceChangePwd(user.getForceChangePwd());
        return vo;
    }
}
