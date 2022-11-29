package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.hmdp.utils.RedisConstants.FEED_KEY;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {
    @Resource
    private IUserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IFollowService followService;

    @Override
    public Result queryBlogById(Long id) {
        // 1.查询blog
        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("该笔记不存在...");
        }

        // 2.查询发布blog的博主信息
        queryBlogUser(blog);
        // 3.查询blog有没有被当前登录用户点赞
        isBlogLiked(blog);
        return Result.ok(blog);
    }


    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog -> {
            this.queryBlogUser(blog);
            this.isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    /**
     * 点赞功能
     *
     * @param blogId blog的id
     * @return
     */
    @Override
    public Result likeBlog(Long blogId) {
        // 1.获取当前登录用户id
        Long userId = UserHolder.getUser().getId();
        // 2.判断该用户是否对该blog点赞过？redis set:{key='blog:liked:blogId',value = 'userId'}
        String key = BLOG_LIKED_KEY + blogId.toString();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());

        if (score == null) {
            // 2.1如果用户没有对该blog点赞过，则更新数据库表tb_blog的liked+1
            boolean isSuccess = update().setSql("liked = liked + 1").eq("id", blogId).update();
            if (isSuccess) {
                // 2.2将用户写入redis sorted_set; ZADD key score member  分数为时间戳
                stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
            }
        } else {
            // 3.用户对该blog已经点赞，则取消点赞
            // 3.1用户已经对对该blog点赞过，再次点击，则更新数据库表tb_blog的liked-1
            boolean isSuccess = update().setSql("liked = liked - 1").eq("id", blogId).update();
            if (isSuccess) {
                // 3.2将用户从redis sorted_set集合中移除
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result queryBlogLikes(Long blogId) {
        // 1.查询出top5点赞的用户集合 zrange 0 4 ,按score从小到大
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(BLOG_LIKED_KEY + blogId.toString(), 0, 4);
        if (top5 == null || top5.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }

        // 2.获取其中的用户id
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        String idStr = StrUtil.join(",", ids);
        // 3.根据用户id查询用户 where id in (1010,5) order by field(id,1010,5)
        List<UserDTO> userDTOS = userService.query()
                .in("id", ids)
                .last("ORDER BY FIELD(id," + idStr + ")").list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());

        // 4.返回UserDTo
        return Result.ok(userDTOS);
    }

    @Override
    public Result queryBlogByUserId(Long userId, Integer current) {
        // 1.根据用户id查询探店笔记，最多查询当前第一页的10条
        Page<Blog> page = query().eq("user_id", userId).page(new Page<>(current, 10));

        // 2.获取当前页的数据
        List<Blog> records = page.getRecords();

        return Result.ok(records);
    }

    @Override
    public Result saveBlog(Blog blog) {
        // 1.获取当前登录用户id
        Long userId = UserHolder.getUser().getId();

        // 2.保存笔记
        blog.setUserId(userId);
        boolean isSuccess = save(blog);
        if (!isSuccess) {
            return Result.fail("保存笔记失败...");
        }

        // 3.获取当前登录用户的所有粉丝
        List<Follow> follows = followService.query().eq("follow_user_id", userId).list();

        // 4.在redis中保存blog的id,形式为sorted_set<粉丝id，笔记id，时间戳>
        for (Follow follow : follows) {
            // 4.1获取粉丝id
            Long fansId = follow.getUserId();
            // 4.2推送笔记id给粉丝
            String key = FEED_KEY + fansId;
            stringRedisTemplate.opsForZSet().add(key, blog.getId().toString(), System.currentTimeMillis());
        }
        return Result.ok(blog.getId());
    }


    @Override
    public Result queryBlogOfFollow(Long curMaxTime, Integer offset) {
        // 1.获取当前登录用户id
        Long userId = UserHolder.getUser().getId();

        // 2.从redis中按score(时间戳)从大到小(从最新时间开始)获取数据
        String key = FEED_KEY + userId;
        Set<ZSetOperations.TypedTuple<String>> tuples = stringRedisTemplate.opsForZSet().reverseRangeByScoreWithScores(key, 0, curMaxTime, offset, 2);
        // 判断非空
        if (tuples == null || tuples.isEmpty()) {
            return Result.ok();
        }

        // 3.解析数据
        List<Long> ids = new ArrayList<>(tuples.size());
        long minTime = 0;
        // 与最小元素相同的个数
        int count = 1;
        for (ZSetOperations.TypedTuple<String> tuple : tuples) {
            // 获取笔记id
            String id = tuple.getValue();
            ids.add(Long.valueOf(id));

            // 获取时间戳,从大到小
            long time = tuple.getScore().longValue();
            if (time == minTime) {
                count++;
            } else {
                minTime = time;
                count = 1;
            }
        }

        // 4.根据id查询blog
        String idStr = StrUtil.join(",", ids);
        String lastSql = "ORDER BY FIELD(id," + idStr + ")";
        List<Blog> blogList = query().in("id", ids).last(lastSql).list();
        // 5.每个blog需要添加数据库中没有的信息：发布博主的信息，和博文有没有被当前登录用户点赞
        for (Blog blog : blogList) {
            // 查询发布blog的blogger信息
            queryBlogUser(blog);
            // 查询当前登录用户有没有点赞该blog
            isBlogLiked(blog);
        }

        // 6.封装结果并返回
        ScrollResult r = new ScrollResult();
        r.setList(blogList);
        r.setMinTime(minTime);
        r.setOffset(count);

        return Result.ok(r);
    }

    /**
     * 查询发布blog的博主信息
     *
     * @param blog
     */
    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }

    /**
     * 查询blog有没有被当前登录用户点赞
     *
     * @param blog
     */
    private void isBlogLiked(Blog blog) {
        // 1.获取当前登录用户id
        UserDTO user = UserHolder.getUser();
        // fix bug:未登录用户不需要查询是否点过赞
        if (user == null) {
            return;
        }
        Long userId = user.getId();

        // 2.判断当前登录用户是否对该blog点赞过？redis set:{key='blog:liked:blogId',value = 'userId'}
        String key = BLOG_LIKED_KEY + blog.getId().toString();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());

        //3.当前登录用户给blog点过赞，则对blog对象的isLike进行赋值
        blog.setIsLike(score != null);
    }
}
