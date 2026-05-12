# QQ 发送消息 - 快速指南

## ✅ 功能已支持

Operit AI **已经可以**调起 QQ 并发送消息！

## 快速使用

### 步骤 1：告诉 AI 你要发送 QQ 消息

在 Operit AI 对话中输入：
```
用 QQ 帮我发一条消息
```

### 步骤 2：提供消息内容

AI 会问你要发送什么，例如：
```
今晚一起吃饭
```

### 步骤 3：完成发送

AI 会自动：
1. ✅ 打开 QQ 分享界面
2. ✅ 预填消息内容（"今晚一起吃饭"）

你需要：
1. 👆 手动选择联系人
2. ✅ 点击发送按钮

## 技术细节

### 功能名称
`qq_send_message`

### 实现位置
- 文件：`app/src/main/assets/packages/daily_life.js`
- 行号：790-818

### Intent 配置
```kotlin
Action: android.intent.action.SEND
Type: text/plain
Component: com.tencent.mobileqq/com.tencent.mobileqq.activity.JumpActivity
Extra: android.intent.extra.TEXT = 消息内容
```

## 限制说明

❌ **不能自动完成的操作**：
- 选择联系人（必须手动）
- 确认发送（必须手动）

✅ **可以自动完成的操作**：
- 打开 QQ
- 预填消息内容
- 进入分享界面

## 示例代码

```javascript
// JavaScript 调用
await Tools.DailyLife.qq_send_message({
    message: "你好，这是一条测试消息"
});
```

## 类似功能

Operit AI 还支持：
- ✅ 微信发送消息：`wechat_send_message`
- ✅ 微信朋友圈：`wechat_post_moments`
- ✅ 发送短信：`send_sms`
- ✅ 拨打电话：`make_phone_call`

## 常见问题

**Q: 能自动发送给指定联系人吗？**
A: 不能，这是 QQ 的安全限制。

**Q: 支持发送图片吗？**
A: 当前版本只支持文本。

**Q: 需要 QQ 会员吗？**
A: 不需要，任何 QQ 版本都可以。

## 详细文档

完整说明请查看：
📄 [`QQ 发送消息功能说明.md`](file:///home/meizu/Documents/my_agent_projects/Operit/my_docs/QQ 发送消息功能说明.md)

## 总结

✅ **可以调起 QQ**
✅ **可以预填消息**
✅ **需要手动选择联系人**
✅ **需要手动确认发送**

这就是目前 Android 平台上最安全、最可靠的 QQ 消息发送方式！
