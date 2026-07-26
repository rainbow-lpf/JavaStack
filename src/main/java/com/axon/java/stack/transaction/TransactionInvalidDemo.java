package com.axon.java.stack.transaction;

import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Spring 事务失效场景 —— 类内部方法调用不走代理
 *
 * <h2>面试回答模板</h2>
 * <pre>
 * 面试官: "Controller 调用 Service 的 A 方法，A 内部调用 B，B 有 @Transactional，A 没有。事务生效吗？"
 *
 * 回答:
 * 不生效。原因是 Spring 事务基于 AOP 动态代理。
 * Controller 拿到的是 Service 的代理对象，调用 A 方法时：
 * 1. 代理拦截，发现 A 没有 @Transactional → 不开启事务 → 直接调用真实对象的 A
 * 2. 真实对象 A 内部 this.B() → this 是真实对象，不是代理 → 完全绕过了 AOP → B 的事务注解失效
 *
 * 这跟 @Async、@Cacheable 失效是同一个根因：内部调用不走代理。
 *
 * 3 种解决方式:
 *   1. A 也加 @Transactional — 最简单，A 和 B 共用一个事务
 *   2. 自己注入自己: @Autowired private XxxService self; self.B() → 走代理
 *   3. AopContext.currentProxy(): ((XxxService) AopContext.currentProxy()).B()
 *      需要 @EnableAspectJAutoProxy(exposeProxy = true)
 *
 * 扩展: 即使把方法写成 public，如果 Spring 用的是 CGLIB 代理且方法是 final/private，
 * CGLIB 无法重写，事务也会失效。
 * </pre>
 *
 * <h2>原理图解</h2>
 * <pre>
 * Controller
 *     │  注入的是代理对象 (CGLIB / JDK)
 *     ▼
 * ┌──────────────────┐
 * │  ServiceProxy     │  ← 代理拦截每个方法调用
 * │  A() → 无事务 →   │
 * │  调用 target.A()  │
 * └──────┬───────────┘
 *        │ target (真实对象)
 *        ▼
 * ┌──────────────────┐
 * │  ServiceImpl      │
 * │  A() {            │
 * │    this.B() ←── !! this = 真实对象, 不是代理, 事务切面被跳过 !!
 * │  }                │
 * │  @Transactional   │  ← 只有走代理调用才会生效
 * │  B() { ... }      │
 * └──────────────────┘
 * </pre>
 *
 * <h2>其他常见事务失效场景</h2>
 * <pre>
 * 1. 内部调用 (本案例)        → this.xxx() 不走代理
 * 2. 非 public 方法           → CGLIB 无法拦截 protected/private/包可见
 * 3. 异常被 catch 吞掉        → 检查型异常默认不回滚（需 rollbackFor = Exception.class）
 * 4. 多线程                  → 事务绑定在线程上，新线程拿不到当前事务
 * 5. 数据库引擎不支持事务      → MyISAM 不支持事务
 * 6. 传播行为设置不当         → propagation = REQUIRES_NEW / NOT_SUPPORTED
 * 7. final 方法               → CGLIB 不能重写 final 方法
 * 8. 同类非代理调用 + 不同事务传播 → 内层 REQUIRES_NEW 也失效
 * </pre>
 */
@Service
public class TransactionInvalidDemo {

    /**
     * 方案2: 注入自己，通过代理调用 B
     */
    @Autowired
    private TransactionInvalidDemo self;

    /**
     * Controller 调的入口 — 无事务
     */
    public String methodA() {
        System.out.println("[MethodA] 线程: " + Thread.currentThread().getName());
        System.out.println("[MethodA] this 类型: " + this.getClass().getName());

        // 场景1: 直接 this.B() — 事务失效
        String result1 = this.methodB("直接 this 调用");

        // 场景2: self.B() — 事务生效（注入的是代理）
        String result2 = self.methodB("通过注入的代理 self 调用");

        // 场景3: AopContext.currentProxy() — 事务生效（需开启 exposeProxy）
        // String result3 = ((Service) AopContext.currentProxy()).methodB("AopContext 代理调用");

        return result1 + " | " + result2;
    }

    /**
     * 加了事务 — 只有走代理调用才生效
     */
    @Transactional
    public String methodB(String source) {
        System.out.println("[MethodB] 被调用, 来源: " + source);
        System.out.println("[MethodB] this 类型: " + this.getClass().getName());
        return "B 执行完毕(" + source + ")";
    }

    /**
     * 验证方法: 打印是否有 AOP 代理
     */
    public boolean isProxyForB() {
        // 如果 Spring 没代理，this 就是普通对象，@Transactional 绝不可能生效
        boolean isProxy = org.springframework.aop.support.AopUtils.isAopProxy(this);
        System.out.println("[检查] this 是否为 AOP 代理: " + isProxy);
        return isProxy;
    }
}
