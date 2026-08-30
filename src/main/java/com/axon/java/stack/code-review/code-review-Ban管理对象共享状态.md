# Code Review：Ban 管理对象的共享可变状态

> 复原一段把局部缓冲写成成员变量的雷区代码 + 问题清单 + 正确写法

---

## 一、复原的原始代码

```java
@Component
public class BanManager {

    // ① 成员变量当缓冲区用（雷区核心）
    private StringBuilder sqlBuilder = new StringBuilder();
    private List<String> banList = new ArrayList<>();

    // ② 对外方法：拼 SQL + 收集封禁对象
    public String buildBanSql(List<String> userIds) {
        for (String uid : userIds) {
            sqlBuilder.append(" OR user_id = '").append(uid).append("'");
            banList.add(uid);
        }
        return sqlBuilder.toString();   // ③ 返回的是"越滚越大"的字符串
    }

    public List<String> getBanList() {
        return banList;
    }
}
```

---

## 二、Code Review 问题清单

| # | 问题 | 为什么是坑 |
|---|------|-----------|
| 1 | **成员变量当局部变量用** | StringBuilder/List 是共享可变状态——Bean 默认单例，所有线程、所有请求共用同一份 |
| 2 | **线程不安全** | `StringBuilder` 非线程安全，并发 append 数据错乱；`ArrayList` 并发 add 丢数据/越界 |
| 3 | **不清空，无限累积** | `sqlBuilder` 和 `banList` 只增不减 → 每次调用结果包含前 N 次内容 → **脏数据 + 内存泄漏** |
| 4 | **toString 越滚越长** | 第二次返回的 SQL 会带上第一次的 `OR user_id=...`，条件越拼越多，逻辑完全错误 |
| 5 | **SQL 拼接注入风险** | `' + uid + '` 未参数化，uid 来自外部可注入 |
| 6 | **职责错位** | BanManager 里拼 SQL，SQL 该在 Mapper 层用 MyBatis `<foreach>` 干 |
| 7 | **getBanList 暴露内部状态** | 返回可变 List 引用，外部可随意篡改 |

---

## 三、根因一句话

> **把"方法内部的临时变量"错误提升成了"类的成员变量"，把无状态的工具类写成了有状态的单例——并发串扰 + 状态累积，两头都炸。**

---

## 四、正确写法

### 方案 A：SQL 交给 MyBatis（正解）

```java
@Component
public class BanManager {
    // 无成员变量，纯无状态

    public int banUsers(List<String> userIds) {
        return banMapper.batchBan(userIds);   // SQL 进 Mapper
    }
}
```

```xml
<update id="batchBan">
    UPDATE t_user SET banned = 1
    WHERE user_id IN
    <foreach collection="userIds" item="uid" open="(" separator="," close=")">
        #{uid}
    </foreach>
</update>
```

### 方案 B：一定要自己拼，就用局部变量 + 参数化

```java
public String buildBanSql(List<String> userIds) {
    StringBuilder sb = new StringBuilder();          // 局部，每次新建
    for (int i = 0; i < userIds.size(); i++) {
        if (i > 0) sb.append(" OR ");
        sb.append("user_id = ?");                    // 占位符防注入
    }
    return sb.toString();
}
```

---

## 五、面试话术（30 秒）

> 核心问题是把 StringBuilder 和 List 这种方法内临时缓冲，写成了 Bean 的成员变量——Spring Bean 默认单例，等于所有请求共享一份可变状态：StringBuilder 线程不安全，并发 append 会串数据；而且只加不清，toString 返回的结果越滚越长，每次调用都带着前几次的残留，既是逻辑错误又是内存泄漏。修法两个：SQL 直接下沉到 MyBatis 用 foreach 参数化拼接；或者真要在内存拼，用方法局部变量每次新建，参数用占位符防注入。一句话：**无状态方法别用有状态成员变量。**
