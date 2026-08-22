package com.hmdp.service.impl;

import cn.hutool.core.util.RandomUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.UserInfo;
import com.hmdp.mapper.UserInfoMapper;
import com.hmdp.service.IUserInfoService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-24
 */
@Slf4j
@Service
public class UserInfoServiceImpl extends ServiceImpl<UserInfoMapper, UserInfo> implements IUserInfoService {

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
}
