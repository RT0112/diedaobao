package com.falldetector.diedaobao.config

/**
 * 服务器配置 - 全局唯一URL定义
 * 改隧道地址只改这里，所有组件自动生效
 */
object ServerConfig {
    // ⚠️ 改这里就行！
    val BASE_URL = "https://6e2b7f6238485d.lhr.life"
    
    // 派生URL（自动从BASE_URL计算，不需要改）
    val WS_URL = "wss://${BASE_URL.substringAfter("://")}/ws"
    val FEEDBACK_URL = "$BASE_URL/feedback"
    val UPLOAD_LOG_URL = "$BASE_URL/upload-log"
    val REMOTE_ASSIST_URL = "$BASE_URL/remote-assist"
}
