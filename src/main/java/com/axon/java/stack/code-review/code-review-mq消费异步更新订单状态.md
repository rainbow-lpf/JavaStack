# Code Review：MQ 消费异步更新订单状态

> 复原一段有坑的消费代码 + 问题清单 + 正确写法 + 面试话术

---

## 一、复原的原始代码

```java
@RabbitListener(queues = "order.pay.success")
public void onMessage(OrderMessage msg) {
    // 第一步：Redis 判断是否存在（幂等去重）
    String key = "order:consumed:" + msg.getOrderId();
    if (redisTemplate.hasKey(key)) {
        return;                                    // 已处理过
    }
    redisTemplate.opsForValue().set(key, "1", 30, TimeUnit.MINUTES);

    // 第二步：查订单，判断状态
    Order order = orderMapper.selectById(msg.getOrderId());
    if (order == null) {
        return;
    }
    if (order.getStatus() == OrderStatus.PAID) {
        return;                                    // 已是成功状态，幂等返回
    }

    // 不成功则更新，更新完返回
    order.setStatus(OrderStatus.PAID);
    orderMapper.updateById(order);
}
```

---

## 二、Code Review 问题清单

| # | 问题 | 为什么是坑 |
|---|------|-----------|
| 1 | **Redis 标记先于业务执行** | set 成功 → DB 更新前进程崩溃/消息重投 → hasKey=true 直接 return → **订单永远没更新，消息被"假消费"丢掉** |
| 2 | **`hasKey` + `set` 非原子** | 两条消息并发同 orderId → 都 hasKey=false → 都往下执行 → 幂等失效。应用 `setIfAbsent`（SETNX）原子占位 |
| 3 | **"查状态再更新"非原子** | 并发下两个消费都读到 INIT → 都 update 成 PAID → 重复更新。应该用 **CAS 条件更新** |
| 4 | **"不成功就更新"状态机漏洞** | 若订单当前是 `CANCELED`（已取消），也不等于 PAID → 会把已取消订单改回 PAID，**非法状态流转**。必须校验前置状态 ∈ {INIT, PAYING} |
| 5 | **无异常处理 + ack 语义** | 异常要么被吞（消息丢）要么无限重试（打爆队列），应 catch + 死信/告警 |
| 6 | **Redis 幂等无 DB 兜底** | Redis 重启/清缓存 → 幂等全失效。应加**消息去重表唯一索引**（msg_id unique）做硬兜底 |
| 7 | **Redis key 固定 30min TTL** | 订单 30 分钟内没到终态 / 消费慢 → key 过期 → 重复消息重放副作用。TTL 要 ≥ 幂等窗口 |
| 8 | **`updateById` 全量更新无乐观锁** | 并发覆盖其他字段更新，缺 version 乐观锁 |

---

## 三、概念澄清：这是幂等占位，不是分布式锁

| | 看门狗（Watchdog） | 本代码场景 |
|---|---|---|
| 所属 | Redisson 分布式锁（RLock）的续期机制 | 幂等去重（SETNX + TTL） |
| 场景 | 锁了资源，业务没跑完怕锁过期被抢 | 标记"这条消息处理过没" |
| 机制 | leaseTime=-1 时看门狗每 10s 续期到 30s，直到 unlock | 普通 key 带过期时间，**无续期** |

**看门狗是给"锁"续命的；幂等标记没有看门狗概念。** 面试时说"用 Redisson 看门狗解决过期"是方向跑偏——正确说法是：key 会过期是真实风险，但解法是 TTL 覆盖幂等窗口 + DB 唯一索引兜底，不是续期。

---

## 四、正确写法（三件套：原子占位 + CAS + 先业务后标记）

```java
@RabbitListener(queues = "order.pay.success")
public void onMessage(OrderMessage msg) {
    String idempotentKey = "order:consumed:" + msg.getOrderId();

    // ① SETNX 原子占位，占不到说明别人在/已处理
    Boolean ok = redisTemplate.opsForValue()
            .setIfAbsent(idempotentKey, "1", 30, TimeUnit.MINUTES);
    if (!Boolean.TRUE.equals(ok)) {
        return;
    }

    try {
        // ② 状态机 CAS 条件更新：只允许 INIT/PAYING → PAID
        int rows = orderMapper.updateStatusIfAllowed(
                msg.getOrderId(), OrderStatus.PAID,
                Arrays.asList(OrderStatus.INIT, OrderStatus.PAYING));
        // rows=0 → 已处理过 / 已取消 / 状态非法，幂等返回，不改动
        if (rows == 0) {
            return;
        }
        // ③ 后续副作用（发积分等）在此执行
    } catch (Exception e) {
        // ④ 业务失败 → 删占位标记，允许重试；并告警
        redisTemplate.delete(idempotentKey);
        throw e;   // 由 MQ 重投/死信
    }
}
```

```xml
<!-- 状态机 SQL：只认合法前置状态，影响行数即幂等依据 -->
<update id="updateStatusIfAllowed">
    UPDATE t_order SET status = #{toStatus}, update_time = NOW()
    WHERE order_id = #{orderId}
      AND status IN
      <foreach collection="fromStatuses" item="s" open="(" separator="," close=")">
          #{s}
      </foreach>
</update>
```

---

## 五、面试话术（30 秒）

> 原代码四个硬伤：Redis 标记放在业务之前——崩溃重投会被假消费丢消息；hasKey+set 非原子，并发下幂等失效；查状态再更新非原子，应改 CAS 条件更新；以及"不等于 PAID 就更新"会把已取消订单非法改回 PAID。正确姿势：SETNX 原子占位 + 状态机条件更新（前置状态白名单 + 影响行数判幂等）+ 业务失败删标记允许重试 + DB 消息去重表兜底 Redis 失效。关于过期：这是幂等标记不是分布式锁，不涉及看门狗——key 过期风险靠 TTL 覆盖幂等窗口 + DB 唯一索引兜底解决。核心原则：**Redis 只是加速，DB 唯一索引/CAS 才是幂等硬保证；标记在业务成功后保留，失败要释放。**
