# Playwright 微信文件传输助手自动上传

## 目标
编译成功后，自动将两个 APK 文件上传到微信文件传输助手，发送给用户。

## 文件位置
- 老人端：`/Users/zhou/Desktop/跌倒宝-老人端-v134.apk`
- 子女端：`/Users/zhou/Desktop/亲情守护-子女端-v17.apk`

## 方案演进

### ❌ 失败方案记录
1. **CDP DOM.setInputFiles** — Chrome 148 已废弃此命令
2. **CDP Page.setFileInputFiles (page-level)** — 不存在
3. **CDP Page.setFileInputFiles (browser-level)** — 不存在
4. **Runtime.evaluate + DataTransfer API** — 无法赋值真实文件内容
5. **xbrowser skill** — 无文件上传命令
6. **OpenClaw browser 工具 (profile="user")** — SSRF policy 阻止域名访问
7. **Node.js raw CDP via HTTP POST** — CDP 只支持 WebSocket，不支持 POST
8. **第三方微信库 (itchat/wxpy)** — 需要扫码登录，不适合自动化

### ✅ 正确方案：Playwright 直接启动 Chrome
- Playwright 原生支持 `page.setInputFiles()` 和 `page.waitForEvent('filechooser')`
- 使用 `chromium.launchPersistentContext(userDataDir)` 启动 Chrome，复用用户的登录态
- 用户数据目录：`/Users/zhou/Library/Application Support/Google/Chrome`

## 当前状态

### Playwright 安装进度
- ✅ `npm install playwright` 完成（/tmp 目录）
- ⏳ **浏览器二进制下载中** — `npx playwright install chromium` 正在运行
  - 进程：`fresh-reef` (pid 31362)
  - 预计需要 1-3 分钟（Chromium ~150MB）

### 脚本已就绪
文件：`/tmp/upload_simple.js`

```javascript
const { chromium } = require('playwright');

(async () => {
  const userDataDir = '/Users/zhou/Library/Application Support/Google/Chrome';
  const browser = await chromium.launchPersistentContext(userDataDir, {
    headless: false,
    args: ['--no-sandbox'],
    viewport: { width: 1200, height: 800 },
  });
  
  const page = browser.pages()[0] || (await browser.newPage());
  await page.goto('https://filehelper.weixin.qq.com/', { waitUntil: 'networkidle', timeout: 30000 });
  
  // 上传第一个文件
  await page.setInputFiles('input[type="file"]', '/Users/zhou/Desktop/跌倒宝-老人端-v134.apk');
  await page.waitForTimeout(2000);
  await page.click('::-p-text("发送")');
  
  // 上传第二个文件
  await page.setInputFiles('input[type="file"]', '/Users/zhou/Desktop/亲情守护-子女端-v17.apk');
  await page.waitForTimeout(2000);
  await page.click('::-p-text("发送")');
  
  console.log('DONE! 两个APK已发送');
  await page.waitForTimeout(600000); // 保持打开
})();
```

## 下一步（按序执行）

1. **等待** `npx playwright install chromium` 完成
2. **关闭** 正在运行的 Chrome（`pkill -x "Google Chrome"`）
3. **执行** `node /tmp/upload_simple.js`
4. **验证** 微信文件传输助手是否显示文件已发送

## 关键经验教训

1. **不要过度设计** — 用户说"用 Playwright，不就是点击吗"，意思是直接用 Playwright 高层 API，不要绕 CDP 底层
2. **Playwright 原生支持文件上传** — `setInputFiles()` 是直接可用的 API，不需要模拟文件对话框
3. **复用登录态** — `launchPersistentContext` + 用户数据目录 = 免扫码登录
4. **浏览器二进制需要单独安装** — `npm install playwright` 只装了 JS 库，浏览器二进制要用 `npx playwright install` 下载

## 后续集成

将此脚本集成到编译流程：
1. 编译成功 → 推送 APK 到 K70 ✅（已有）
2. 编译成功 → 复制 APK 到桌面 ✅（已有）
3. **编译成功 → 自动运行 `node /tmp/upload_simple.js`** ← 新增

修改为 `OPERATIONS.md` 的编译推送章节，加入自动上传步骤。

---

*创建时间：2026-05-18 10:36*
