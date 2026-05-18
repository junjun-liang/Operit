# Condition 模块设计思想与详细使用指南

## 一、模块概述

`condition` 模块是 Operit AI 的**条件表达式评估引擎**，提供轻量级的布尔表达式解析与计算能力。该模块采用经典的**词法分析 + 语法分析 + AST 求值**三阶段架构，支持变量引用、逻辑运算、比较运算、数组成员检查等丰富的条件判断功能，广泛应用于包的条件启用、Hook 触发判断、角色卡权限控制等场景。

### 1.1 核心定位

- **条件评估器**：解析并计算布尔条件表达式
- **能力检查器**：基于设备能力映射进行条件判断
- **轻量级引擎**：单文件实现，零外部依赖
- **安全执行**：表达式异常时返回 false，不会崩溃

### 1.2 模块结构

```
condition/
└── ConditionEvaluator.kt    # 条件评估器（单文件完整实现）
```

### 1.3 内部架构

```
ConditionEvaluator (Object)
    │
    ├── evaluate(expression, capabilities) → Boolean
    │       │
    │       ├── Tokenizer.tokenize() → List<Token>
    │       │       ├── 词法分析：将字符串拆分为 Token 序列
    │       │       └── 支持：标识符、字符串、数字、布尔值、null、运算符、标点
    │       │
    │       ├── Parser.parseExpression() → Expr (AST)
    │       │       ├── 语法分析：将 Token 序列构建为抽象语法树
    │       │       └── 优先级：|| → && → ==/!= → >/>=/</<=/in → ! → primary
    │       │
    │       └── AST.eval(capabilities) → Value
    │               ├── 表达式求值：遍历 AST 计算结果
    │               └── 支持：Bool、Num、Str、Null、Array 五种值类型
    │
    └── Value 类型系统
            ├── Bool: Boolean 值
            ├── Num: Double 数值
            ├── Str: String 字符串
            ├── Null: 空值
            └── Array: 值数组
```

---

## 二、核心设计思想

### 2.1 三阶段执行模型

ConditionEvaluator 采用编译原理经典的三阶段模型：

```
┌─────────────┐     ┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│  Expression │ ──▶ │   Tokens    │ ──▶ │     AST     │ ──▶ │   Result    │
│   String    │     │   (Lexer)   │     │  (Parser)   │     │  (Evaluator)│
└─────────────┘     └─────────────┘     └─────────────┘     └─────────────┘
   "a > 5"           [Id(a), >, Num(5)]   BinaryExpr         true/false
```

**设计优势**：
- **分离关注点**：词法、语法、求值三个阶段独立
- **可扩展性**：新增运算符只需修改 Parser 和 Evaluator
- **错误隔离**：任一阶段出错不会导致崩溃，返回 false

### 2.2 递归下降语法分析

Parser 采用递归下降方式，按运算符优先级分层解析：

```
parseExpression()      ← 入口，解析整个表达式
    │
    ▼
parseOr()              ← 逻辑或 (||)
    │
    ▼
parseAnd()             ← 逻辑与 (&&)
    │
    ▼
parseEquality()        ← 相等/不等 (==, !=)
    │
    ▼
parseRelational()      ← 比较/成员 (>, >=, <, <=, in)
    │
    ▼
parseUnary()           ← 一元非 (!)
    │
    ▼
parsePrimary()         ← 基本元素（标识符、字面量、括号、数组）
```

**运算符优先级**（从高到低）：

| 优先级 | 运算符 | 说明 |
|--------|--------|------|
| 1 | `()` `[]` | 括号、数组 |
| 2 | `!` | 逻辑非 |
| 3 | `>` `>=` `<` `<=` `in` | 比较、成员检查 |
| 4 | `==` `!=` | 相等 |
| 5 | `&&` | 逻辑与 |
| 6 | `\|\|` | 逻辑或 |

### 2.3 统一的 Value 类型系统

所有表达式求值结果统一为 `Value` 类型，支持 5 种具体类型：

