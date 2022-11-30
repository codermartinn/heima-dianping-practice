package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

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
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        // 1.校验手机号码
        if (RegexUtils.isPhoneInvalid(phone)) {
            // 2.如果不符合，返回错误信息
            return Result.fail("手机号格式错误...");
        }


        // 3.如果符合，生成验证码
        String code = RandomUtil.randomNumbers(6);


        // 4.保存验证码到session
        //session.setAttribute("phone", code);

        // 4.保存验证码到redis
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);


        // 5.发送验证码
        log.debug("发送短信验证码成功，验证码：{}", code);

        // 返回ok
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        // 1.校验手机号
        String phone = loginForm.getPhone();

        if (RegexUtils.isPhoneInvalid(phone)) {
            // 2.如果不符合，返回错误信息
            return Result.fail("手机号格式错误...");
        }

        // 2.校验验证码
//        Object cacheCode = session.getAttribute("code");
//        String code = loginForm.getCode();

        // 2.从redis中获取验证码并校验
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        //前端用户输入的验证码
        String code = loginForm.getCode();

        if (cacheCode == null || !cacheCode.equals(code)) {
            // 3.验证码不一致，报错
            return Result.fail("验证码错误...");
        }

        // 4.一致，根据手机号查询用户
        User user = query().eq("phone", phone).one();


        // 5.判断用户是否存在
        if (user == null) {
            // 6.不存在，创建用户并保存
            user = createUserWithPhone(phone);
        }

        // 7.保存用户信息到session
        // 7.保存用户信息到redis
        // 7.1生成随机token，作为登录令牌
        String token = UUID.randomUUID().toString(true);

        // 7.2将User对象作为HashMap存储
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fileName, fileValue) -> fileValue.toString()));

        // 7.3存储
        String tokenKey = LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
        // 7.4设置有效期
        stringRedisTemplate.expire(tokenKey, LOGIN_USER_TTL, TimeUnit.MINUTES);

        // 8.返回token
        return Result.ok(token);
    }


    @Override
    public Result signIn() {
        // 1.获取用户id
        Long userId = UserHolder.getUser().getId();

        // 2.获取日期
        LocalDateTime now = LocalDateTime.now();

        // 3.拼接key
        String sufferKey = now.format(DateTimeFormatter.ofPattern("yyyy:MM"));
        String key = USER_SIGN_KEY + userId + ":" + sufferKey;

        // 4.获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();

        // 5.写入redis
        stringRedisTemplate.opsForValue().setBit(key, dayOfMonth - 1, true);

        return Result.ok();
    }

    @Override
    public Result signCount() {
        // 1.获取用户id
        Long userId = UserHolder.getUser().getId();

        // 2.获取日期
        LocalDateTime now = LocalDateTime.now();

        // 3.拼接key
        String sufferKey = now.format(DateTimeFormatter.ofPattern("yyyy:MM"));
        String key = USER_SIGN_KEY + userId + ":" + sufferKey;

        // 4.获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();

        // 5.获取本月到今天为止，用户连续签到的记录，返回的是一个十进制数字 BITFIELD sign:5:2022:03 get u[取几个bit位] 0[index从哪开始]
        List<Long> results = stringRedisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth))
                        .valueAt(0)
        );
        // 处理数据为空情况
        if (results == null || results.isEmpty()) {
            return Result.ok(0);
        }

        Long num = results.get(0);
        if (num == null || num == 0) {
            return Result.ok(0);
        }

        // 6.循环遍历，计算出从后往前有几个连续的1
        int count = 0;
        while (true) {
            if ((num & 1) == 0) {
                // 如果为0，说明没签到，退出循环
                break;
            } else {
                // 如果不为0，说明已签到，连续签到天数+1
                count++;
            }
            //将num无符合右移一位，继续从后往前计算下一个bit位
            num = num >>> 1;
        }

        return Result.ok(count);
    }

    private User createUserWithPhone(String phone) {
        // 1.创建用户
        User user = new User();
        user.setPhone(phone);
        user.setNickName("user_ " + RandomUtil.randomString(10));

        // 2.保存用户到数据库
        save(user);

        return user;
    }
}
