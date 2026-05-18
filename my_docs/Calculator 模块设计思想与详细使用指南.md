# Calculator 模块设计思想与详细使用指南

## 一、模块概述

`calculator` 模块是 Operit AI 的**表达式计算引擎**，提供 JavaScript 风格的数学表达式解析与计算能力。该模块采用**递归下降解析器**将表达式字符串转换为抽象语法树（AST），然后通过树遍历执行计算，支持变量、函数调用、逻辑运算、日期计算、单位转换等丰富特性。

### 1.1 核心定位

- **表达式计算器**：解析并计算数学表达式字符串
- **语法解析器**：将人类可读的表达式转换为机器可执行的语法树
- **变量系统**：支持变量定义、赋值和持久化存储
- **函数库**：内置数学、日期、统计、单位转换等函数
- **JS 风格语法**：支持三元运算符、模板字符串、复合赋值等 JavaScript 特性

### 1.2 模块结构

```
calculator/
├── Calculator.kt              # 计算器门面：向后兼容的 API 封装
├── JsCalculator.kt            # JS 风格计算器：核心计算 API
├── ExpressionParser.kt        # 表达式解析器：词法分析 + 语法分析
├── ExpressionNode.kt          # 表达式节点：AST 节点定义
├── ExpressionContext.kt       # 表达式上下文：变量存储 + 函数实现
└── CalculatorTest.kt          # 测试类：功能验证示例
```

### 1.3 架构关系

```
Calculator (门面/适配器)
    │
    ├── evalExpression() → JsCalculator.evaluate()
    ├── getVariable() → JsCalculator.getVariable()
    ├── setVariable() → JsCalculator.setVariable()
    └── ...
    │
    ▼
JsCalculator (核心 API)
    │
    ├── evaluate(expression) → ExpressionParser.parse() + ExpressionNode.evaluate()
    ├── setVariable(name, value) → ExpressionContext.setVariable()
    └── calc(expression) → evaluate() + formatResult()
    │
    ▼
ExpressionParser (解析器)
    │
    ├── 词法分析：nextToken() 将字符串拆分为 Token
    └── 语法分析：parseExpression() 构建 AST
    │
    ▼
ExpressionNode (AST 节点)
    │
    ├── NumberNode, VariableNode
    ├── BinaryOperationNode, UnaryOperationNode
    ├── FunctionCallNode, TernaryOperationNode
    ├── AssignmentNode, CompoundAssignmentNode
    ├── ArrayAccessNode, TemplateStringNode
    └── evaluate() → 递归计算节点值
    │
    ▼
ExpressionContext (执行上下文)
    │
    ├── 变量存储：variables Map
    ├── 常量定义：PI, E
    ├── 函数实现：callFunction()
    └── 类型转换：coerceToNumber()
```

---

## 二、核心设计思想

### 2.1 递归下降解析器

ExpressionParser 采用经典的**递归下降解析**技术，按照运算符优先级从高到低分层解析：

```
parseExpression()      ← 入口
    │
    ▼
parseTernary()         ← 三元运算符 (?:)
    │
    ▼
parseAssignment()      ← 赋值 (=, +=, -=, *=, /=)
    │
    ▼
parseLogicalOr()       ← 逻辑或 (||)
    │
    ▼
parseLogicalAnd()      ← 逻辑与 (&&)
    │
    ▼
parseEquality()        ← 相等 (==, !=)
    │
    ▼
parseComparison()      ← 比较 (>, >=, <, <=)
    │
    ▼
parseAdditive()        ← 加减 (+, -)
    │
    ▼
parseMultiplicative()  ← 乘除模 (*, /, %)
    │
    ▼
parseExponential()     ← 指数 (**)
    │
    ▼
parseUnary()           ← 一元 (+, -, !)
    │
    ▼
parseArrayAccess()     ← 数组访问 ([])
    │
    ▼
parsePrimary()         ← 基本元素（数字、变量、函数、括号）
```

**设计优势**：
- **优先级自然表达**：高优先级运算符在解析树深层，低优先级在浅层
- **左递归消除**：通过循环而非递归处理左结合运算符
- **错误定位精确**：解析失败时可定位到具体 token 位置

### 2.2 抽象语法树（AST）

表达式被解析为树形结构，每个节点实现 `ExpressionNode` 接口：