```kotlin
sealed interface Value : Comparable<Value> {
    fun isTruthy(): Boolean

    data class Bool(val value: Boolean) : Value {
        override fun isTruthy(): Boolean = value
    }

    data class Num(val value: Double) : Value {
        override fun isTruthy(): Boolean = value != 0.0 && !value.isNaN()
    }

    data class Str(val value: String) : Value {
        override fun isTruthy(): Boolean = value.isNotEmpty()
    }

    data object Null : Value {
        override fun isTruthy(): Boolean = false
    }

    data class Array(val items: List<Value>) : Value {
        override fun isTruthy(): Boolean = items.isNotEmpty()
    }
}
```

**真值规则**：
- `Bool`: `true` 为真，`false` 为假
- `Num`: 非零且非 NaN 为真
- `Str`: 非空字符串为真
- `Null`: 始终为假
- `Array`: 非空数组为真

### 2.4 短路求值

逻辑运算符 `&&` 和 `||` 采用短路求值：

```kotlin
// && 短路：左操作数为假时直接返回假，不计算右操作数
"a && b" → 如果 a 为假，直接返回 false，不计算 b

// || 短路：左操作数为真时直接返回真，不计算右操作数
"a || b" → 如果 a 为真，直接返回 true，不计算 b
```

**设计意义**：
- 提高性能：避免不必要的计算
- 安全执行：避免在条件不满足时访问不存在的变量

### 2.5 容错设计

```kotlin
fun evaluate(expression: String, capabilities: Map<String, Any?>): Boolean {
    val trimmed = expression.trim()
    if (trimmed.isEmpty()) {
        return true  // 空表达式视为 true
    }

    return try {
        // ... 正常求值
    } catch (e: Exception) {
        AppLogger.w(TAG, "Condition evaluation failed: '$expression' (${e.message})")
        false  // 异常时返回 false
    }
}
```

**容错策略**：
- 空表达式 → `true`
- 解析异常 → `false`（记录日志）
- 运行时异常 → `false`（记录日志）

---

## 三、详细使用方法

### 3.1 基础条件评估

```kotlin
import com.ai.assistance.operit.core.tools.condition.ConditionEvaluator

// 定义能力映射
val capabilities = mapOf(
    "hasRoot" to true,
    "apiLevel" to 33,
    "deviceModel" to "Pixel 7",
    "supportedFeatures" to listOf("shell", "file", "network")
)

// 评估条件表达式
val result = ConditionEvaluator.evaluate("hasRoot", capabilities)
println(result) // true

// 比较运算
val result2 = ConditionEvaluator.evaluate("apiLevel >= 30", capabilities)
println(result2) // true

// 逻辑运算
val result3 = ConditionEvaluator.evaluate("hasRoot && apiLevel >= 30", capabilities)
println(result3) // true
```

### 3.2 比较运算

```kotlin
val capabilities = mapOf(
    "version" to 2.5,
    "count" to 100,
    "name" to "Operit"
)

// 数值比较
ConditionEvaluator.evaluate("version > 2.0", capabilities)     // true
ConditionEvaluator.evaluate("version >= 2.5", capabilities)    // true
ConditionEvaluator.evaluate("version < 3.0", capabilities)     // true
ConditionEvaluator.evaluate("version <= 2.5", capabilities)    // true

// 相等比较
ConditionEvaluator.evaluate("count == 100", capabilities)      // true
ConditionEvaluator.evaluate("count != 50", capabilities)       // true
ConditionEvaluator.evaluate("name == 'Operit'", capabilities)  // true
ConditionEvaluator.evaluate("name != 'Other'", capabilities)   // true
```

### 3.3 逻辑运算

```kotlin
val capabilities = mapOf(
    "hasRoot" to true,
    "hasShizuku" to false,
    "apiLevel" to 33
)

// 逻辑与
ConditionEvaluator.evaluate("hasRoot && apiLevel >= 30", capabilities)   // true
ConditionEvaluator.evaluate("hasRoot && hasShizuku", capabilities)       // false

// 逻辑或
ConditionEvaluator.evaluate("hasRoot || hasShizuku", capabilities)       // true
ConditionEvaluator.evaluate("hasShizuku || apiLevel < 20", capabilities) // false

// 逻辑非
ConditionEvaluator.evaluate("!hasShizuku", capabilities)                 // true
ConditionEvaluator.evaluate("!hasRoot", capabilities)                    // false

// 组合逻辑
ConditionEvaluator.evaluate("(hasRoot || hasShizuku) && apiLevel >= 30", capabilities) // true
```

### 3.4 数组成员检查

