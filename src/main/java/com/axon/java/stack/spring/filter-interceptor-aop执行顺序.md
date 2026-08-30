# 过滤器、拦截器、AOP 执行顺序

> Filter / HandlerInterceptor / AOP 三层定位、执行链路、异常行为、应用场景

---

## 一、一句话顺序

```
请求进来：Filter → Interceptor → AOP → Controller 方法
响应返回：Controller 方法 → AOP → Interceptor → Filter（原路反向）
```

---

## 二、完整链路图

```
请求 ──▶ [Filter 链]                ① Servlet 容器层，DispatcherServlet 之外
            │
            ▼
        DispatcherServlet           ② 前端控制器，框架入口
            │
            ▼
        [Interceptor.preHandle]     ③ MVC 层，处理器执行前
            │
            ▼
        [AOP 环绕通知：@Around 前]    ④ Bean 层，方法代理内
            │
            ▼
        Controller 方法执行          ⑤ 真正干活
            │
            ▼
        [AOP 环绕通知：@Around 后]    ④'
            │
            ▼
        [Interceptor.postHandle]    ③' 处理器执行后、视图渲染前
            │
            ▼
        视图渲染
            │
            ▼
        [Interceptor.afterCompletion]  ③'' 渲染后，一定执行
            │
            ▼
        [Filter 链 收尾]             ①' 原路返回
```

---

## 三、三层定位表

| | Filter | Interceptor | AOP |
|---|--------|-------------|-----|
| 所属 | **Servlet 容器**（javax/jakarta.servlet） | **Spring MVC**（HandlerInterceptor） | **Spring Bean 层**（切面） |
| 作用范围 | 所有请求（含静态资源、非 Spring 请求） | 进了 DispatcherServlet 的请求 | 单个 Bean 的某个方法 |
| 能否拿到 Controller 类/方法 | ❌ | ✅（HandlerMethod） | ✅（ProceedingJoinPoint） |
| 能拿 HttpServletRequest/Response | ✅ | ✅ | ❌（靠 RequestContextHolder 取） |
| 执行时机 | DispatcherServlet **之外** | DispatcherServlet **之内**、handler 前后 | 方法代理**之内** |
| 应用场景 | 编码、跨域 CORS、请求日志、XSS 过滤 | 登录态校验、权限、TraceId | 业务切面、日志、事务、幂等 |

---

## 四、各自内部排序

```
Filter 多个：@Order 值小先执行（或 FilterRegistrationBean.setOrder）
Interceptor 多个：addInterceptor 注册顺序（preHandle 顺序，afterCompletion 逆序）
AOP 多个：@Order 值小的在外层（环绕前半段先执行、后半段后执行）
```

---

## 五、异常时的行为差异（面试必问）

```
Controller 抛异常：
  ├─ AOP    ：@AfterThrowing 执行（@After 也会）
  ├─ Interceptor：
  │     ├─ postHandle        → ❌ 不执行（只有正常返回才走）
  │     └─ afterCompletion   → ✅ 一定执行（类似 finally）
  └─ Filter  ：finally 块一定执行，异常最终抛给容器
```

**记忆点：`postHandle` 只在成功时走，`afterCompletion` 不管成败都走——和 try-catch-finally 一个道理。**

---

## 六、经典追问：谁最先拦得住请求？

**Filter 最先。** 它活在 Servlet 容器层，DispatcherServlet 都还没进来。所以：

- CORS、字符编码放 Filter（可能要处理根本进不了 Spring 的请求）
- 登录校验放 Interceptor（需要判断具体 handler 是否免鉴权）
- 业务日志/事务放 AOP（需要环绕具体方法）

---

## 七、面试话术（30 秒）

> 顺序：Filter → Interceptor → AOP → 目标方法，响应时原路反向返回。三层定位不同——Filter 在 Servlet 容器层、DispatcherServlet 之外，管所有请求；Interceptor 是 Spring MVC 层，能拿到 HandlerMethod 做权限；AOP 是 Bean 层，环绕具体方法做业务增强。异常时 postHandle 不执行、afterCompletion 一定执行。所以 CORS/编码放 Filter，登录权限放 Interceptor，业务日志事务放 AOP。
