package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.FOLLOW_USER_KEY;


/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IUserService userService;

    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        // 1.获取当前登录用户id
        Long userId = UserHolder.getUser().getId();

        // 2.判断当前登录用户是要进行关注还是取关
        if (isFollow) {
            // 2.1关注，则进行关注，添加记录
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followUserId);
            boolean isSuccess = save(follow);
            // 2.2关注成功，则在redis中添加目标用户id
            if (isSuccess) {
                String key = FOLLOW_USER_KEY + userId;
                stringRedisTemplate.opsForSet().add(key, followUserId.toString());
            }
        } else {
            // 2.3取关，则取消关注,删除记录
            QueryWrapper<Follow> queryWrapper = new QueryWrapper<>();
            queryWrapper.eq("user_id", userId);
            queryWrapper.eq("follow_user_id", followUserId);
            boolean isSuccess = remove(queryWrapper);
            // 2.4取关成功，则在redis中删除目标用户id
            if (isSuccess) {
                String key = FOLLOW_USER_KEY + userId;
                stringRedisTemplate.opsForSet().remove(key, followUserId.toString());
            }
        }

        return Result.ok();
    }

    @Override
    public Result isFollow(Long followUserId) {
        // 1.获取当前登录用户id
        Long userId = UserHolder.getUser().getId();

        // 2.数据库查询是否有关联记录
        Integer count = query().eq("user_id", userId).eq("follow_user_id", followUserId).count();

        // 3.如果关联记录大于0，则当前登录用户关注过目标用户
        return Result.ok(count > 0);
    }

    @Override
    public Result followCommon(Long bloggerId) {
        // 1.获取当前登陆用户
        Long userId = UserHolder.getUser().getId();

        // 2.共同关注
        String key1 = FOLLOW_USER_KEY + userId;
        String key2 = FOLLOW_USER_KEY + bloggerId;
        Set<String> commons = stringRedisTemplate.opsForSet().intersect(key1, key2);
        // 3.如果共同关注为空，则返回
        if (commons == null || commons.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        // 4.如果共同关注不为空
        List<Long> ids = commons.stream().map(Long::valueOf).collect(Collectors.toList());

        // 5.查询所有共同关注的用户信息
        List<User> users = userService.listByIds(ids);
        List<UserDTO> userDTOS = users.stream().map(user -> BeanUtil.copyProperties(user, UserDTO.class)).collect(Collectors.toList());

        // 6.返回一个UserDTO类型的List
        return Result.ok(userDTOS);
    }
}