```kotlin
val capabilities = mapOf(
    "permissions" to listOf("read", "write", "execute"),
    "features" to listOf("shell", "file"),
    "role" to "admin"
)

// 检查元素是否在数组中
ConditionEvaluator.evaluate("'read' in permissions", capabilities)      // true
ConditionEvaluator.evaluate("'delete' in permissions", capabilities)    // false

// 结合其他条件
ConditionEvaluator.evaluate("'shell' in features && role == 'admin'", capabilities) // true
```

### 3.5 字面量使用

```kotlin
val capabilities = mapOf<String, Any>()

// 布尔字面量
ConditionEvaluator.evaluate("true", capabilities)   // true
ConditionEvaluator.evaluate("false", capabilities)  // false

// 数值字面量
ConditionEvaluator.evaluate("42", capabilities)     // true (非零为真)
ConditionEvaluator.evaluate("0", capabilities)      // false

// 字符串字面量
ConditionEvaluator.evaluate("'hello'", capabilities) // true (非空为真)
ConditionEvaluator.evaluate("''", capabilities)      // false

// null
ConditionEvaluator.evaluate("null", capabilities)   // false
```

### 3.6 空表达式处理

```kotlin
// 空表达式始终返回 true
ConditionEvaluator.evaluate("", capabilities)       // true
ConditionEvaluator.evaluate("  ", capabilities)      // true

// 这在包的条件配置中很有用：
// 如果条件字段为空，表示该包无条件启用
```

### 3.7 实际应用场景

#### 包的条件启用

```kotlin
// 包配置中的条件
val packageCondition = "hasRoot && apiLevel >= 30"

val deviceCapabilities = mapOf(
    "hasRoot" to RootAuthorizer.isRooted.value,
    "apiLevel" to Build.VERSION.SDK_INT,
    "deviceModel" to Build.MODEL
)

val shouldEnable = ConditionEvaluator.evaluate(packageCondition, deviceCapabilities)
if (shouldEnable) {
    enablePackage(packageName)
}
```

#### Hook 触发判断

```kotlin
// Hook 条件：只在有 Root 权限且 API 级别 >= 30 时触发
val hookCondition = "hasRoot && apiLevel >= 30"

val context = mapOf(
    "hasRoot" to hasRootAccess(),
    "apiLevel" to Build.VERSION.SDK_INT,
    "eventType" to event.type
)

if (ConditionEvaluator.evaluate(hookCondition, context)) {
    executeHook(hook)
}
```

#### 角色卡权限控制

```kotlin
// 角色卡条件：只在特定设备上可用
val roleCondition = "deviceModel == 'Pixel 7' || 'premium' in features"

val context = mapOf(
    "deviceModel" to Build.MODEL,
    "features" to getUserFeatures()
)

val allowed = ConditionEvaluator.evaluate(roleCondition, context)
```

---

## 四、支持的表达式语法

### 4.1 标识符

- 以字母或下划线开头
- 后续可跟字母、数字、下划线、点号
- 用于引用 capabilities 中的变量

```
hasRoot
api_level
device.model
```

### 4.2 字面量

| 类型 | 示例 | 说明 |
|------|------|------|
| 字符串 | `"hello"` `'world'` | 单引号或双引号包裹 |
| 数字 | `42` `3.14` | 整数或浮点数 |
| 布尔 | `true` `false` | 布尔值 |
| null | `null` | 空值 |
| 数组 | `[1, 2, 3]` `['a', 'b']` | 方括号包裹的元素列表 |

### 4.3 运算符

| 运算符 | 说明 | 示例 |
|--------|------|------|
| `==` | 相等 | `a == 5` |
| `!=` | 不等 | `a != 5` |
| `>` | 大于 | `a > 5` |
| `>=` | 大于等于 | `a >= 5` |
| `<` | 小于 | `a < 5` |
| `<=` | 小于等于 | `a <= 5` |
| `in` | 成员检查 | `'x' in arr` |
| `&&` | 逻辑与 | `a && b` |
| `\|\|` | 逻辑或 | `a \|\| b` |
| `!` | 逻辑非 | `!a` |

### 4.4 括号

- 圆括号 `()` 用于分组和改变优先级
- 方括号 `[]` 用于定义数组字面量

```
(a || b) && c
['read', 'write', 'execute']
```

---

## 五、Value 类型转换规则

