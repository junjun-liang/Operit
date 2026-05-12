# Operit AI QQ 发送消息功能说明

## ✅ 功能状态

**Operit AI 已经支持通过 QQ 发送消息！**

该功能已内置在 `daily_life.js` 工具包中，可以直接使用。

## 功能描述

### 功能名称
`qq_send_message`

### 功能说明
通过 QQ 发送文本消息，调起 QQ 分享界面，由用户选择联系人并确认发送。

**注意**：出于安全考虑，该功能**不会自动发送消息**，而是：
1. ✅ 打开 QQ 分享界面
2. ✅ 预填消息内容
3. ✅ 由**用户手动选择联系人**
4. ✅ 由**用户手动确认发送**

这是 Android 系统的安全限制，防止恶意应用自动发送消息。

## 使用方法

### 方式一：通过 AI 对话调用

在 Operit AI 的对话中，直接告诉 AI 你要发送 QQ 消息：

**示例对话**：
```
用户：帮我用 QQ 发一条消息给好友
AI：好的，请告诉我您要发送什么内容？
用户：今晚一起吃饭
AI：好的，正在调起 QQ...
（自动打开 QQ 分享界面，预填"今晚一起吃饭"）
（用户选择联系人并确认发送）
```

### 方式二：通过工具直接调用

如果你直接使用工具系统调用：

```javascript
// 调用示例
const result = await Tools.DailyLife.qq_send_message({
    message: "你好，这是一条测试消息"
});

console.log(result);
// 输出：
// {
//     success: true,
//     message: "已打开 QQ 分享界面，请选择联系人并确认发送",
//     content_preview: "你好，这是一条测试消息"
// }
```

### 方式三：通过 Tasker 等自动化应用

如果你使用 Tasker 等自动化工具：

1. 在 Tasker 中创建任务
2. 添加 Operit AI 的插件动作
3. 选择 `qq_send_message` 工具
4. 传入参数：`message`

## 技术实现

### Intent 配置

```javascript
const intent = new Intent("android.intent.action.SEND");
intent.setType("text/plain");
intent.putExtra("android.intent.extra.TEXT", params.message);
intent.setComponent("com.tencent.mobileqq", "com.tencent.mobileqq.activity.JumpActivity");
intent.addFlag(268435456 /* IntentFlag.ACTIVITY_NEW_TASK */);
const result = await intent.start();
```

### 参数说明

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `message` | string | ✅ 是 | 要发送的文本内容 |

### 返回值

```typescript
{
    success: boolean,      // 是否成功
    message: string,       // 结果描述
    content_preview: string,  // 消息内容预览（超过 30 字符会截断）
    raw_result?: any       // 原始 Intent 结果（可选）
}
```

## 使用流程

```
1. 用户调用 qq_send_message
       ↓
2. Operit AI 创建 Intent
       ↓
3. 启动 QQ JumpActivity
       ↓
4. QQ 显示分享界面（预填消息）
       ↓
5. 用户选择联系人
       ↓
6. 用户确认发送
       ↓
7. 消息发送成功
```

## 注意事项

### ⚠️ 限制

1. **不能自动选择联系人**
   - 必须由用户手动选择
   - 这是 QQ 的安全限制

2. **不能自动确认发送**
   - 必须由用户点击发送按钮
   - 防止恶意应用自动发消息

3. **需要安装 QQ**
   - 手机上必须安装 QQ 应用
   - 否则会报错

4. **消息长度限制**
   - QQ 本身有消息长度限制
   - 建议控制在 2000 字符以内

### ✅ 最佳实践

1. **消息内容简洁**
   ```javascript
   // 推荐
   message: "今晚 7 点老地方见"
   
   // 不推荐（太长）
   message: "这是一条非常非常长的消息，有几百个字..."
   ```

2. **提前告知用户**
   ```
   AI：我将为您调起 QQ 发送消息，请选择联系人并确认发送
   ```

3. **错误处理**
   ```javascript
   try {
       const result = await Tools.DailyLife.qq_send_message({
           message: "测试消息"
       });
       if (result.success) {
           console.log("成功：" + result.message);
       } else {
           console.log("失败：" + result.message);
       }
   } catch (error) {
       console.error("调用失败：" + error.message);
   }
   ```

## 类似功能

Operit AI 还提供了其他类似功能：

### 1. 微信发送消息
```javascript
wechat_send_message({
    message: "消息内容"
})
```

### 2. 微信朋友圈
```javascript
wechat_post_moments({
    message: "朋友圈内容"
})
```

### 3. 发送短信
```javascript
send_sms({
    phone_number: "13800138000",
    message: "短信内容"
})
```

### 4. 拨打电话
```javascript
make_phone_call({
    phone_number: "13800138000",
    emergency: false
})
```

## 完整示例

### 示例 1：简单的 QQ 消息

```javascript
// 在 Operit AI 对话中
用户：用 QQ 帮我发"明天见"给朋友
AI：好的，正在调起 QQ...
（打开 QQ 分享界面，预填"明天见"）
（用户选择联系人并发送）
```

### 示例 2：带错误处理的脚本

```javascript
async function send_qq_message(content) {
    try {
        const result = await Tools.DailyLife.qq_send_message({
            message: content
        });
        
        if (result.success) {
            return {
                status: "success",
                message: "QQ 已打开，请完成发送",
                preview: result.content_preview
            };
        } else {
            return {
                status: "error",
                message: result.message
            };
        }
    } catch (error) {
        return {
            status: "error",
            message: "调用失败：" + error.message
        };
    }
}

// 使用
const response = await send_qq_message("你好！");
console.log(response);
```

## 常见问题

### Q1: 为什么不能自动发送？
**A**: 出于安全考虑，Android 和 QQ 都不允许应用自动发送消息，防止恶意应用滥用。

### Q2: 能指定发送给某个联系人吗？
**A**: 不能。这是 QQ 的安全限制，必须由用户手动选择。

### Q3: 支持发送图片吗？
**A**: 当前版本只支持文本消息。图片消息需要额外的实现。

### Q4: 提示"未安装 QQ"怎么办？
**A**: 确保手机上已安装 QQ 应用，并且版本不是太老。

### Q5: 支持 QQ 国际版或 TIM 吗？
**A**: 当前实现针对标准版 QQ。其他版本可能需要调整 Intent 配置。

## 开发调试

### 查看日志

在 Android Studio 或命令行中查看日志：

```bash
adb logcat | grep -i "qq_send_message"
```

### 测试 Intent

可以使用 `adb` 测试 Intent：

```bash
adb shell am start -a android.intent.action.SEND \
  -t "text/plain" \
  --es android.intent.extra.TEXT "测试消息" \
  -n com.tencent.mobileqq/com.tencent.mobileqq.activity.JumpActivity
```

## 相关文件

- 工具定义：`app/src/main/assets/packages/daily_life.js`
- 实现代码：`app/src/main/assets/packages/daily_life.js:790-818`
- 工具注册：`app/src/main/assets/packages/daily_life.js:134-144`

## 更新计划

未来可能支持：
- [ ] 发送图片
- [ ] 发送文件
- [ ] 发送到指定群组（如果 QQ 开放 API）
- [ ] 支持 TIM 版本

## 总结

✅ **Operit AI 可以调起 QQ 并发送消息**
- 自动打开 QQ 分享界面
- 自动预填消息内容
- 由用户选择联系人并确认发送

⚠️ **限制**
- 不能自动选择联系人
- 不能自动确认发送
- 需要用户手动完成最后一步

这是目前 Android 平台上最安全、最可靠的 QQ 消息发送方式。
