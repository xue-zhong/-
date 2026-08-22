package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.SystemConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
@TableName("tb_user")

public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {
    @Override
    public Result sendCode(String phone, HttpSession session) {
//        校验是否是无效手机号，如果是，返回失败内容
        if(RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("手机号无效，请输入正确格式的手机号");
        }
//        生成六位数字的验证码
        String code= RandomUtil.randomNumbers(6);
        session.setAttribute("code",code);
        log.debug("验证码发送成功，验证码{}",code);

        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        String phone=loginForm.getPhone();
        if(RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("手机号无效，请输入正确格式的手机号");
        }
        String code=loginForm.getCode();
        Object cacheCode= session.getAttribute("code");
        if(cacheCode==null||!code.equals(cacheCode)){
            return Result.fail("输入的手机号或验证码有误");
        }
        User user= query().eq("phone",phone).one();
        if(user ==  null){
             user= createUserWithPhone(phone);
        }
        session.setAttribute("user", BeanUtil.copyProperties(user, UserDTO.class));
        return Result.ok();
    }
    private User createUserWithPhone(String phone){
        User user=new User();
        user.setPhone(phone);
        user.setNickName(SystemConstants.USER_NICK_NAME_PREFIX+RandomUtil.randomString(10));
        save(user);
        return user;
    }
}
