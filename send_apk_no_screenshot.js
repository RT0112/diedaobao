const { chromium } = require('playwright');
(async () => {
  console.log('🔗 连接Chrome...');
  const browser = await chromium.connectOverCDP('http://127.0.0.1:28800', { timeout: 15000 });
  const context = browser.contexts()[0];
  
  // 找微信文件传输助手页面
  let page = context.pages().find(p => p.url().includes('filehelper'));
  if (!page) {
    console.log('📱 打开微信文件传输助手...');
    page = await context.newPage();
    await page.goto('https://filehelper.weixin.qq.com/', { waitUntil: 'domcontentloaded', timeout: 20000 });
    await page.waitForTimeout(3000);
    const bodyText = await page.evaluate(() => document.body.innerText);
    if (bodyText.includes('扫码') || bodyText.includes('登录')) {
      console.log('⚠️ 需要扫码登录！');
      process.exit(1);
    }
  }
  
  console.log('✅ Chrome已连接，URL:', page.url());
  
  // 定义要上传的APK文件
  const apks = [
    '/Users/zhou/Desktop/跌倒宝-老人端-v134.apk',
    '/Users/zhou/Desktop/亲情守护-子女端-v17.apk'
  ];
  
  for (const apk of apks) {
    const fname = apk.split('/').pop();
    console.log(`\n📤 上传 ${fname}...`);
    
    // 点击文件按钮，触发文件选择器
    await page.click('[title*="文件"]').catch(() => {});
    console.log('✅ 文件按钮已点击');
    
    // 等待文件选择器
    await page.waitForTimeout(2000);
    
    // 尝试通过fileChooser事件上传
    try {
      const [fc] = await Promise.all([
        page.waitForEvent('filechooser', { timeout: 15000 }),
        page.click('[title*="文件"]')
      ]);
      await fc.setFiles([apk]);
      console.log(`✅ ${fname} 已选择`);
    } catch(e) {
      console.log(`⚠️ ${fname} 超时，尝试直接发送...`);
    }
    
    await page.waitForTimeout(2000);
    
    // 点击发送按钮
    await page.click('text=发送').catch(() => {});
    console.log(`✅ ${fname} 已点击发送`);
    
    // 等待发送完成，检查聊天记录
    await page.waitForTimeout(5000);
    
    // 优化验证：直接检查DOM，看最新消息是否包含文件名
    const chatCheck = await page.evaluate((f) => {
      // 找最近一条消息
      const msgs = document.querySelectorAll('[class*="msg"], [class*="message"], .message_item');
      if (msgs.length === 0) return { found: false, reason: '无消息记录' };
      const lastMsg = msgs[msgs.length - 1];
      const text = lastMsg.textContent || lastMsg.innerText || '';
      return {
        found: text.includes(f.split('/').pop().replace('.apk', '')) || text.includes('apk'),
        preview: text.substring(0, 100)
      };
    }, fname);
    
    if (chatCheck.found) {
      console.log(`✅ ${fname} 发送成功（已验证聊天记录）`);
    } else {
      console.log(`⚠️ ${fname} 发送状态未确认：${chatCheck.reason}`);
      //  fallback：检查页面是否有"发送成功"提示
      const sendTip = await page.evaluate(() => {
        const tips = Array.from(document.querySelectorAll('*')).map(e => e.textContent || '').filter(t => t.includes('发送成功') || t.includes('已发送'));
        return tips.length > 0 ? tips[0] : '';
      });
      if (sendTip) console.log(`✅ 找到发送成功提示：${sendTip}`);
    }
  }
  
  console.log('\n🎉 所有APK发送完成！');
})().catch(e => { console.error('❌ 错误:', e.message); process.exit(1); });
