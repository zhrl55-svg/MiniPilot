package com.openminis.app.tools

import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * [T-android-tool-error-semantics] Say WHICH transport failure happened.
 *
 * The 2026-11-20 self-diagnosis caught the agent burning turns on
 * `web_search` / `news_search`: both tools reported every failure as
 * "网络或超时", so a 422 (bad request / missing credentials) and a source
 * that is simply unreachable looked identical and the agent chased the wrong
 * cause. A generic "network" message is a dead end for the caller.
 *
 * The rules this encodes:
 *   - timeout   → the request never completed; look at the path (proxy, VPN)
 *   - DNS       → the name did not resolve; the sandbox has no working resolver
 *   - refused   → the host answered "no"
 *   - TLS       → certificate / interception
 *   - anything else → name the exception class, never hide it
 *
 * The message stays actionable: it names the host, and it says what to do
 * next rather than leaving the agent to guess.
 */
object NetworkFailure {

    /**
     * @param service tool-facing name, e.g. "网页搜索"
     * @param host    the endpoint host, when known
     */
    fun describe(throwable: Throwable, service: String, host: String? = null): String {
        val where = host?.let { "（$it）" } ?: ""
        val cause = rootCause(throwable)
        return when (cause) {
            is SocketTimeoutException ->
                "$service 请求超时$where：是网络路径/代理/出口问题，不是「没有结果」，也不是数据为空。" +
                    "下一步：用 shell_execute 的 curl 直连同一端点看 HTTP 状态码（000/超时=出口不可达，" +
                    "4xx=请求或凭据问题），不要按「网络异常」反复重试。"

            is UnknownHostException ->
                "$service 域名解析失败$where（${cause.message ?: "未知主机"}）：沙箱的 DNS 不可用或被改写。" +
                    "下一步：先 curl 一下该域名确认解析，再决定是修代理/出口还是换源。"

            is ConnectException ->
                "$service 连接被拒$where：目标端口没连上（服务未监听或被防火墙拦截）。" +
                    "下一步：curl 同一端点确认端口可达性，别重试同一次请求。"

            is PortUnreachableException, is NoRouteToHostException ->
                "$service 网络不可达$where：没有到目标主机的路由。" +
                    "下一步：换源或检查出口，先用 curl 验证端点是否可达。"

            is SSLException ->
                "$service TLS/证书错误$where：握手失败（证书无效或被代理拦截）。" +
                    "下一步：用 curl -v 同一端点看证书链，别当成「没搜到」。"

            is InterruptedIOException ->
                "$service 请求被中断$where（${cause.javaClass.simpleName}）。"

            is IOException ->
                "$service 网络请求失败$where（${cause.javaClass.simpleName}：${cause.message ?: "无详细信息"}）。" +
                    "下一步：用 curl 直连同一端点区分是网络还是请求本身的问题。"

            else ->
                "$service 调用失败$where（${cause.javaClass.simpleName}：${cause.message ?: "无详细信息"}）。"
        }
    }

    /** The innermost cause — OkHttp wraps everything several layers deep. */
    fun rootCause(t: Throwable): Throwable {
        var current = t
        var guard = 0
        while (current.cause != null && current.cause !== current && guard < 8) {
            current = current.cause!!
            guard++
        }
        return current
    }
}
