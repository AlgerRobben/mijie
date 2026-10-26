package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
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
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * 验证码登录和按月Bitmap签到。登录凭据保存在Redis Hash，请求拦截器恢复到UserHolder。
 * HttpSession参数保留旧接口形式，当前路径使用Redis；短信发送仍是开发日志演示，未接第三方短信平台。
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 校验手机号，生成6位数字验证码并以分钟TTL写入Redis。HttpSession在当前方案中未使用。
     * 日志仅模拟发送；返回成功不代表第三方短信已经送达。
     */
    @Override
    public Result sendCode(String phone, HttpSession session) {
        //1.利用工具类的正则表达式校验手机号
        if(RegexUtils.isPhoneInvalid(phone)){
            //2.如果手机号不合法，返回错误信息Result fail
            return Result.fail("手机号校验失败！");
        }else {
            //3.如果手机合法，生成验证码（工具类随机生成）
            String code = RandomUtil.randomNumbers(6);
            //4.将验证码存储到redis中
            stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);
            //5.发送验证码（调用第三方云平台实现，此处暂不实现）
            log.debug("发送短信验证码成功，验证码：{}", code);
            //6.返回Result success
            return Result.ok();
        }
    }

    /**
     * 从Redis校验验证码，查询/创建用户，再生成token并把UserDTO以字符串Hash字段保存。
     * 返回token给前端；后续请求通过authorization携带。当前成功登录后未删除验证码，仍受原TTL限制。
     */
    @Override
    public Result login(LoginFormDTO loginFormDTO, HttpSession session) {
        //1.校验手机号
        String phone = loginFormDTO.getPhone();
        if(RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("手机号校验失败！");
        }
        //2.从redis中获取验证码校验
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        String code = loginFormDTO.getCode();
        if(code == null || !code.equals(cacheCode)){
            return Result.fail("验证码错误");
        }
        //3.根据手机号查询用户信息来决定登录到哪个账户上
        User user = query().eq("phone", phone).one();

        //4.若不存在该用户则创建用户，若存在则直接登录即可
        if(user == null){
            user = createUserWithPhone(phone);
        }
        //5.将用户信息保存到redis中
        String token = UUID.randomUUID().toString(); //生成随机uuid
        String tokenKey = LOGIN_USER_KEY + token;
                UserDTO userDTO =  BeanUtil.copyProperties(user, UserDTO.class);
        // StringRedisTemplate要求Hash值为字符串；DTO只包含必要用户字段，避免把完整账号存入登录会话。
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create().setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));
        stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
        stringRedisTemplate.expire(tokenKey, LOGIN_USER_TTL, TimeUnit.MINUTES); //设置登录信息时效
        return Result.ok(token);
    }

    /**
     * 按用户和yyyyMM组织Bitmap，本月第day天映射到从0开始的day-1位；重复签到仍是把同一位设为true。
     */
    @Override
    public Result sign() {
        //1.获取当前用户
        Long userId = UserHolder.getUser().getId();
        //2.获取日期
        LocalDateTime now = LocalDateTime.now();
        //3.写入redis中的bitmap, 用户id + year + month组成key，day作为bitmap中的offset
        String yearAndMonth = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + yearAndMonth;
        int dayOfMonth = now.getDayOfMonth();
        stringRedisTemplate.opsForValue().setBit(key, dayOfMonth - 1, true);
        return Result.ok();
    }

    /**
     * BITFIELD一次取得本月1号至今日的无符号位段，最低位对应今日。
     * 从最低位开始连续统计1，遇到0即停止，因此今日未签返回0，且只统计当前月份内的连续天数。
     */
    @Override
    public Result signCount() {
        //1.获取当前用户
        Long userId = UserHolder.getUser().getId();
        //2.获取日期
        LocalDateTime now = LocalDateTime.now();
        //3.根据日期获取key与dayOfMonth
        String yearAndMonth = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + yearAndMonth;
        int dayOfMonth = now.getDayOfMonth();
        //4.根据key和dayOfMonth获取本月至今为止所有签到记录
        List<Long> results = stringRedisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands.create().get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0)
        );
        if(results == null || results.isEmpty()){
            return Result.ok(0);
        }
        Long records = results.getFirst();
        //5.与1作按位与操作并右移一位，循环该步骤直至连续签到中断
        int cnt = 0;
        int record = records.intValue() & 1;
        while(record == 1){
            cnt++;
            // 无符号右移，让下一天成为最低位；按位检查连续签到而非统计整月所有1的数量。
            records = records >>> 1;
            record = records.intValue() & 1;
        }
        return Result.ok(cnt);
    }

    /**
     * 首次验证码登录创建账号，生成默认随机昵称；返回保存后带数据库ID的实体。
     */
    public User createUserWithPhone(String phone){
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        save(user);
        return user;
    }
}
