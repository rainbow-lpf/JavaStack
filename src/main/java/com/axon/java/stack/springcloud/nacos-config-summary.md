# 配置中心（Nacos Config）

> 目录：`com.axon.java.stack.springcloud`

---

## 一、一句话说清楚

> 配置中心把 application.yml 里的配置抽到云端统一管理，改配不改代码不动应用，实现动态刷新。

---

## 二、没有配置中心的问题

```
application.yml 写死配置：

  db.ip = 192.168.1.100
  redis.ip = 192.168.1.200

某天 DB 做了迁移 → 新 IP 192.168.1.101
  怎么办？→ 改 yml → 重新打包 → 重启所有 20 个实例

痛点：
  - 改一个配置要重启一堆服务
  - 各环境配置散落在不同 yml，难以统一管理
  - 敏感信息（密码）明文写在 yml 提交到 Git 不安全
```

---

## 三、有了配置中心

```
配置中心（Nacos Config）

   ┌─────────────── Nacos 控制台 ───────────────┐
   │                                             │
   │  db.ip = 192.168.1.101     ← 直接改这里     │
   │  redis.maxTotal = 200      ← 不用改代码     │
   │                                             │
   └──────────────────┬──────────────────────────┘
                      │ 推送变更
         ┌────────────┼────────────┐
         ▼            ▼            ▼
    实例-1        实例-2        实例-3      ← 自动拉新配置，热刷新
```

---

## 四、Nacos Config 核心机制

### 4.1 Data ID 命名规则

```
${prefix}-${spring.profiles.active}.${file-extension}

示例：user-service-dev.yaml
       ├─ prefix：spring.application.name = user-service
       ├─ active：dev
       └─ extension：yaml
```

### 4.2 三层配置隔离

| 级别 | 方式 | 示例 |
|------|------|------|
| namespace（命名空间） | 环境隔离 | dev / test / prod |
| group（分组） | 业务隔离 | ORDER_GROUP / USER_GROUP |
| dataId（文件） | 应用级隔离 | user-service.yaml |

```
namespace: dev
  ├─ group: DEFAULT_GROUP
  │    ├─ user-service.yaml
  │    └─ order-service.yaml
  └─ group: LOG_CONFIG
       └─ shared-log.yaml          ← 共享日志配置
```

### 4.3 动态刷新原理

```
1. 启动时：应用从 Nacos 拉取配置 → 注入 Spring Environment
2. 加 @RefreshScope 的 Bean → Nacos 监听此 Bean 的配置项
3. 在 Nacos 控制台修改配置 → 点击发布
4. Nacos Server → 推送变更给所有监听客户端
5. 客户端刷新 @RefreshScope Bean → 新配置立刻生效

关键类：
  NacosPropertySourceLocator    → 启动时拉配置
  NacosContextRefresher         → 收到变更事件 → 重建 Bean
  @RefreshScope                 → 标注需要动态刷新的 Bean
```

---

## 五、基础配置

```yaml
# bootstrap.yml（Spring Cloud 配置引导文件，必须在此配置才能早于应用启动加载）
spring:
  application:
    name: user-service
  cloud:
    nacos:
      config:
        server-addr: 127.0.0.1:8848
        namespace: dev
        group: DEFAULT_GROUP
        file-extension: yaml
        shared-configs:           # 共享配置（多服务共用）
          - data-id: common.yaml
            group: DEFAULT_GROUP
            refresh: true
        extension-configs:        # 扩展配置
          - data-id: log-config.yaml
            group: LOG_CONFIG
            refresh: true
```

---

## 六、动态刷新示例

```java
@RestController
@RefreshScope   // 标注后，配置变更时此 Bean 会被重建
public class ConfigController {

    @Value("${app.version}")
    private String version;

    @Value("${app.switch.enabled}")
    private boolean switchEnabled;

    @GetMapping("/config")
    public Map<String, Object> getConfig() {
        Map<String, Object> map = new HashMap<>();
        map.put("version", version);
        map.put("switch", switchEnabled);
        return map;
    }
}
```

### @RefreshScope 的坑

```
@RefreshScope 内部用 CGLIB 代理，每次调用重新创建 Bean。

问题：
  - 不要标注在全局单例上（比如连接池），会频繁重建浪费资源
  - 标注在 Controller / Service 上 → 用到动态配置的 Bean 才标
  - 不用标注也能拿到最新的 @Value —— 前提是该对象通过 BeanFactory 获取

建议：只标注那些真正需要运行时热更新的 Bean
```

---

## 七、bootstrap.yml vs application.yml

| | bootstrap.yml | application.yml |
|--|-------------|----------------|
| 加载顺序 | 先（Bootstrap Context） | 后（Application Context） |
| 用途 | 配置中心的地址、应用名、环境 | 业务配置 |
| 读取配置中心 | ✅ 从这里读 Nacos 地址去拉配置 | ❌ 这时候 Nacos 配置还没拉到 |
| 注意 | Spring Cloud 2020.0 后默认不读该文件，需加 `spring.cloud.bootstrap.enabled=true` | |

---

## 八、配置优先级（从高到低）

```
1. 命令行参数                       java -jar --db.ip=1.2.3.4
2. Nacos 共享配置（shared-configs）
3. Nacos 扩展配置（extension-configs）
4. Nacos 应用配置（${spring.application.name}.yaml）
5. application.yml（本地）
6. bootstrap.yml（本地）
```

---

## 九、常见问题

| 问题 | 原因 | 解决 |
|------|------|------|
| 改了配置不生效 | 没加 @RefreshScope | 需要用到的 Bean 加注解 |
| 启动报连不上 Nacos | bootstrap.yml 没配或没启用 | 加 `spring.cloud.bootstrap.enabled=true` |
| Nacos 配置不生效 | namespace/group/dataId 不匹配 | 三要素对齐 |
| 共享配置不刷新 | shared-configs 里 refresh 没开 | `refresh: true` |
| 线上配置不小心改了 | 人工误操作 | Nacos 带配置版本+回滚 |

---

## 十、面试话术（30 秒版）

> 配置中心把 application.yml 抽到云端统一管理。Nacos Config 支持，改配不动应用， @RefreshScope 标注的 Bean 在配置变更时自动重建。三层隔离：namespace 做环境隔离，group 做业务隔离，dataId 做应用隔离。支持共享配置，多服务复用同一份配置减少冗余。