```kotlin
sealed interface ExpressionNode {
    fun evaluate(): Double
}
```

**节点类型**：

| 节点类型 | 说明 | 示例 |
|---------|------|------|
| `NumberNode` | 数字常量 | `42`, `3.14` |
| `VariableNode` | 变量引用 | `x`, `PI` |
| `BinaryOperationNode` | 二元运算 | `a + b`, `x > y` |
| `UnaryOperationNode` | 一元运算 | `-5`, `!flag` |
| `FunctionCallNode` | 函数调用 | `sin(PI/2)`, `max(1,2,3)` |
| `TernaryOperationNode` | 三元运算 | `a > b ? 1 : 0` |
| `AssignmentNode` | 变量赋值 | `x = 10` |
| `CompoundAssignmentNode` | 复合赋值 | `x += 5` |
| `ArrayAccessNode` | 数组/字符串索引 | `arr[0]`, `"hello"[1]` |
| `TemplateStringNode` | 模板字符串 | `${a + b}` |

### 2.3 JavaScript 风格类型系统

ExpressionContext 实现了 JavaScript 风格的**弱类型转换**：

```kotlin
fun coerceToNumber(value: Any?): Double {
    return when (value) {
        null -> 0.0
        is Number -> value.toDouble()
        is Boolean -> if (value) 1.0 else 0.0
        is String -> {
            when (value.lowercase()) {
                "true" -> 1.0
                "false" -> 0.0
                "null", "undefined" -> 0.0
                "nan" -> Double.NaN
                "infinity" -> Double.POSITIVE_INFINITY
                else -> value.toDouble() // 失败返回 NaN
            }
        }
        is List<*> -> value.size.toDouble()
        else -> Double.NaN
    }
}
```

**转换规则**：
- `null` / `undefined` → `0`
- `true` → `1`, `false` → `0`
- 无效字符串 → `NaN`
- 数组 → 长度

### 2.4 门面模式适配

`Calculator` 类作为 `JsCalculator` 的**门面/适配器**，保持向后兼容：

```kotlin
class Calculator {
    companion object {
        fun evalExpression(expression: String): Double = JsCalculator.evaluate(expression)
        fun getVariable(name: String): Double? = JsCalculator.getVariable(name)
        fun setVariable(name: String, value: Double) = JsCalculator.setVariable(name, value)
        fun clearVariables() = JsCalculator.clearVariables()
        fun formatDate(date: Date, format: String) = JsCalculator.formatDate(date, format)
        fun formatResult(result: Double) = JsCalculator.formatResult(result)
        fun getSupportedUnits() = JsCalculator.getSupportedUnits()
        fun getSupportedDateFunctions() = JsCalculator.getSupportedDateFunctions()
        fun getSupportedStatFunctions() = JsCalculator.getSupportedStatFunctions()
        fun getSupportedJsFeatures() = JsCalculator.getSupportedJsFeatures()
    }
}
```

---

## 三、详细使用方法

### 3.1 基础计算

#### 使用 JsCalculator（推荐）

```kotlin
import com.ai.assistance.operit.core.tools.calculator.JsCalculator

// 计算表达式
val result = JsCalculator.evaluate("2 + 3 * 4")
println(result) // 14.0

// 计算并格式化结果
val formatted = JsCalculator.calc("2 + 3 * 4")
println(formatted) // "14"

// 复杂表达式
val complex = JsCalculator.evaluate("(2 + 3) * 4 ** 2 / (10 - 2)")
println(complex) // 10.0
```

#### 使用 Calculator（向后兼容）

```kotlin
import com.ai.assistance.operit.core.tools.calculator.Calculator

// 计算表达式
val result = Calculator.evalExpression("2 + 3 * 4")
println(result) // 14.0

// 格式化结果
val formatted = Calculator.formatResult(3.1415926)
println(formatted) // "3.141593"
```

### 3.2 变量操作

#### 设置和获取变量

```kotlin
// 设置变量
JsCalculator.setVariable("x", 10.0)
JsCalculator.setVariable("y", 20.0)

// 在表达式中使用变量
val result = JsCalculator.evaluate("x + y")
println(result) // 30.0

// 获取变量值
val xValue = JsCalculator.getVariable("x")
println(xValue) // 10.0

// 清除所有变量
JsCalculator.clearVariables()
```

#### 赋值运算

