package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;

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
     * @param blogId blog的id
     * @return
     */
    @Override
    public Result likeBlog(Long blogId) {
        // 1.获取当前登录用户id
        Long userId = UserHolder.getUser().getId();
        // 2.判断该用户是否对该blog点赞过？redis set:{key='blog:liked:blogId',value = 'userId'}
        String key = BLOG_LIKED_KEY + blogId.toString();
        Boolean isMember = stringRedisTemplate.opsForSet().isMember(key, userId.toString());
        if (BooleanUtil.isFalse(isMember)) {
            // 2.1如果用户没有对该blog点赞过，则更新数据库表tb_blog的liked+1
            boolean isSuccess = update().setSql("liked = liked + 1").eq("id", blogId).update();
            if (isSuccess) {
                // 2.2将用户写入redis set集合中
                stringRedisTemplate.opsForSet().add(BLOG_LIKED_KEY + blogId.toString(), userId.toString());
            }
        } else {
            // 3.用户对该blog已经点赞，则取消点赞
            // 3.1用户已经对对该blog点赞过，再次点击，则更新数据库表tb_blog的liked-1
            boolean isSuccess = update().setSql("liked = liked - 1").eq("id", blogId).update();
            if (isSuccess) {
                // 3.2将用户从redis set集合中移除
                stringRedisTemplate.opsForSet().remove(key, userId.toString());
            }
        }
        return Result.ok();
    }

    /**
     * 查询发布blog的博主信息
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
     * @param blog
     */
    private void isBlogLiked(Blog blog) {
        // 1.获取当前登录用户id
        Long userId = UserHolder.getUser().getId();

        // 2.判断当前登录用户是否对该blog点赞过？redis set:{key='blog:liked:blogId',value = 'userId'}
        String key = BLOG_LIKED_KEY + blog.getId();
        Boolean isMember = stringRedisTemplate.opsForSet().isMember(key, userId.toString());

        //3.当前登录用户给blog点过赞，则对blog对象的isLike进行赋值
        blog.setIsLike(BooleanUtil.isTrue(isMember));
    }
}
