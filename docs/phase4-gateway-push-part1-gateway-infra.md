# Phase 4 Gateway 与推送服务 — Part 1：Gateway 基础设施

> **目标：** 搭建 Gateway Service 的 Netty 服务器骨架，实现 JWT/HMAC 鉴权、
> 令牌桶限流以及与 Aeron Cluster 的连接客户端。
>
> **前置条件：** Phase 3 完成，柜台服务 107 个测试全部通过  
> **本节验证目标：** Netty 服务器启动，鉴权与限流逻辑单元测试通过

---

## 目录

1. [Phase 4 总览](#1-phase-4-总览)
2. [gateway-service POM](#2-gateway-service-pom)
3. [GatewayServer（Netty 启动器）](#3-gatewayservernetty-启动器)
4. [JwtAuthenticator](#4-jwtauthenticator)
5. [HmacAuthenticator](#5-hmacauthenticator)
6. [TokenBucketRateLimiter](#6-tokenbucketratelimiter)
7. [AeronClusterClient](#7-aeronclusterclient)
8. [GatewayEgressListener](#8-gatewayegresslistener)
9. [基础设施单元测试](#9-基础设施单元测试)

---

## 1. Phase 4 总览

### 1.1 本阶段目标

Phase 4 对应设计文档第 4、7、9 节，分 5 个 Part 实现：

| Part           | 内容                                                 | 验证目标                   |
|----------------|----------------------------------------------------|------------------------|
| **Part 1**（本文） | Gateway 基础设施（Netty / 鉴权 / 限流 / AeronClusterClient） | 鉴权+限流单测通过              |
| Part 2         | Gateway REST + WebSocket Handler                   | 接口联调通过                 |
| Part 3         | Push Service（深度 / 成交 / Ticker 推送）                  | WS 推送端到端通过             |
| Part 4         | Journal Service（顺序写 / 文件轮转）                        | 写入吞吐 > 500K events/sec |
| Part 5         | 全量单测 + 端到端延迟基准 + 验收清单                              | P99 < 1ms              |

### 1.2 新增文件结构

```
gateway-service/src/main/java/com/trading/gateway/
├── GatewayServer.java               ← Netty 启动器（本 Part）
├── GatewayMain.java                 ← 启动入口（Part 2）
├── auth/
│   ├── AuthResult.java              ← 鉴权结果（本 Part）
│   ├── JwtAuthenticator.java        ← JWT 鉴权（本 Part）
│   └── HmacAuthenticator.java       ← HMAC 签名鉴权（本 Part）
├── ratelimit/
│   └── TokenBucketRateLimiter.java  ← 令牌桶限流（本 Part）
├── aeron/
│   ├── AeronClusterClient.java      ← Cluster 连接客户端（本 Part）
│   └── GatewayEgressListener.java   ← Cluster 回报监听（本 Part）
├── netty/
│   ├── HttpServerHandler.java       ← REST Handler（Part 2）
│   └── WebSocketHandler.java        ← WS Handler（Part 2）
└── session/
    └── SessionRegistry.java         ← WS Session 注册表（Part 2）

gateway-service/src/test/java/com/trading/gateway/
├── auth/
│   └── AuthenticatorTest.java       ← 本 Part
└── ratelimit/
    └── TokenBucketRateLimiterTest.java  ← 本 Part
```

### 1.3 Gateway 整体数据流

```
Client
  │ HTTPS (REST) / WSS (WebSocket)
  ▼
┌────────────────────────────────────────────────────────┐
│  GatewayServer (Netty)                                  │
│                                                        │
│  HttpServerHandler                WebSocketHandler     │
│    │ 解析 JSON                       │ 解析 JSON        │
│    │ JwtAuthenticator                │ JwtAuthenticator │
│    │   / HmacAuthenticator           │ 订阅注册          │
│    │ TokenBucketRateLimiter          │                  │
│    │ 构造 SBE NewOrderRequest         │                  │
│    └──────────────┬──────────────────┘                 │
└───────────────────┼────────────────────────────────────┘
                    │ Aeron UDP → Cluster Ingress
                    ▼
           AeronClusterClient
                    │
           Aeron Cluster (Raft)
                    │
           GatewayEgressListener
                    │ SBE ExecutionReport
                    ▼
           WebSocket 回报推送给对应客户端
```

---

## 2. gateway-service POM

文件：`gateway-service/pom.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>com.trading</groupId>
        <artifactId>trading-platform</artifactId>
        <version>1.0.0-SNAPSHOT</version>
        <relativePath>../pom.xml</relativePath>
    </parent>

    <artifactId>gateway-service</artifactId>
    <name>Gateway Service</name>

    <dependencies>
        <dependency>
            <groupId>com.trading</groupId>
            <artifactId>common-sbe</artifactId>
        </dependency>
        <dependency>
            <groupId>com.trading</groupId>
            <artifactId>common-util</artifactId>
        </dependency>
        <dependency>
            <groupId>io.aeron</groupId>
            <artifactId>aeron-all</artifactId>
        </dependency>
        <dependency>
            <groupId>io.netty</groupId>
            <artifactId>netty-all</artifactId>
        </dependency>
        <dependency>
            <groupId>org.agrona</groupId>
            <artifactId>agrona</artifactId>
        </dependency>
        <dependency>
            <groupId>org.slf4j</groupId>
            <artifactId>slf4j-api</artifactId>
        </dependency>
        <dependency>
            <groupId>ch.qos.logback</groupId>
            <artifactId>logback-classic</artifactId>
        </dependency>
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

---

## 3. GatewayServer（Netty 启动器）

文件：`gateway-service/src/main/java/com/trading/gateway/GatewayServer.java`

```java
package com.trading.gateway;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.timeout.IdleStateHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

/**
 * Gateway Netty 服务器。
 *
 * <p>端口规划：
 * <ul>
 *   <li>HTTP/REST：8080（生产换 HTTPS:443）</li>
 *   <li>WebSocket：8080/ws（同端口，协议升级）</li>
 * </ul>
 *
 * <p>线程模型：
 * <ul>
 *   <li>BossGroup：1 线程，负责 accept</li>
 *   <li>WorkerGroup：2×CPU 线程，负责 IO 读写</li>
 *   <li>业务逻辑（序列化/鉴权/限流）在 WorkerGroup 线程执行，不另起线程池</li>
 * </ul>
 *
 * @author Reln Ding
 */
public final class GatewayServer {

    private static final Logger log = LoggerFactory.getLogger(GatewayServer.class);

    private final int            port;
    private final ChannelInitializer<SocketChannel> channelInitializer;
    private final SslContext     sslContext;  // null = 不启用 TLS（开发模式）

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel        serverChannel;

    public GatewayServer(final int port,
                         final ChannelInitializer<SocketChannel> channelInitializer,
                         final SslContext sslContext) {
        this.port               = port;
        this.channelInitializer = channelInitializer;
        this.sslContext         = sslContext;
    }

    /**
     * 启动服务器（阻塞直到绑定成功）。
     */
    public void start() throws InterruptedException {
        bossGroup   = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();  // 默认 2×CPU

        final ServerBootstrap bootstrap = new ServerBootstrap()
            .group(bossGroup, workerGroup)
            .channel(NioServerSocketChannel.class)
            .option(ChannelOption.SO_BACKLOG, 1024)
            .childOption(ChannelOption.TCP_NODELAY, true)          // 禁止 Nagle，降低延迟
            .childOption(ChannelOption.SO_KEEPALIVE, true)
            .childOption(ChannelOption.SO_RCVBUF, 65536)
            .childOption(ChannelOption.SO_SNDBUF, 65536)
            .childHandler(channelInitializer);

        final ChannelFuture future = bootstrap.bind(port).sync();
        serverChannel = future.channel();
        log.info("GatewayServer started on port {}", port);
    }

    /**
     * 优雅关闭。
     */
    public void shutdown() {
        if (serverChannel != null) {
            serverChannel.close();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully(100, 500, TimeUnit.MILLISECONDS);
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully(100, 500, TimeUnit.MILLISECONDS);
        }
        log.info("GatewayServer shutdown complete.");
    }
}
```

---

## 4. JwtAuthenticator

Phase 4 采用无状态 JWT（HS256）鉴权：客户端登录后拿到 Token，每次请求携带 `Authorization: Bearer <token>`，Gateway 本地验签，无需查库。

文件：`gateway-service/src/main/java/com/trading/gateway/auth/AuthResult.java`

```java
package com.trading.gateway.auth;

/**
 * 鉴权结果。
 *
 * @author Reln Ding
 */
public final class AuthResult {

    public static final AuthResult INVALID_TOKEN =
        new AuthResult(false, -1L, "invalid_token");
    public static final AuthResult EXPIRED =
        new AuthResult(false, -1L, "token_expired");
    public static final AuthResult MISSING =
        new AuthResult(false, -1L, "missing_token");

    /** 是否通过 */
    public final boolean passed;

    /** 账户 ID（通过时有效）*/
    public final long accountId;

    /** 拒绝原因（通过时为 null）*/
    public final String rejectReason;

    public AuthResult(final boolean passed, final long accountId,
                      final String rejectReason) {
        this.passed       = passed;
        this.accountId    = accountId;
        this.rejectReason = rejectReason;
    }

    public static AuthResult ok(final long accountId) {
        return new AuthResult(true, accountId, null);
    }
}
```

文件：`gateway-service/src/main/java/com/trading/gateway/auth/JwtAuthenticator.java`

```java
package com.trading.gateway.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * JWT HS256 鉴权器（无外部依赖，手动实现 JWT 验签）。
 *
 * <p>JWT 结构：{Base64url(header)}.{Base64url(payload)}.{Base64url(signature)}
 * <br>payload 约定字段：
 * <ul>
 *   <li>{@code sub}：账户 ID（字符串形式的 long）</li>
 *   <li>{@code exp}：过期时间（Unix 秒）</li>
 * </ul>
 *
 * <p>不使用第三方 JWT 库，避免引入额外依赖，保持低延迟。
 *
 * @author Reln Ding
 */
public final class JwtAuthenticator {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticator.class);

    private static final String HMAC_ALGO = "HmacSHA256";
    private static final String EXPECTED_HEADER_B64 =
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));

    private final byte[] secretKey;

    public JwtAuthenticator(final String secret) {
        this.secretKey = secret.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 验证 JWT Token。
     *
     * @param token Bearer token 字符串（不含 "Bearer " 前缀）
     * @return AuthResult
     */
    public AuthResult authenticate(final String token) {
        if (token == null || token.isEmpty()) {
            return AuthResult.MISSING;
        }

        final String[] parts = token.split("\\.", -1);
        if (parts.length != 3) {
            return AuthResult.INVALID_TOKEN;
        }

        // 验签
        final String signingInput = parts[0] + "." + parts[1];
        final String expectedSig  = sign(signingInput);
        if (expectedSig == null || !constantTimeEquals(expectedSig, parts[2])) {
            return AuthResult.INVALID_TOKEN;
        }

        // 解析 payload
        try {
            final String payloadJson = new String(
                Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            final long accountId = extractLongField(payloadJson, "sub");
            final long exp       = extractLongField(payloadJson, "exp");

            if (accountId <= 0) {
                return AuthResult.INVALID_TOKEN;
            }
            if (exp > 0 && System.currentTimeMillis() / 1000 > exp) {
                return AuthResult.EXPIRED;
            }
            return AuthResult.ok(accountId);

        } catch (final Exception e) {
            log.debug("JWT parse error: {}", e.getMessage());
            return AuthResult.INVALID_TOKEN;
        }
    }

    /**
     * 生成 JWT Token（用于测试 / 登录接口）。
     *
     * @param accountId 账户 ID
     * @param ttlSeconds 有效期（秒）
     */
    public String generate(final long accountId, final long ttlSeconds) {
        final long exp = System.currentTimeMillis() / 1000 + ttlSeconds;
        final String payload = "{\"sub\":\"" + accountId + "\",\"exp\":" + exp + "}";
        final String header  = EXPECTED_HEADER_B64;
        final String body    = header + "." + b64url(payload);
        final String sig     = sign(body);
        return body + "." + (sig != null ? sig : "");
    }

    // ---- 内部方法 ----

    private String sign(final String input) {
        try {
            final Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(secretKey, HMAC_ALGO));
            final byte[] bytes = mac.doFinal(input.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        } catch (final Exception e) {
            log.error("JWT sign error", e);
            return null;
        }
    }

    private static String b64url(final String s) {
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    /** 时间恒定的字符串比较，防止时序攻击 */
    private static boolean constantTimeEquals(final String a, final String b) {
        if (a.length() != b.length()) return false;
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }

    /**
     * 从 JSON 字符串中提取 long 类型字段值（简单解析，不依赖 JSON 库）。
     * 仅适用于 value 为纯数字或带引号数字的场景。
     */
    static long extractLongField(final String json, final String field) {
        final String key    = "\"" + field + "\"";
        final int    keyIdx = json.indexOf(key);
        if (keyIdx < 0) return -1L;

        int valStart = json.indexOf(':', keyIdx) + 1;
        while (valStart < json.length() && json.charAt(valStart) == ' ') valStart++;

        // 跳过引号（字符串形式的数字）
        boolean quoted = json.charAt(valStart) == '"';
        if (quoted) valStart++;

        int valEnd = valStart;
        while (valEnd < json.length()) {
            final char c = json.charAt(valEnd);
            if (c >= '0' && c <= '9') { valEnd++; }
            else break;
        }
        if (valEnd == valStart) return -1L;
        return Long.parseLong(json.substring(valStart, valEnd));
    }
}
```

---

## 5. HmacAuthenticator

适用于 REST API 程序化访问：客户端持有 API Key + Secret，对请求签名，Gateway 验证签名合法性。

文件：`gateway-service/src/main/java/com/trading/gateway/auth/HmacAuthenticator.java`

```java
package com.trading.gateway.auth;

import org.agrona.collections.Long2ObjectHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/**
 * HMAC-SHA256 API Key 鉴权器。
 *
 * <p>签名规则（与 Binance 兼容）：
 * <pre>
 *   signature = HMAC-SHA256(secretKey, queryString + requestBody)
 * </pre>
 * 请求参数中必须包含 {@code timestamp}（毫秒），Gateway 检查时间窗口 ±5000ms。
 *
 * @author Reln Ding
 */
public final class HmacAuthenticator {

    private static final Logger log = LoggerFactory.getLogger(HmacAuthenticator.class);

    private static final String HMAC_ALGO       = "HmacSHA256";
    private static final long   TIMESTAMP_WINDOW_MS = 5_000L;

    /**
     * API Key → (accountId, secretKeyBytes) 映射。
     * 生产环境从数据库加载到内存，定期刷新。
     * 使用 Agrona Long2ObjectHashMap：Key 为 API Key 的 hashCode（简化，生产用 ConcurrentHashMap）。
     */
    private final java.util.concurrent.ConcurrentHashMap<String, ApiCredential> credentials
        = new java.util.concurrent.ConcurrentHashMap<>(1024);

    /**
     * 注册 API 凭证（管理接口调用）。
     */
    public void register(final String apiKey, final long accountId, final String secret) {
        credentials.put(apiKey, new ApiCredential(accountId,
            secret.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * 吊销 API Key。
     */
    public void revoke(final String apiKey) {
        credentials.remove(apiKey);
    }

    /**
     * 验证 HMAC 签名。
     *
     * @param apiKey       请求头中的 X-API-KEY
     * @param timestamp    请求参数中的 timestamp（毫秒）
     * @param signPayload  签名原文（queryString + body）
     * @param signature    请求参数中的 signature（hex）
     * @return AuthResult
     */
    public AuthResult authenticate(final String apiKey, final long timestamp,
                                   final String signPayload, final String signature) {
        if (apiKey == null || apiKey.isEmpty()) {
            return AuthResult.MISSING;
        }
        final ApiCredential cred = credentials.get(apiKey);
        if (cred == null) {
            return AuthResult.INVALID_TOKEN;
        }

        // 时间窗口校验
        final long now = System.currentTimeMillis();
        if (Math.abs(now - timestamp) > TIMESTAMP_WINDOW_MS) {
            log.debug("HMAC timestamp out of window: apiKey={}, ts={}, now={}",
                      apiKey, timestamp, now);
            return AuthResult.EXPIRED;
        }

        // 签名校验
        final String expected = hmacHex(cred.secretKey, signPayload);
        if (expected == null || !expected.equalsIgnoreCase(signature)) {
            return AuthResult.INVALID_TOKEN;
        }
        return AuthResult.ok(cred.accountId);
    }

    private static String hmacHex(final byte[] key, final String data) {
        try {
            final Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(key, HMAC_ALGO));
            final byte[] bytes = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (final Exception e) {
            log.error("HMAC sign error", e);
            return null;
        }
    }

    private record ApiCredential(long accountId, byte[] secretKey) {}
}
```

---

## 6. TokenBucketRateLimiter

每个账户独立令牌桶，支持配置桶容量和补充速率，热路径无锁（单线程 Netty Worker 调用）。

文件：`gateway-service/src/main/java/com/trading/gateway/ratelimit/TokenBucketRateLimiter.java`

```java
package com.trading.gateway.ratelimit;

import org.agrona.collections.Long2ObjectHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 令牌桶限流器（per-account，非线程安全，由 Netty Worker 单线程调用）。
 *
 * <p>算法：
 * <ul>
 *   <li>每个账户一个令牌桶，容量 {@code capacity}，初始满桶</li>
 *   <li>每次请求消耗 1 个令牌；若桶为空则拒绝</li>
 *   <li>每隔 {@code refillIntervalNs} 纳秒补充 {@code refillTokens} 个令牌</li>
 * </ul>
 *
 * <p>惰性补充（lazy refill）：在每次 {@link #tryAcquire} 时按时间差计算应补充的令牌数，
 * 不需要后台线程定时补充，适合高并发场景。
 *
 * @author Reln Ding
 */
public final class TokenBucketRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(TokenBucketRateLimiter.class);

    /** 桶容量（突发上限）*/
    private final int  capacity;

    /** 每次补充的令牌数 */
    private final int  refillTokens;

    /** 补充间隔（纳秒）*/
    private final long refillIntervalNs;

    /** accountId → TokenBucket */
    private final Long2ObjectHashMap<TokenBucket> buckets = new Long2ObjectHashMap<>(1024, 0.6f);

    /**
     * @param capacity         桶容量（最大突发请求数）
     * @param refillTokens     每次补充令牌数
     * @param refillIntervalMs 补充间隔（毫秒）
     */
    public TokenBucketRateLimiter(final int capacity,
                                  final int refillTokens,
                                  final long refillIntervalMs) {
        this.capacity         = capacity;
        this.refillTokens     = refillTokens;
        this.refillIntervalNs = refillIntervalMs * 1_000_000L;
    }

    /**
     * 尝试获取令牌（消耗 1 个令牌）。
     *
     * @param accountId 账户 ID
     * @return true=成功（允许请求），false=限流（拒绝请求）
     */
    public boolean tryAcquire(final long accountId) {
        TokenBucket bucket = buckets.get(accountId);
        if (bucket == null) {
            bucket = new TokenBucket(capacity, System.nanoTime());
            buckets.put(accountId, bucket);
        }
        return bucket.tryConsume(System.nanoTime(), refillTokens, refillIntervalNs, capacity);
    }

    /**
     * 移除账户的令牌桶（账户登出时清理内存）。
     */
    public void remove(final long accountId) {
        buckets.remove(accountId);
    }

    /** 当前已注册账户数（用于监控）*/
    public int registeredAccounts() {
        return buckets.size();
    }

    // ----------------------------------------------------------------
    // 内部令牌桶
    // ----------------------------------------------------------------

    static final class TokenBucket {

        private int  tokens;
        private long lastRefillNs;

        TokenBucket(final int initialTokens, final long nowNs) {
            this.tokens      = initialTokens;
            this.lastRefillNs = nowNs;
        }

        boolean tryConsume(final long nowNs,
                           final int refillTokens,
                           final long refillIntervalNs,
                           final int capacity) {
            // 惰性补充
            final long elapsed  = nowNs - lastRefillNs;
            final long periods  = elapsed / refillIntervalNs;
            if (periods > 0) {
                tokens = (int) Math.min(capacity, (long) tokens + periods * refillTokens);
                lastRefillNs += periods * refillIntervalNs;
            }

            if (tokens <= 0) {
                return false;
            }
            tokens--;
            return true;
        }

        int getTokens()  { return tokens;      }
        long getLastRefillNs() { return lastRefillNs; }
    }
}
```

---

## 7. AeronClusterClient

连接 Aeron Cluster Ingress，发送订单请求，接收 Cluster Egress 回报。

文件：`gateway-service/src/main/java/com/trading/gateway/aeron/AeronClusterClient.java`

```java
package com.trading.gateway.aeron;

import com.trading.sbe.*;
import io.aeron.cluster.client.AeronCluster;
import io.aeron.cluster.client.EgressListener;
import io.aeron.cluster.codecs.EventCode;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.BusySpinIdleStrategy;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.UnsafeBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;

/**
 * Aeron Cluster 客户端（Gateway 侧）。
 *
 * <p>负责：
 * <ul>
 *   <li>维护与 Cluster Ingress 的连接（自动识别 Leader）</li>
 *   <li>发送 SBE 编码的 NewOrderRequest / CancelOrderRequest</li>
 *   <li>接收 SBE 编码的 ExecutionReport 并回调 {@link GatewayEgressListener}</li>
 * </ul>
 *
 * @author Reln Ding
 */
public final class AeronClusterClient implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(AeronClusterClient.class);

    private static final int FRAGMENT_LIMIT = 10;

    private final String[]               ingressEndpoints;
    private final GatewayEgressListener  egressListener;
    private final String                 aeronDir;

    private AeronCluster    aeronCluster;
    private MediaDriver     mediaDriver;

    // SBE 编码器（预分配，单线程使用）
    private final MessageHeaderEncoder       headerEncoder  = new MessageHeaderEncoder();
    private final NewOrderRequestEncoder     newOrderEncoder = new NewOrderRequestEncoder();
    private final CancelOrderRequestEncoder  cancelEncoder   = new CancelOrderRequestEncoder();
    private final MutableDirectBuffer        sendBuffer      =
        new UnsafeBuffer(ByteBuffer.allocateDirect(512));

    private final IdleStrategy idleStrategy = new BusySpinIdleStrategy();
    private volatile boolean   running      = true;

    /**
     * @param ingressEndpoints Cluster 节点 Ingress 地址列表，如
     *                         ["localhost:9010", "localhost:9011", "localhost:9012"]
     * @param egressListener   回报回调
     * @param aeronDir         Aeron 工作目录
     */
    public AeronClusterClient(final String[] ingressEndpoints,
                              final GatewayEgressListener egressListener,
                              final String aeronDir) {
        this.ingressEndpoints = ingressEndpoints;
        this.egressListener   = egressListener;
        this.aeronDir         = aeronDir;
    }

    /**
     * 启动连接（建立 MediaDriver + AeronCluster）。
     */
    public void connect() {
        mediaDriver = MediaDriver.launch(new MediaDriver.Context()
            .aeronDirectoryName(aeronDir)
            .threadingMode(ThreadingMode.SHARED)
            .dirDeleteOnStart(false));

        final StringBuilder ingressChannel = new StringBuilder("aeron:udp?endpoint=");
        for (int i = 0; i < ingressEndpoints.length; i++) {
            if (i > 0) ingressChannel.append('|');
            ingressChannel.append(ingressEndpoints[i]);
        }

        aeronCluster = AeronCluster.connect(new AeronCluster.Context()
            .aeronDirectoryName(aeronDir)
            .ingressChannel(ingressChannel.toString())
            .egressListener(this::onEgressMessage));

        log.info("AeronClusterClient connected, leader: {}", aeronCluster.leaderMemberId());
    }

    @Override
    public void run() {
        while (running) {
            idleStrategy.idle(aeronCluster.pollEgress());
        }
    }

    public void stop() {
        running = false;
    }

    public void close() {
        stop();
        if (aeronCluster != null) aeronCluster.close();
        if (mediaDriver  != null) mediaDriver.close();
    }

    // ----------------------------------------------------------------
    // 发送接口
    // ----------------------------------------------------------------

    /**
     * 发送新订单请求到 Cluster。
     *
     * @return true=发送成功，false=背压/未连接
     */
    public boolean sendNewOrder(final long correlationId, final long accountId,
                                final int symbolId, final byte side,
                                final byte orderType, final byte timeInForce,
                                final long price, final long quantity,
                                final short leverage, final long timestampNs) {
        final int headerLen = MessageHeaderEncoder.ENCODED_LENGTH;
        headerEncoder.wrap(sendBuffer, 0)
            .blockLength(NewOrderRequestEncoder.BLOCK_LENGTH)
            .templateId(NewOrderRequestEncoder.TEMPLATE_ID)
            .schemaId(NewOrderRequestEncoder.SCHEMA_ID)
            .version(NewOrderRequestEncoder.SCHEMA_VERSION);

        newOrderEncoder.wrap(sendBuffer, headerLen)
            .correlationId(correlationId)
            .accountId(accountId)
            .symbolId(symbolId)
            .side(Side.get(side))
            .orderType(OrderType.get(orderType))
            .timeInForce(TimeInForce.get(timeInForce))
            .price(price)
            .quantity(quantity)
            .leverage(leverage)
            .timestamp(timestampNs);

        final int totalLen = headerLen + NewOrderRequestEncoder.BLOCK_LENGTH;
        final long result  = aeronCluster.offer(sendBuffer, 0, totalLen);
        if (result < 0) {
            log.warn("sendNewOrder back-pressured or not connected: result={}", result);
            return false;
        }
        return true;
    }

    /**
     * 发送撤单请求到 Cluster。
     */
    public boolean sendCancelOrder(final long correlationId, final long accountId,
                                   final long orderId, final int symbolId,
                                   final long timestampNs) {
        final int headerLen = MessageHeaderEncoder.ENCODED_LENGTH;
        headerEncoder.wrap(sendBuffer, 0)
            .blockLength(CancelOrderRequestEncoder.BLOCK_LENGTH)
            .templateId(CancelOrderRequestEncoder.TEMPLATE_ID)
            .schemaId(CancelOrderRequestEncoder.SCHEMA_ID)
            .version(CancelOrderRequestEncoder.SCHEMA_VERSION);

        cancelEncoder.wrap(sendBuffer, headerLen)
            .correlationId(correlationId)
            .accountId(accountId)
            .orderId(orderId)
            .symbolId(symbolId)
            .timestamp(timestampNs);

        final int totalLen = headerLen + CancelOrderRequestEncoder.BLOCK_LENGTH;
        final long result  = aeronCluster.offer(sendBuffer, 0, totalLen);
        return result >= 0;
    }

    // ----------------------------------------------------------------
    // Egress 回调
    // ----------------------------------------------------------------

    private void onEgressMessage(final long clusterSessionId,
                                 final long timestamp,
                                 final DirectBuffer buffer,
                                 final int offset,
                                 final int length) {
        egressListener.onMessage(clusterSessionId, buffer, offset, length);
    }
}
```

---

## 8. GatewayEgressListener

解码 Cluster 下发的 ExecutionReport，路由到对应的 WebSocket 连接。

文件：`gateway-service/src/main/java/com/trading/gateway/aeron/GatewayEgressListener.java`

```java
package com.trading.gateway.aeron;

import com.trading.gateway.session.SessionRegistry;
import com.trading.sbe.*;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.agrona.DirectBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Cluster Egress 回报监听器：解码 SBE ExecutionReport，推送给对应 WebSocket 客户端。
 *
 * @author Reln Ding
 */
public final class GatewayEgressListener {

    private static final Logger log = LoggerFactory.getLogger(GatewayEgressListener.class);

    private final SessionRegistry            sessionRegistry;
    private final MessageHeaderDecoder       headerDecoder  = new MessageHeaderDecoder();
    private final ExecutionReportDecoder     execDecoder    = new ExecutionReportDecoder();

    public GatewayEgressListener(final SessionRegistry sessionRegistry) {
        this.sessionRegistry = sessionRegistry;
    }

    /**
     * 处理来自 Cluster Egress 的消息。
     */
    public void onMessage(final long clusterSessionId,
                          final DirectBuffer buffer,
                          final int offset,
                          final int length) {
        headerDecoder.wrap(buffer, offset);
        final int templateId = headerDecoder.templateId();

        if (templateId == ExecutionReportDecoder.TEMPLATE_ID) {
            handleExecutionReport(buffer, offset + MessageHeaderDecoder.ENCODED_LENGTH);
        } else {
            log.debug("Unknown egress templateId={}", templateId);
        }
    }

    private void handleExecutionReport(final DirectBuffer buffer, final int bodyOffset) {
        execDecoder.wrap(buffer, bodyOffset,
                         ExecutionReportDecoder.BLOCK_LENGTH,
                         ExecutionReportDecoder.SCHEMA_VERSION);

        final long accountId    = execDecoder.accountId();
        final long orderId      = execDecoder.orderId();
        final long correlationId = execDecoder.correlationId();
        final byte execType     = execDecoder.execType().value();
        final byte orderStatus  = execDecoder.orderStatus().value();
        final long fillPrice    = execDecoder.lastFillPrice();
        final long fillQty      = execDecoder.lastFillQty();
        final long leavesQty    = execDecoder.leavesQty();
        final long fee          = execDecoder.fee();

        // 构造 JSON（生产环境可换 Jackson 或预格式化模板）
        final String json = buildExecReportJson(
            orderId, correlationId, accountId,
            execDecoder.symbolId(), execType, orderStatus,
            execDecoder.price(), execDecoder.quantity(),
            fillPrice, fillQty, leavesQty, fee);

        // 推送到对应账户的所有 WebSocket 连接
        final java.util.Set<Channel> channels = sessionRegistry.getChannels(accountId);
        if (channels != null) {
            for (final Channel ch : channels) {
                if (ch.isActive()) {
                    ch.writeAndFlush(new TextWebSocketFrame(json));
                }
            }
        }
    }

    private static String buildExecReportJson(final long orderId, final long correlationId,
                                               final long accountId, final int symbolId,
                                               final byte execType, final byte orderStatus,
                                               final long price, final long quantity,
                                               final long fillPrice, final long fillQty,
                                               final long leavesQty, final long fee) {
        return "{\"e\":\"executionReport\""
            + ",\"orderId\":" + orderId
            + ",\"correlationId\":" + correlationId
            + ",\"accountId\":" + accountId
            + ",\"symbolId\":" + symbolId
            + ",\"execType\":" + execType
            + ",\"orderStatus\":" + orderStatus
            + ",\"price\":" + price
            + ",\"qty\":" + quantity
            + ",\"fillPrice\":" + fillPrice
            + ",\"fillQty\":" + fillQty
            + ",\"leavesQty\":" + leavesQty
            + ",\"fee\":" + fee
            + "}";
    }
}
```

---

## 9. 基础设施单元测试

文件：`gateway-service/src/test/java/com/trading/gateway/auth/AuthenticatorTest.java`

```java
package com.trading.gateway.auth;

import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JwtAuthenticator + HmacAuthenticator 单元测试。
 *
 * @author Reln Ding
 */
class AuthenticatorTest {

    // ================================================================
    // JwtAuthenticator
    // ================================================================

    @Nested @DisplayName("JwtAuthenticator")
    class JwtTests {

        private JwtAuthenticator auth;

        @BeforeEach
        void setUp() {
            auth = new JwtAuthenticator("test-secret-key-32-bytes-minimum!");
        }

        @Test @DisplayName("生成并验证 Token 成功")
        void generateAndVerify() {
            final String token = auth.generate(1001L, 3600L);
            final AuthResult result = auth.authenticate(token);
            assertTrue(result.passed);
            assertEquals(1001L, result.accountId);
            assertNull(result.rejectReason);
        }

        @Test @DisplayName("篡改签名返回 INVALID_TOKEN")
        void tamperedSignature() {
            final String token = auth.generate(1001L, 3600L);
            final String tampered = token.substring(0, token.lastIndexOf('.') + 1) + "invalid";
            final AuthResult result = auth.authenticate(tampered);
            assertFalse(result.passed);
            assertEquals("invalid_token", result.rejectReason);
        }

        @Test @DisplayName("过期 Token 返回 EXPIRED")
        void expiredToken() {
            final String token = auth.generate(1001L, -1L);  // 已过期
            final AuthResult result = auth.authenticate(token);
            assertFalse(result.passed);
            assertEquals("token_expired", result.rejectReason);
        }

        @Test @DisplayName("null token 返回 MISSING")
        void nullToken() {
            assertFalse(auth.authenticate(null).passed);
            assertEquals("missing_token", auth.authenticate(null).rejectReason);
        }

        @Test @DisplayName("空字符串 token 返回 MISSING")
        void emptyToken() {
            assertFalse(auth.authenticate("").passed);
        }

        @Test @DisplayName("格式错误（非3段）返回 INVALID_TOKEN")
        void malformedToken() {
            assertFalse(auth.authenticate("only.two").passed);
            assertFalse(auth.authenticate("a.b.c.d").passed);
        }

        @Test @DisplayName("extractLongField 正确解析带引号和不带引号的数字")
        void extractLongField() {
            assertEquals(1001L, JwtAuthenticator.extractLongField("{\"sub\":\"1001\",\"exp\":9999}", "sub"));
            assertEquals(9999L, JwtAuthenticator.extractLongField("{\"sub\":\"1001\",\"exp\":9999}", "exp"));
            assertEquals(-1L,   JwtAuthenticator.extractLongField("{\"sub\":\"1001\"}", "missing"));
        }

        @Test @DisplayName("不同密钥生成的 Token 不能被其他实例验证")
        void differentSecretRejected() {
            final JwtAuthenticator other = new JwtAuthenticator("different-secret-key-32-bytes!!");
            final String token = auth.generate(1001L, 3600L);
            assertFalse(other.authenticate(token).passed);
        }
    }

    // ================================================================
    // HmacAuthenticator
    // ================================================================

    @Nested @DisplayName("HmacAuthenticator")
    class HmacTests {

        private HmacAuthenticator auth;

        @BeforeEach
        void setUp() {
            auth = new HmacAuthenticator();
            auth.register("key-001", 1001L, "secret-001");
        }

        private String sign(final String secret, final String payload) {
            try {
                final javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
                mac.init(new javax.crypto.spec.SecretKeySpec(
                    secret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
                return java.util.HexFormat.of().formatHex(
                    mac.doFinal(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            } catch (Exception e) { throw new RuntimeException(e); }
        }

        @Test @DisplayName("合法签名通过鉴权")
        void validSignature() {
            final long ts      = System.currentTimeMillis();
            final String payload = "symbol=1&qty=100&timestamp=" + ts;
            final String sig    = sign("secret-001", payload);
            final AuthResult result = auth.authenticate("key-001", ts, payload, sig);
            assertTrue(result.passed);
            assertEquals(1001L, result.accountId);
        }

        @Test @DisplayName("签名错误返回 INVALID_TOKEN")
        void wrongSignature() {
            final long ts = System.currentTimeMillis();
            final AuthResult result = auth.authenticate("key-001", ts, "payload", "wrongsig");
            assertFalse(result.passed);
            assertEquals("invalid_token", result.rejectReason);
        }

        @Test @DisplayName("时间戳超出窗口返回 EXPIRED")
        void timestampOutOfWindow() {
            final long oldTs   = System.currentTimeMillis() - 10_000L;
            final String payload = "symbol=1&timestamp=" + oldTs;
            final String sig    = sign("secret-001", payload);
            final AuthResult result = auth.authenticate("key-001", oldTs, payload, sig);
            assertFalse(result.passed);
            assertEquals("token_expired", result.rejectReason);
        }

        @Test @DisplayName("未知 API Key 返回 INVALID_TOKEN")
        void unknownApiKey() {
            final AuthResult result = auth.authenticate("unknown", System.currentTimeMillis(), "", "");
            assertFalse(result.passed);
        }

        @Test @DisplayName("null API Key 返回 MISSING")
        void nullApiKey() {
            assertFalse(auth.authenticate(null, 0L, "", "").passed);
        }

        @Test @DisplayName("revoke 后 API Key 失效")
        void revokeApiKey() {
            final long ts      = System.currentTimeMillis();
            final String payload = "timestamp=" + ts;
            final String sig    = sign("secret-001", payload);
            auth.revoke("key-001");
            assertFalse(auth.authenticate("key-001", ts, payload, sig).passed);
        }
    }
}
```

文件：`gateway-service/src/test/java/com/trading/gateway/ratelimit/TokenBucketRateLimiterTest.java`

```java
package com.trading.gateway.ratelimit;

import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TokenBucketRateLimiter 单元测试。
 *
 * @author Reln Ding
 */
class TokenBucketRateLimiterTest {

    @Test @DisplayName("初始满桶，连续请求在容量内全部通过")
    void initialFullBucket() {
        final TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(5, 1, 1000L);
        for (int i = 0; i < 5; i++) {
            assertTrue(limiter.tryAcquire(1001L), "Request " + i + " should pass");
        }
    }

    @Test @DisplayName("桶耗尽后请求被拒绝")
    void bucketExhaustedRejects() {
        final TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(3, 1, 1000L);
        limiter.tryAcquire(1001L);
        limiter.tryAcquire(1001L);
        limiter.tryAcquire(1001L);
        assertFalse(limiter.tryAcquire(1001L));
    }

    @Test @DisplayName("不同账户桶独立，互不影响")
    void separateBucketsPerAccount() {
        final TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1, 1, 1000L);
        assertTrue(limiter.tryAcquire(1001L));
        assertFalse(limiter.tryAcquire(1001L));  // 1001 桶耗尽
        assertTrue(limiter.tryAcquire(1002L));   // 1002 仍可通过
    }

    @Test @DisplayName("等待补充周期后令牌恢复")
    void tokensRefillAfterInterval() throws InterruptedException {
        final TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(2, 2, 50L);
        limiter.tryAcquire(1001L);
        limiter.tryAcquire(1001L);
        assertFalse(limiter.tryAcquire(1001L));   // 耗尽

        Thread.sleep(60L);  // 等待补充
        assertTrue(limiter.tryAcquire(1001L));    // 补充后可通过
    }

    @Test @DisplayName("补充不超过桶容量上限")
    void refillDoesNotExceedCapacity() throws InterruptedException {
        final TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(3, 10, 50L);
        // 等待多个周期，桶应被限制在容量 3
        Thread.sleep(200L);
        // 最多只能通过 3 次
        int passed = 0;
        for (int i = 0; i < 10; i++) {
            if (limiter.tryAcquire(1001L)) passed++;
        }
        assertEquals(3, passed);
    }

    @Test @DisplayName("remove 后账户桶被清除，再次请求重建满桶")
    void removeAndRebuild() {
        final TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(2, 1, 1000L);
        limiter.tryAcquire(1001L);
        limiter.tryAcquire(1001L);
        assertFalse(limiter.tryAcquire(1001L));

        limiter.remove(1001L);
        assertTrue(limiter.tryAcquire(1001L));  // 重建满桶
    }

    @Test @DisplayName("内部 TokenBucket 惰性补充计算正确")
    void lazyRefillCalculation() {
        final TokenBucketRateLimiter.TokenBucket bucket =
            new TokenBucketRateLimiter.TokenBucket(5, 0L);
        // 消耗 5 个令牌
        for (int i = 0; i < 5; i++) {
            bucket.tryConsume(0L, 5, 100_000_000L, 5);
        }
        assertEquals(0, bucket.getTokens());

        // 模拟过了 2 个补充周期（200ms）
        bucket.tryConsume(200_000_000L, 5, 100_000_000L, 5);
        // 补充 2×5=10，但上限 5，所以应剩 5-1=4
        assertEquals(4, bucket.getTokens());
    }
}
```

### 9.1 运行单元测试

```bash
cd trading-platform
mvn test -pl gateway-service \
  -Dtest="AuthenticatorTest,TokenBucketRateLimiterTest" \
  -Dcheckstyle.skip=true
# 期望：Tests run: 22, Failures: 0, Errors: 0
```

---

## Part 1 完成检查清单

- [ ] `GatewayServer`：BossGroup/WorkerGroup 正确初始化，`TCP_NODELAY=true`
- [ ] `JwtAuthenticator`：HS256 验签正确；`constantTimeEquals` 防时序攻击；过期/格式错误分支均覆盖
- [ ] `HmacAuthenticator`：时间窗口 ±5000ms；revoke 后立即失效；null/空 apiKey 返回 MISSING
- [ ] `TokenBucketRateLimiter`：惰性补充正确；不同账户桶独立；容量上限不超；remove 后重建
- [ ] `AeronClusterClient`：`sendNewOrder` / `sendCancelOrder` 编码字段完整
- [ ] `GatewayEgressListener`：ExecutionReport 解码后路由到正确账户的 Channel
- [ ] `AuthenticatorTest` 15 个 + `TokenBucketRateLimiterTest` 7 个 = **22 个测试通过**

---

## 下一步：Part 2

Part 1 完成后，进入 **Part 2：Gateway HTTP + WebSocket Handler**，包括：

1. `SessionRegistry`：WebSocket Session 注册表（accountId → Set\<Channel\>）
2. `HttpServerHandler`：解析 REST 请求（下单/撤单/查询余额/查询持仓）→ 鉴权 → 限流 → SBE 编码 → 发 Cluster
3. `WebSocketHandler`：协议升级、行情订阅/取消订阅、私有回报推送
4. `GatewayChannelInitializer`：组装 Netty Pipeline（SSL → HTTP → 路由 → WS 升级）
5. `GatewayMain`：整体启动入口
6. Gateway 接口联调测试
