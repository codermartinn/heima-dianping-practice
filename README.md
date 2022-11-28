## Contents
* 短信登录
这一块我们会使用redis共享session来实现

* 商户查询缓存
通过本章节，我们会理解缓存击穿，缓存穿透，缓存雪崩等问题，让小伙伴的对于这些概念的理解不仅仅是停留在概念上，更是能在代码中看到对应的内容

* 优惠卷秒杀
通过本章节，我们可以学会Redis的计数器功能， 结合Lua完成高性能的redis操作，同时学会Redis分布式锁的原理，包括Redis的三种消息队列

* 附近的商户
我们利用Redis的GEOHash来完成对于地理坐标的操作

* UV统计
主要是使用Redis来完成统计功能

* 用户签到
使用Redis的BitMap数据统计功能

* 好友关注
基于Set集合的关注、取消关注，共同关注等等功能，这一块知识咱们之前就讲过，这次我们在项目中来使用一下

* 打人探店
基于List来完成点赞列表的操作，同时基于SortedSet来完成点赞的排行榜功能


## Update
- 2022-11-20 实现基于session的短信登录
- 2022-11-20 实现基于Redis的短信登录。
  - 有两个拦截器，一个拦截器负责刷新token有效期，一个负责登录校验拦截
- 2022-11-21 商铺查询缓存
  - 给商铺查询添加缓存
  - 给商铺类型查询添加缓存
  - 解决了商铺查询的缓存穿透问题
  - 基于互斥锁的方式解决「根据id查询商铺」缓存击穿的问题
  - 基于逻辑过期的方式解决「根据id查询商铺」缓存击穿的问题
- 2022-11-23 商铺查询缓存 and 优惠券秒杀
  - 封装Redis工具类
  - Redis实现全局唯一ID
  - 实现代金券秒杀下单功能
- 2022-11-24 优惠券秒杀
  - 使用乐观锁解决商品超卖的问题
  - 使用悲观锁实现了一人一单的功能
  - 获取代理对象解决了事务生效失败的问题

- 2022-11-25 Redis分布式锁
  - 实现了基于Redis的分布式锁，初级版本
  - 解决在业务阻塞情况下，会释放其他线程锁的问题
  - 解决分布式锁的释放原子性问题：「判断锁标识与释放锁的原子性」
    - 基于Lua脚本实现分布式锁的释放锁逻辑

- 2022-11-26 Redis分布式锁
  - 使用Redisson分布式锁
  - 秒杀优化
    - Redis完成秒杀资格判断
      - 新增秒杀优惠券的同时，将优惠券信息保存到Redis中
      - 基于Lua脚本，判断秒杀库存、一人一单，决定用户是否抢购成功
      - 如果抢购成功，将优惠券id和用户id封装后存入阻塞队列
      - 开启线程任务，不断从阻塞队列中获取信息，实现异步下单功能

- 2022-11-27 Redis消息队列 and 达人探店
  - 基于Redis的Stream结构作为消息队列，实现异步秒杀下单
    - 创建一个Stream类型的消息队列，名为stream.orders
    - 修改之前的秒杀下单Lua脚本，在认定有抢购资格后，直接向stream.orders中添加消息，内容包含voucherId、userId、orderId
    - 项目启动时，开启一个线程任务，尝试获取stream.orders中的消息，完成下单
  - 达人探店
    - 查看探店笔记
    - 完善点赞功能
      - 同一个用户只能点赞一次，再次点击则取消点赞
      - 如果当前用户已经点赞，则点赞按钮高亮显示（前端已实现，判断字段Blog类的isLike属性）

- 2022-11-28 达人探店
  - 点赞排行榜
  - 好友关注
    - 关注和取关
    - 共同关注
  