### 5.1 fromAny 转换

```kotlin
Value.fromAny(v: Any?) → Value

null          → Value.Null
Boolean       → Value.Bool
Number        → Value.Num
CharSequence  → Value.Str
Enum          → Value.Str (使用 name)
List          → Value.Array
Array         → Value.Array
其他           → Value.Str (toString())
```

### 5.2 比较规则

| 左操作数 | 右操作数 | 比较方式 |
|---------|---------|---------|
| Num | Num | 数值比较 |
| Num | Bool | Bool 转数值 (true=1, false=0) 后比较 |
| Bool | Num | Bool 转数值后比较 |
| Bool | Bool | 转数值后比较 |
| Str | Str | 字符串字典序比较 |
| 其他组合 | | 抛出异常 |

### 5.3 相等规则

- 同类型：值相等则相等
- 不同类型：不相等
- Null 只与 Null 相等

---

## 六、错误处理

### 6.1 常见错误

| 错误场景 | 行为 | 日志 |
|---------|------|------|
| 空表达式 | 返回 true | 无 |
| 语法错误 | 返回 false | `Condition evaluation failed: ...` |
| 未定义变量 | 返回 false | `Condition evaluation failed: ...` |
| 类型不匹配 | 返回 false | `Condition evaluation failed: ...` |
| 无效字符 | 返回 false | `Condition evaluation failed: ...` |

### 6.2 调试技巧

```kotlin
// 查看详细错误信息
val expression = "hasRoot && undefinedVar > 5"
val result = ConditionEvaluator.evaluate(expression, capabilities)
// 如果返回 false，查看日志：
// W/ConditionEvaluator: Condition evaluation failed: 'hasRoot && undefinedVar > 5' (Variable undefinedVar not defined)
```

---

## 七、与 Calculator 模块的对比

| 特性 | ConditionEvaluator | Calculator (JsCalculator) |
|------|-------------------|--------------------------|
| **目的** | 布尔条件判断 | 数值计算 |
| **返回值** | Boolean | Double |
| **表达式类型** | 条件表达式 | 数学表达式 |
| **运算符** | 逻辑/比较 | 算术/数学函数 |
| **变量系统** | 外部传入 Map | 内部维护 |
| **赋值** | 不支持 | 支持 |
| **函数调用** | 不支持 | 支持 |
| **复杂度** | 轻量级 | 较复杂 |

---

## 八、最佳实践

### 8.1 表达式编写建议

1. **使用括号明确优先级**：
```kotlin
// 好的做法
"(hasRoot || hasShizuku) && apiLevel >= 30"

// 避免依赖默认优先级
"hasRoot || hasShizuku && apiLevel >= 30" // 可读性差
```

2. **字符串比较使用引号**：
```kotlin
// 正确
"deviceModel == 'Pixel 7'"

// 错误（Pixel 和 7 会被解析为两个标识符）
"deviceModel == Pixel 7"
```

3. **数组成员检查**：
```kotlin
// 检查权限
"'read' in permissions"

// 检查多个权限
"'read' in permissions && 'write' in permissions"
```

### 8.2 性能优化

1. **预计算 capabilities**：避免每次评估都重新构建 Map
2. **缓存结果**：对于不变的条件，缓存评估结果
3. **简化表达式**：避免过深的嵌套

### 8.3 安全建议

1. **验证输入**：对用户输入的条件表达式进行验证
2. **限制复杂度**：避免过长的表达式
3. **沙箱执行**：ConditionEvaluator 本身已具备容错能力

---

## 九、总结

Condition 模块是 Operit AI 的轻量级条件表达式评估引擎，通过词法分析、语法分析和 AST 求值三阶段模型，提供了安全可靠的布尔表达式计算能力。其设计特点包括：

1. **三阶段架构**：词法分析 → 语法分析 → AST 求值
2. **递归下降解析**：清晰的运算符优先级分层
3. **统一 Value 类型**：Bool/Num/Str/Null/Array 五种类型
4. **短路求值**：逻辑运算符的短路优化
5. **容错设计**：异常时返回 false，不会崩溃
6. **零依赖**：单文件实现，无外部依赖

通过本模块，Operit AI 能够在包管理、Hook 系统、权限控制等场景中灵活地进行条件判断，为动态功能启用提供了强大的表达能力。