```kotlin
// 简单赋值
JsCalculator.evaluate("a = 5")      // a = 5
JsCalculator.evaluate("b = a + 3")  // b = 8

// 复合赋值
JsCalculator.evaluate("a += 2")     // a = 7 (a = a + 2)
JsCalculator.evaluate("a -= 1")     // a = 6
JsCalculator.evaluate("a *= 2")     // a = 12
JsCalculator.evaluate("a /= 3")     // a = 4
```

### 3.3 数学函数

#### 基本数学函数

```kotlin
// 三角函数
JsCalculator.evaluate("sin(PI / 2)")    // 1.0
JsCalculator.evaluate("cos(0)")          // 1.0
JsCalculator.evaluate("tan(PI / 4)")     // 1.0

// 反三角函数
JsCalculator.evaluate("asin(1)")         // 1.570796 (PI/2)
JsCalculator.evaluate("acos(1)")         // 0.0
JsCalculator.evaluate("atan(1)")         // 0.785398 (PI/4)

// 对数函数
JsCalculator.evaluate("log(100)")        // 2.0 (log10)
JsCalculator.evaluate("ln(E)")           // 1.0 (自然对数)

// 幂函数
JsCalculator.evaluate("pow(2, 10)")      // 1024.0
JsCalculator.evaluate("sqrt(16)")        // 4.0
JsCalculator.evaluate("2 ** 3")          // 8.0
JsCalculator.evaluate("2 ^ 3")           // 8.0

// 取整函数
JsCalculator.evaluate("round(3.7)")      // 4.0
JsCalculator.evaluate("floor(3.7)")      // 3.0
JsCalculator.evaluate("ceil(3.2)")       // 4.0

// 其他
JsCalculator.evaluate("abs(-5)")         // 5.0
JsCalculator.evaluate("max(1, 5, 3)")    // 5.0
JsCalculator.evaluate("min(1, 5, 3)")    // 1.0
JsCalculator.evaluate("random()")        // 随机数 [0, 1)
JsCalculator.evaluate("fact(5)")         // 120.0 (5!)
```

#### Math 对象方法

```kotlin
// 使用 Math. 前缀
JsCalculator.evaluate("Math.sin(PI / 2)")   // 1.0
JsCalculator.evaluate("Math.sqrt(16)")       // 4.0
JsCalculator.evaluate("Math.pow(2, 3)")      // 8.0
```

### 3.4 逻辑与比较运算

#### 比较运算

```kotlin
// 比较运算符返回 1.0 (true) 或 0.0 (false)
JsCalculator.evaluate("5 > 3")     // 1.0
JsCalculator.evaluate("5 < 3")     // 0.0
JsCalculator.evaluate("5 >= 5")    // 1.0
JsCalculator.evaluate("5 <= 4")    // 0.0
JsCalculator.evaluate("5 == 5")    // 1.0
JsCalculator.evaluate("5 != 3")    // 1.0
```

#### 逻辑运算

```kotlin
// 逻辑与 (&&)
JsCalculator.evaluate("1 && 1")    // 1.0
JsCalculator.evaluate("1 && 0")    // 0.0

// 逻辑或 (||)
JsCalculator.evaluate("1 || 0")    // 1.0
JsCalculator.evaluate("0 || 0")    // 0.0

// 逻辑非 (!)
JsCalculator.evaluate("!0")        // 1.0
JsCalculator.evaluate("!1")        // 0.0
```

#### 三元运算符

```kotlin
// condition ? trueValue : falseValue
JsCalculator.evaluate("5 > 3 ? 10 : 20")     // 10.0
JsCalculator.evaluate("5 < 3 ? 10 : 20")     // 20.0

// 嵌套三元
JsCalculator.evaluate("x > 0 ? (x > 10 ? 100 : 50) : 0")
```

### 3.5 日期函数

```kotlin
// 当前日期/时间
JsCalculator.evaluate("today()")     // 当前日期的天数表示
JsCalculator.evaluate("now()")       // 当前时间戳（毫秒）

// 解析日期
JsCalculator.evaluate("date(2024-01-15)")           // 日期天数
JsCalculator.evaluate("date(2024/01/15 10:30:00)") // 支持多种格式

// 日期计算
JsCalculator.evaluate("date_diff(2024-01-01, 2024-01-15)")  // 14.0
JsCalculator.evaluate("date_add(2024-01-01, 7)")            // 加7天

// 日期提取
JsCalculator.evaluate("weekday(2024-01-15)")   // 2.0 (周一=2)
JsCalculator.evaluate("month(2024-01-15)")     // 1.0
JsCalculator.evaluate("year(2024-01-15)")      // 2024.0
JsCalculator.evaluate("day(2024-01-15)")       // 15.0
```

