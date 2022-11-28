package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.UserHolder;
import org.springframework.stereotype.Service;

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

    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        // 1.获取当前登录用户id
        Long userId = UserHolder.getUser().getId();

        // 2.判断当前登录用户是否关注了目标用户
        if (isFollow) {
            // 2.1关注了，则取消关注,删除记录
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followUserId);
            save(follow);
        } else {
            // 2.2没关注，则进行关注，添加记录
            QueryWrapper<Follow> queryWrapper = new QueryWrapper<>();
            queryWrapper.eq("user_id", userId);
            queryWrapper.eq("follow_user_id", followUserId);
            remove(queryWrapper);
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
}