### 3.6 统计函数

```kotlin
// 平均值
JsCalculator.evaluate("stats.mean(1, 2, 3, 4, 5)")    // 3.0

// 中位数
JsCalculator.evaluate("stats.median(1, 3, 5, 7, 9)")  // 5.0
JsCalculator.evaluate("stats.median(1, 2, 3, 4)")     // 2.5

// 最值
JsCalculator.evaluate("stats.min(5, 2, 8, 1)")        // 1.0
JsCalculator.evaluate("stats.max(5, 2, 8, 1)")        // 8.0

// 求和
JsCalculator.evaluate("stats.sum(1, 2, 3, 4, 5)")     // 15.0

// 标准差
JsCalculator.evaluate("stats.stdev(2, 4, 4, 4, 5, 5, 7, 9)")  // 2.0
```

### 3.7 单位转换

```kotlin
// 温度转换
JsCalculator.evaluate("convert(32, f, c)")     // 0.0 (32°F = 0°C)
JsCalculator.evaluate("convert(100, c, f)")    // 212.0
JsCalculator.evaluate("convert(0, c, k)")      // 273.15

// 长度转换
JsCalculator.evaluate("convert(1, km, mi)")    // 0.621371
JsCalculator.evaluate("convert(1, mi, km)")    // 1.60934
JsCalculator.evaluate("convert(1, m, ft)")     // 3.28084

// 重量转换
JsCalculator.evaluate("convert(1, kg, lb)")    // 2.20462
JsCalculator.evaluate("convert(1, lb, kg)")    // 0.453592

// 体积转换
JsCalculator.evaluate("convert(1, l, gal)")    // 0.264172
JsCalculator.evaluate("convert(1, gal, l)")    // 3.78541

// 速度转换
JsCalculator.evaluate("convert(100, kph, mph)") // 62.1371
```

### 3.8 数组和字符串操作

#### 数组字面量

```kotlin
// 创建数组
JsCalculator.evaluate("[1, 2, 3]")        // 数组对象

// 数组访问
JsCalculator.evaluate("[10, 20, 30][1]")  // 20.0

// 数组长度
JsCalculator.evaluate("[1, 2, 3].length") // 3.0
```

#### 字符串索引

```kotlin
// 字符串索引访问（返回字符 ASCII 码）
JsCalculator.evaluate("\"ABC\"[0]")   // 65.0 (A)
JsCalculator.evaluate("\"ABC\"[1]")   // 66.0 (B)
```

### 3.9 模板字符串

```kotlin
// 模板字符串（反引号包裹）
JsCalculator.evaluate("`${10 + 20}`")     // 30.0
JsCalculator.evaluate("`${2 * 3 + 4}`")   // 10.0
```

### 3.10 获取支持的功能列表

```kotlin
// 获取支持的单位
val units = JsCalculator.getSupportedUnits()
units.forEach { (category, unitList) ->
    println("$category: ${unitList.joinToString(", ")}")
}

// 获取支持的日期函数
val dateFunctions = JsCalculator.getSupportedDateFunctions()
dateFunctions.forEach { println(it) }

// 获取支持的统计函数
val statFunctions = JsCalculator.getSupportedStatFunctions()
statFunctions.forEach { println(it) }

// 获取支持的 JS 特性
val jsFeatures = JsCalculator.getSupportedJsFeatures()
jsFeatures.forEach { println(it) }
```

---

## 四、支持的运算符优先级

| 优先级 | 运算符 | 结合性 | 说明 |
|--------|--------|--------|------|
| 1 | `()` `[]` `.` | 左结合 | 括号、数组访问、属性访问 |
| 2 | `+` `-` `!` | 右结合 | 一元运算符 |
| 3 | `**` `^` | 右结合 | 指数运算 |
| 4 | `*` `/` `%` | 左结合 | 乘除模 |
| 5 | `+` `-` | 左结合 | 加减 |
| 6 | `>` `>=` `<` `<=` | 左结合 | 比较 |
| 7 | `==` `!=` | 左结合 | 相等 |
| 8 | `&&` | 左结合 | 逻辑与 |
| 9 | `\|\|` | 左结合 | 逻辑或 |
| 10 | `? :` | 右结合 | 三元运算符 |
| 11 | `=` `+=` `-=` `*=` `/=` | 右结合 | 赋值 |

---

## 五、常量定义

| 常量 | 值 | 说明 |
|------|-----|------|
| `PI` | 3.141592653589793 | 圆周率 |
| `E` | 2.718281828459045 | 自然常数 |

```kotlin
JsCalculator.evaluate("PI")           // 3.141592653589793
JsCalculator.evaluate("2 * PI * 5")   // 31.41592653589793
JsCalculator.evaluate("E ** 2")       // 7.38905609893065
```

---

## 六、错误处理

### 6.1 常见错误

| 错误场景 | 异常信息 | 解决方案 |
|---------|---------|---------|
| 未定义变量 | `Variable x not defined` | 先使用 `setVariable()` 定义变量 |
| 未知函数 | `Unknown function: xxx` | 检查函数名拼写或使用支持的函数 |
| 括号不匹配 | `Expected ')'` | 检查括号是否成对 |
| 除零 | `Infinity` / `NaN` | 添加除零检查 |
| 无效字符 | `Invalid character: x` | 检查表达式中的非法字符 |
| 日期解析失败 | `Cannot parse date: xxx` | 使用支持的日期格式 |

### 6.2 安全计算

```kotlin
try {
    val result = JsCalculator.evaluate(userInput)
    println("Result: $result")
} catch (e: IllegalArgumentException) {
    println("Error: ${e.message}")
}
```

---

## 七、测试验证

### 7.1 运行测试

```kotlin
// 运行内置测试
CalculatorTest.runTests()
```

### 7.2 测试覆盖

CalculatorTest 包含以下测试场景：

- **基本算术**：`2 + 3 * 4`, `(2 + 3) * 4`, `10 / 2 - 3`
- **指数运算**：`2 ** 3`
- **三元运算符**：`true ? 10 : 20`, `false ? 10 : 20`
- **Math 函数**：`Math.sin(Math.PI / 2)`
- **变量操作**：`x + 10`, `x *= 2`
- **模板字符串**：`${10 + 20}`
- **日期函数**：`now() > 0`

---

## 八、最佳实践

### 8.1 表达式编写建议

1. **使用括号明确优先级**：
```kotlin
// 好的做法
JsCalculator.evaluate("(a + b) * c")

// 避免依赖默认优先级
JsCalculator.evaluate("a + b * c") // 虽然正确，但可读性差
```

2. **变量预定义**：
```kotlin
// 先定义变量
JsCalculator.setVariable("price", 100.0)
JsCalculator.setVariable("taxRate", 0.08)
JsCalculator.evaluate("price * (1 + taxRate)")
```

3. **处理除零**：
```kotlin
// 使用三元运算符避免除零
JsCalculator.evaluate("b != 0 ? a / b : 0")
```

### 8.2 性能优化

1. **复用变量**：避免重复解析相同表达式
2. **批量计算**：使用统计函数处理大量数据
3. **及时清理**：使用 `clearVariables()` 释放不需要的变量

### 8.3 安全建议

1. **验证输入**：对用户输入的表达式进行验证
2. **限制复杂度**：避免过深的嵌套表达式
3. **异常处理**：始终使用 try-catch 包裹计算逻辑

---

## 九、总结

Calculator 模块是 Operit AI 的表达式计算引擎，通过递归下降解析器和抽象语法树实现了 JavaScript 风格的表达式计算。其设计特点包括：

1. **递归下降解析**：清晰的运算符优先级分层解析
2. **抽象语法树**：树形结构便于扩展和维护
3. **JS 风格语法**：支持三元运算符、模板字符串、复合赋值等
4. **丰富函数库**：数学、日期、统计、单位转换等内置函数
5. **弱类型系统**：JavaScript 风格的类型自动转换
6. **门面模式**：Calculator 类提供向后兼容的 API

通过本模块，Operit AI 能够解析和执行复杂的数学表达式，为 AI 工具提供强大的计算能力支持。
