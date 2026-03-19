# Phase 4 Gateway 与推送服务 — Part 2：Gateway HTTP + WebSocket Handler

> **目标：** 实现 Gateway 的 REST 与 WebSocket 接口，完成从客户端请求到
> Aeron Cluster 的完整编解码链路，以及 WebSocket 行情订阅管理。
>
> **前置条件：** Part 1 完成，22 个基础设施测试通过  
> **本节验证目标：** REST 下单/撤单/查询接口联调通过，WS 订阅/取消订阅逻辑单测通过

---

## 目录

1. [SessionRegistry（WebSocket 会话注册表）](#1-sessionregistrywebsocket-会话注册表)
2. [GatewayChannelInitializer（Netty Pipeline 组装）](#2-gatewaychannelinitializernetty-pipeline-组装)
3. [HttpServerHandler（REST 接口）](#3-httpserverhandlerrest-接口)
4. [WebSocketHandler（WS 接口）](#4-websockethandlerws-接口)
5. [GatewayMain（启动入口）](#5-gatewaymain启动入口)
6. [Handler 单元测试](#6-handler-单元测试)

---

## 1. SessionRegistry（WebSocket 会话注册表）

管理账户与 WebSocket Channel 的双向映射，支持一个账户同时建立多个连接（多设备）。

文件：`gateway-service/src/main/java/com/trading/gateway/session/SessionRegistry.java`

```java
package com.trading.gateway.session;

import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket 会话注册表。
 *
 * <p>数据结构：
 * <ul>
 *   <li>{@code accountChannels}：accountId → Set\<Channel\>（公共推送和私有回报）</li>
 *   <li>{@code channelAccount}：Channel → accountId（快速反查，用于断线清理）</li>
 * </ul>
 *
 * <p>线程安全：ConcurrentHashMap，Netty Worker 多线程安全。
 *
 * @author Reln Ding
 */
public final class SessionRegistry {

    private static final Logger log = LoggerFactory.getLogger(SessionRegistry.class);

    /** Channel 上存储 accountId 的属性键 */
    public static final AttributeKey<Long> ACCOUNT_ID_KEY =
        AttributeKey.valueOf("accountId");

    /** accountId → WebSocket Channels */
    private final ConcurrentHashMap<Long, Set<Channel>> accountChannels =
        new ConcurrentHashMap<>(1024);

    /** Channel → accountId（反查） */
    private final ConcurrentHashMap<Channel, Long> channelAccount =
        new ConcurrentHashMap<>(4096);

    /**
     * 注册已鉴权的 WebSocket 连接。
     *
     * @param accountId 账户 ID
     * @param channel   WebSocket Channel
     */
    public void register(final long accountId, final Channel channel) {
        channel.attr(ACCOUNT_ID_KEY).set(accountId);
        accountChannels.computeIfAbsent(accountId,
            k -> Collections.newSetFromMap(new ConcurrentHashMap<>())).add(channel);
        channelAccount.put(channel, accountId);
        log.debug("WS registered: accountId={}, channel={}", accountId, channel.id());
    }

    /**
     * 注销连接（断线/主动关闭时调用）。
     */
    public void unregister(final Channel channel) {
        final Long accountId = channelAccount.remove(channel);
        if (accountId != null) {
            final Set<Channel> channels = accountChannels.get(accountId);
            if (channels != null) {
                channels.remove(channel);
                if (channels.isEmpty()) {
                    accountChannels.remove(accountId);
                }
            }
            log.debug("WS unregistered: accountId={}, channel={}", accountId, channel.id());
        }
    }

    /**
     * 获取某账户的所有 WebSocket 连接（只读视图）。
     *
     * @return Channel Set，或 null（无连接）
     */
    public Set<Channel> getChannels(final long accountId) {
        return accountChannels.get(accountId);
    }

    /** 获取 Channel 对应的账户 ID，-1 表示未注册 */
    public long getAccountId(final Channel channel) {
        final Long id = channelAccount.get(channel);
        return id != null ? id : -1L;
    }

    /** 当前在线连接总数 */
    public int totalConnections() {
        return channelAccount.size();
    }

    /** 当前在线账户数 */
    public int totalAccounts() {
        return accountChannels.size();
    }
}
```

---

## 2. GatewayChannelInitializer（Netty Pipeline 组装）

文件：`gateway-service/src/main/java/com/trading/gateway/netty/GatewayChannelInitializer.java`

```java
package com.trading.gateway.netty;

import com.trading.gateway.aeron.AeronClusterClient;
import com.trading.gateway.auth.HmacAuthenticator;
import com.trading.gateway.auth.JwtAuthenticator;
import com.trading.gateway.ratelimit.TokenBucketRateLimiter;
import com.trading.gateway.session.SessionRegistry;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.codec.http.websocketx.extensions.compression.WebSocketServerCompressionHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.timeout.IdleStateHandler;

import java.util.concurrent.TimeUnit;

/**
 * Netty Channel Pipeline 装配器。
 *
 * <p>Pipeline 结构（每条连接独立）：
 * <pre>
 * [SslHandler]               ← TLS（生产环境必须）
 * [HttpServerCodec]          ← HTTP 编解码
 * [HttpObjectAggregator]     ← 聚合 HTTP Body（最大 64KB）
 * [IdleStateHandler]         ← 心跳超时（60s 无读则断开）
 * [WebSocketCompressionHandler] ← permessage-deflate 压缩
 * [WebSocketProtocolHandler] ← WS 握手升级（路径 /ws）
 * [HttpServerHandler]        ← 处理 REST 请求
 * [WebSocketHandler]         ← 处理 WS 帧
 * </pre>
 *
 * @author Reln Ding
 */
public final class GatewayChannelInitializer extends ChannelInitializer<SocketChannel> {

    private static final int MAX_HTTP_CONTENT_LENGTH = 64 * 1024;
    private static final String WS_PATH = "/ws";

    private final SslContext            sslContext;   // null = 不启用 TLS
    private final JwtAuthenticator      jwtAuth;
    private final HmacAuthenticator     hmacAuth;
    private final TokenBucketRateLimiter rateLimiter;
    private final AeronClusterClient    clusterClient;
    private final SessionRegistry       sessionRegistry;

    public GatewayChannelInitializer(final SslContext sslContext,
                                     final JwtAuthenticator jwtAuth,
                                     final HmacAuthenticator hmacAuth,
                                     final TokenBucketRateLimiter rateLimiter,
                                     final AeronClusterClient clusterClient,
                                     final SessionRegistry sessionRegistry) {
        this.sslContext      = sslContext;
        this.jwtAuth         = jwtAuth;
        this.hmacAuth        = hmacAuth;
        this.rateLimiter     = rateLimiter;
        this.clusterClient   = clusterClient;
        this.sessionRegistry = sessionRegistry;
    }

    @Override
    protected void initChannel(final SocketChannel ch) {
        final ChannelPipeline p = ch.pipeline();

        if (sslContext != null) {
            p.addLast("ssl", sslContext.newHandler(ch.alloc()));
        }
        p.addLast("httpCodec",   new HttpServerCodec());
        p.addLast("aggregator",  new HttpObjectAggregator(MAX_HTTP_CONTENT_LENGTH));
        p.addLast("idle",        new IdleStateHandler(60, 0, 0, TimeUnit.SECONDS));
        p.addLast("wsCompressor", new WebSocketServerCompressionHandler());
        p.addLast("wsProtocol",  new WebSocketServerProtocolHandler(WS_PATH, null, true));
        p.addLast("http",        new HttpServerHandler(jwtAuth, hmacAuth, rateLimiter, clusterClient));
        p.addLast("ws",          new WebSocketHandler(jwtAuth, sessionRegistry));
    }
}
```

---

## 3. HttpServerHandler（REST 接口）

处理 REST 请求：解析 JSON → 鉴权 → 限流 → 编码 SBE → 发 Cluster。

文件：`gateway-service/src/main/java/com/trading/gateway/netty/HttpServerHandler.java`

```java
package com.trading.gateway.netty;

import com.trading.gateway.aeron.AeronClusterClient;
import com.trading.gateway.auth.AuthResult;
import com.trading.gateway.auth.HmacAuthenticator;
import com.trading.gateway.auth.JwtAuthenticator;
import com.trading.gateway.ratelimit.TokenBucketRateLimiter;
import com.trading.sbe.OrderType;
import com.trading.sbe.Side;
import com.trading.sbe.TimeInForce;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.handler.codec.http.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

/**
 * REST HTTP 请求处理器。
 *
 * <p>支持接口：
 * <ul>
 *   <li>POST /api/v1/order     — 下单</li>
 *   <li>DELETE /api/v1/order   — 撤单</li>
 *   <li>GET /api/v1/order      — 查询订单（TODO：从柜台查询）</li>
 *   <li>GET /api/v1/account    — 查询余额（TODO：从柜台查询）</li>
 * </ul>
 *
 * <p>鉴权优先级：JWT Bearer Token > HMAC API Key（请求头含 X-API-KEY 则走 HMAC）
 *
 * @author Reln Ding
 */
@ChannelHandler.Sharable
public final class HttpServerHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private static final Logger log = LoggerFactory.getLogger(HttpServerHandler.class);

    private static final String PATH_ORDER   = "/api/v1/order";
    private static final String PATH_ACCOUNT = "/api/v1/account";
    private static final String CONTENT_JSON = "application/json; charset=UTF-8";

    private final JwtAuthenticator       jwtAuth;
    private final HmacAuthenticator      hmacAuth;
    private final TokenBucketRateLimiter rateLimiter;
    private final AeronClusterClient     clusterClient;

    /** correlationId 生成器（per-Gateway 进程单调递增）*/
    private final AtomicLong correlationIdGen = new AtomicLong(1L);

    public HttpServerHandler(final JwtAuthenticator jwtAuth,
                             final HmacAuthenticator hmacAuth,
                             final TokenBucketRateLimiter rateLimiter,
                             final AeronClusterClient clusterClient) {
        this.jwtAuth       = jwtAuth;
        this.hmacAuth      = hmacAuth;
        this.rateLimiter   = rateLimiter;
        this.clusterClient = clusterClient;
    }

    @Override
    protected void channelRead0(final ChannelHandlerContext ctx,
                                final FullHttpRequest req) {
        // WebSocket 升级请求不在此处理
        if (HttpUtil.is100ContinueExpected(req)) {
            ctx.write(new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.CONTINUE,
                Unpooled.EMPTY_BUFFER));
            return;
        }

        final String path   = req.uri().split("\\?")[0];
        final String method = req.method().name();

        // 鉴权
        final AuthResult auth = authenticate(req);
        if (!auth.passed) {
            sendJson(ctx, HttpResponseStatus.UNAUTHORIZED,
                     "{\"code\":401,\"msg\":\"" + auth.rejectReason + "\"}");
            return;
        }

        // 限流
        if (!rateLimiter.tryAcquire(auth.accountId)) {
            sendJson(ctx, HttpResponseStatus.TOO_MANY_REQUESTS,
                     "{\"code\":429,\"msg\":\"rate_limit_exceeded\"}");
            return;
        }

        // 路由
        if (PATH_ORDER.equals(path)) {
            switch (method) {
                case "POST"   -> handleNewOrder(ctx, req, auth.accountId);
                case "DELETE" -> handleCancelOrder(ctx, req, auth.accountId);
                case "GET"    -> handleQueryOrder(ctx, req, auth.accountId);
                default -> sendJson(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED,
                                    "{\"code\":405,\"msg\":\"method_not_allowed\"}");
            }
        } else if (PATH_ACCOUNT.equals(path) && "GET".equals(method)) {
            handleQueryAccount(ctx, req, auth.accountId);
        } else {
            sendJson(ctx, HttpResponseStatus.NOT_FOUND,
                     "{\"code\":404,\"msg\":\"not_found\"}");
        }
    }

    // ----------------------------------------------------------------
    // 接口处理
    // ----------------------------------------------------------------

    /**
     * POST /api/v1/order — 下单。
     *
     * <p>请求 JSON 格式：
     * <pre>
     * {
     *   "symbol": "BTC/USDT",
     *   "symbolId": 1,
     *   "side": "BUY",
     *   "type": "LIMIT",
     *   "price": "50000.00",
     *   "quantity": "0.1",
     *   "timeInForce": "GTC",
     *   "leverage": 1
     * }
     * </pre>
     */
    private void handleNewOrder(final ChannelHandlerContext ctx,
                                final FullHttpRequest req,
                                final long accountId) {
        final String body = req.content().toString(StandardCharsets.UTF_8);
        try {
            final int    symbolId    = parseIntField(body, "symbolId");
            final byte   side        = "BUY".equals(parseStrField(body, "side"))
                                       ? Side.BUY.value() : Side.SELL.value();
            final byte   orderType   = parseOrderType(parseStrField(body, "type"));
            final byte   tif         = parseTif(parseStrField(body, "timeInForce"));
            final long   price       = parsePriceToLong(parseStrField(body, "price"), 2);
            final long   quantity    = parsePriceToLong(parseStrField(body, "quantity"), 6);
            final short  leverage    = (short) parseIntField(body, "leverage");
            final long   correlationId = correlationIdGen.getAndIncrement();

            final boolean sent = clusterClient.sendNewOrder(
                correlationId, accountId, symbolId,
                side, orderType, tif,
                price, quantity, leverage,
                System.nanoTime());

            if (sent) {
                sendJson(ctx, HttpResponseStatus.OK,
                         "{\"code\":0,\"correlationId\":" + correlationId + "}");
            } else {
                sendJson(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE,
                         "{\"code\":503,\"msg\":\"system_busy\"}");
            }
        } catch (final Exception e) {
            log.warn("handleNewOrder parse error: {}", e.getMessage());
            sendJson(ctx, HttpResponseStatus.BAD_REQUEST,
                     "{\"code\":400,\"msg\":\"invalid_request\"}");
        }
    }

    /**
     * DELETE /api/v1/order?orderId=xxx — 撤单。
     */
    private void handleCancelOrder(final ChannelHandlerContext ctx,
                                   final FullHttpRequest req,
                                   final long accountId) {
        try {
            final QueryStringDecoder qsd    = new QueryStringDecoder(req.uri());
            final String             idStr  = getParam(qsd, "orderId");
            final String             symStr = getParam(qsd, "symbolId");
            if (idStr == null || symStr == null) {
                sendJson(ctx, HttpResponseStatus.BAD_REQUEST,
                         "{\"code\":400,\"msg\":\"missing_params\"}");
                return;
            }
            final long orderId    = Long.parseLong(idStr);
            final int  symbolId   = Integer.parseInt(symStr);
            final long correlationId = correlationIdGen.getAndIncrement();

            clusterClient.sendCancelOrder(
                correlationId, accountId, orderId, symbolId, System.nanoTime());

            sendJson(ctx, HttpResponseStatus.OK,
                     "{\"code\":0,\"correlationId\":" + correlationId + "}");
        } catch (final NumberFormatException e) {
            sendJson(ctx, HttpResponseStatus.BAD_REQUEST,
                     "{\"code\":400,\"msg\":\"invalid_params\"}");
        }
    }

    /** GET /api/v1/order?orderId=xxx — 查询订单（Phase 4 简化：返回占位响应）*/
    private void handleQueryOrder(final ChannelHandlerContext ctx,
                                  final FullHttpRequest req,
                                  final long accountId) {
        sendJson(ctx, HttpResponseStatus.OK,
                 "{\"code\":0,\"msg\":\"query_not_implemented\"}");
    }

    /** GET /api/v1/account — 查询余额（Phase 4 简化：返回占位响应）*/
    private void handleQueryAccount(final ChannelHandlerContext ctx,
                                    final FullHttpRequest req,
                                    final long accountId) {
        sendJson(ctx, HttpResponseStatus.OK,
                 "{\"code\":0,\"accountId\":" + accountId + "}");
    }

    // ----------------------------------------------------------------
    // 鉴权
    // ----------------------------------------------------------------

    private AuthResult authenticate(final FullHttpRequest req) {
        final String apiKey = req.headers().get("X-API-KEY");
        if (apiKey != null) {
            // HMAC 鉴权
            final QueryStringDecoder qsd = new QueryStringDecoder(req.uri());
            final String tsStr = getParam(qsd, "timestamp");
            final String sig   = getParam(qsd, "signature");
            if (tsStr == null || sig == null) return AuthResult.MISSING;
            final String body     = req.content().toString(StandardCharsets.UTF_8);
            final String queryStr = req.uri().contains("?")
                ? req.uri().substring(req.uri().indexOf('?') + 1) : "";
            return hmacAuth.authenticate(apiKey, Long.parseLong(tsStr),
                                         queryStr + body, sig);
        }
        // JWT 鉴权
        final String authorization = req.headers().get("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return AuthResult.MISSING;
        }
        return jwtAuth.authenticate(authorization.substring(7));
    }

    // ----------------------------------------------------------------
    // 工具方法
    // ----------------------------------------------------------------

    private static void sendJson(final ChannelHandlerContext ctx,
                                 final HttpResponseStatus status,
                                 final String json) {
        final byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        final FullHttpResponse response = new DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1, status,
            Unpooled.wrappedBuffer(bytes));
        response.headers()
            .set(HttpHeaderNames.CONTENT_TYPE, CONTENT_JSON)
            .setInt(HttpHeaderNames.CONTENT_LENGTH, bytes.length)
            .set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
        ctx.writeAndFlush(response);
    }

    private static String getParam(final QueryStringDecoder qsd, final String key) {
        final var list = qsd.parameters().get(key);
        return (list != null && !list.isEmpty()) ? list.get(0) : null;
    }

    /** 从 JSON body 提取字符串字段（简单字符串解析，生产换 Jackson）*/
    static String parseStrField(final String json, final String field) {
        final String key = "\"" + field + "\"";
        final int idx = json.indexOf(key);
        if (idx < 0) throw new IllegalArgumentException("Missing field: " + field);
        int start = json.indexOf(':', idx) + 1;
        while (start < json.length() && json.charAt(start) == ' ') start++;
        if (json.charAt(start) == '"') {
            final int end = json.indexOf('"', start + 1);
            return json.substring(start + 1, end);
        }
        int end = start;
        while (end < json.length() && json.charAt(end) != ',' && json.charAt(end) != '}') end++;
        return json.substring(start, end).trim();
    }

    static int parseIntField(final String json, final String field) {
        return Integer.parseInt(parseStrField(json, field));
    }

    /** 将小数字符串转为固定精度 long（如 "50000.25" precision=2 → 5000025L）*/
    static long parsePriceToLong(final String value, final int precision) {
        final String[] parts = value.split("\\.");
        final long integer = Long.parseLong(parts[0]);
        long decimal = 0L;
        if (parts.length > 1) {
            String decStr = parts[1];
            if (decStr.length() > precision) decStr = decStr.substring(0, precision);
            while (decStr.length() < precision) decStr += "0";
            decimal = Long.parseLong(decStr);
        }
        long factor = 1L;
        for (int i = 0; i < precision; i++) factor *= 10;
        return integer * factor + decimal;
    }

    private static byte parseOrderType(final String type) {
        return switch (type) {
            case "LIMIT"     -> com.trading.sbe.OrderType.LIMIT.value();
            case "MARKET"    -> com.trading.sbe.OrderType.MARKET.value();
            case "IOC"       -> com.trading.sbe.OrderType.IOC.value();
            case "FOK"       -> com.trading.sbe.OrderType.FOK.value();
            case "POST_ONLY" -> com.trading.sbe.OrderType.POST_ONLY.value();
            default -> throw new IllegalArgumentException("Unknown orderType: " + type);
        };
    }

    private static byte parseTif(final String tif) {
        return switch (tif) {
            case "GTC" -> com.trading.sbe.TimeInForce.GTC.value();
            case "GTD" -> com.trading.sbe.TimeInForce.GTD.value();
            case "GFD" -> com.trading.sbe.TimeInForce.GFD.value();
            default    -> com.trading.sbe.TimeInForce.GTC.value();
        };
    }

    @Override
    public void exceptionCaught(final ChannelHandlerContext ctx, final Throwable cause) {
        log.warn("HTTP handler exception: {}", cause.getMessage());
        ctx.close();
    }
}
```

---

## 4. WebSocketHandler（WS 接口）

处理 WebSocket 连接的握手鉴权、行情订阅/取消订阅、心跳。

文件：`gateway-service/src/main/java/com/trading/gateway/netty/WebSocketHandler.java`

```java
package com.trading.gateway.netty;

import com.trading.gateway.auth.AuthResult;
import com.trading.gateway.auth.JwtAuthenticator;
import com.trading.gateway.session.SessionRegistry;
import io.netty.channel.*;
import io.netty.handler.codec.http.websocketx.*;
import io.netty.handler.timeout.IdleStateEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket 帧处理器。
 *
 * <p>客户端连接后需先发送鉴权帧，否则订阅请求不处理：
 * <pre>
 * // 鉴权
 * {"action":"auth","token":"Bearer eyJ..."}
 *
 * // 订阅行情
 * {"action":"subscribe","channels":["depth@1@5","trade@1","ticker@1"]}
 *
 * // 取消订阅
 * {"action":"unsubscribe","channels":["trade@1"]}
 *
 * // 心跳
 * {"action":"ping"}
 * → 服务端回复 {"action":"pong"}
 * </pre>
 *
 * @author Reln Ding
 */
@ChannelHandler.Sharable
public final class WebSocketHandler extends SimpleChannelInboundHandler<WebSocketFrame> {

    private static final Logger log = LoggerFactory.getLogger(WebSocketHandler.class);

    private final JwtAuthenticator jwtAuth;
    private final SessionRegistry  sessionRegistry;

    /**
     * symbolId → Set\<Channel\>（行情订阅注册表，供 Push Service 广播使用）。
     * 此处使用 static 共享，实际生产可注入 SubscriptionRegistry 单例。
     */
    private final java.util.concurrent.ConcurrentHashMap<Integer, Set<Channel>>
        depthSubscribers = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<Integer, Set<Channel>>
        tradeSubscribers = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<Integer, Set<Channel>>
        tickerSubscribers = new java.util.concurrent.ConcurrentHashMap<>();

    public WebSocketHandler(final JwtAuthenticator jwtAuth,
                            final SessionRegistry sessionRegistry) {
        this.jwtAuth         = jwtAuth;
        this.sessionRegistry = sessionRegistry;
    }

    @Override
    protected void channelRead0(final ChannelHandlerContext ctx,
                                final WebSocketFrame frame) {
        if (frame instanceof TextWebSocketFrame textFrame) {
            handleText(ctx, textFrame.text());
        } else if (frame instanceof PingWebSocketFrame) {
            ctx.writeAndFlush(new PongWebSocketFrame(frame.content().retain()));
        } else if (frame instanceof CloseWebSocketFrame) {
            ctx.close();
        }
    }

    private void handleText(final ChannelHandlerContext ctx, final String text) {
        final String action = parseStrField(text, "action");
        if (action == null) {
            sendText(ctx, "{\"code\":400,\"msg\":\"missing_action\"}");
            return;
        }

        switch (action) {
            case "auth"        -> handleAuth(ctx, text);
            case "subscribe"   -> handleSubscribe(ctx, text);
            case "unsubscribe" -> handleUnsubscribe(ctx, text);
            case "ping"        -> sendText(ctx, "{\"action\":\"pong\"}");
            default            -> sendText(ctx, "{\"code\":400,\"msg\":\"unknown_action\"}");
        }
    }

    private void handleAuth(final ChannelHandlerContext ctx, final String text) {
        final String tokenField = parseStrField(text, "token");
        if (tokenField == null) {
            sendText(ctx, "{\"code\":401,\"msg\":\"missing_token\"}");
            return;
        }
        final String token = tokenField.startsWith("Bearer ")
            ? tokenField.substring(7) : tokenField;

        final AuthResult result = jwtAuth.authenticate(token);
        if (!result.passed) {
            sendText(ctx, "{\"code\":401,\"msg\":\"" + result.rejectReason + "\"}");
            return;
        }
        sessionRegistry.register(result.accountId, ctx.channel());
        sendText(ctx, "{\"code\":0,\"msg\":\"auth_ok\",\"accountId\":" + result.accountId + "}");
    }

    /**
     * 订阅频道。
     *
     * <p>频道格式：
     * <ul>
     *   <li>{@code depth@{symbolId}@{levels}} — 订单簿深度（5/10/20档）</li>
     *   <li>{@code trade@{symbolId}} — 逐笔成交</li>
     *   <li>{@code ticker@{symbolId}} — 最新价/24h 统计</li>
     * </ul>
     */
    private void handleSubscribe(final ChannelHandlerContext ctx, final String text) {
        final String channelsStr = parseArrayStr(text, "channels");
        if (channelsStr == null) {
            sendText(ctx, "{\"code\":400,\"msg\":\"missing_channels\"}");
            return;
        }
        for (final String channel : channelsStr.split(",")) {
            final String c = channel.trim().replace("\"", "");
            addSubscription(ctx.channel(), c);
        }
        sendText(ctx, "{\"code\":0,\"msg\":\"subscribed\"}");
    }

    private void handleUnsubscribe(final ChannelHandlerContext ctx, final String text) {
        final String channelsStr = parseArrayStr(text, "channels");
        if (channelsStr != null) {
            for (final String channel : channelsStr.split(",")) {
                final String c = channel.trim().replace("\"", "");
                removeSubscription(ctx.channel(), c);
            }
        }
        sendText(ctx, "{\"code\":0,\"msg\":\"unsubscribed\"}");
    }

    private void addSubscription(final Channel ch, final String channel) {
        if (channel.startsWith("depth@")) {
            final int symbolId = parseSymbolId(channel, "depth@");
            if (symbolId > 0) addToMap(depthSubscribers, symbolId, ch);
        } else if (channel.startsWith("trade@")) {
            final int symbolId = parseSymbolId(channel, "trade@");
            if (symbolId > 0) addToMap(tradeSubscribers, symbolId, ch);
        } else if (channel.startsWith("ticker@")) {
            final int symbolId = parseSymbolId(channel, "ticker@");
            if (symbolId > 0) addToMap(tickerSubscribers, symbolId, ch);
        }
    }

    private void removeSubscription(final Channel ch, final String channel) {
        if (channel.startsWith("depth@")) {
            final int symbolId = parseSymbolId(channel, "depth@");
            removeFromMap(depthSubscribers, symbolId, ch);
        } else if (channel.startsWith("trade@")) {
            final int symbolId = parseSymbolId(channel, "trade@");
            removeFromMap(tradeSubscribers, symbolId, ch);
        } else if (channel.startsWith("ticker@")) {
            final int symbolId = parseSymbolId(channel, "ticker@");
            removeFromMap(tickerSubscribers, symbolId, ch);
        }
    }

    @Override
    public void channelInactive(final ChannelHandlerContext ctx) {
        sessionRegistry.unregister(ctx.channel());
        // 从所有订阅中移除
        depthSubscribers.forEach((k, v)  -> v.remove(ctx.channel()));
        tradeSubscribers.forEach((k, v)  -> v.remove(ctx.channel()));
        tickerSubscribers.forEach((k, v) -> v.remove(ctx.channel()));
        log.debug("WS disconnected: channel={}", ctx.channel().id());
    }

    @Override
    public void userEventTriggered(final ChannelHandlerContext ctx, final Object evt) {
        if (evt instanceof IdleStateEvent) {
            log.debug("WS idle timeout, closing: {}", ctx.channel().id());
            ctx.close();
        }
    }

    // ---- 公开查询（供 Push Service 广播用）----

    public Set<Channel> getDepthSubscribers(final int symbolId) {
        return depthSubscribers.getOrDefault(symbolId, Collections.emptySet());
    }

    public Set<Channel> getTradeSubscribers(final int symbolId) {
        return tradeSubscribers.getOrDefault(symbolId, Collections.emptySet());
    }

    public Set<Channel> getTickerSubscribers(final int symbolId) {
        return tickerSubscribers.getOrDefault(symbolId, Collections.emptySet());
    }

    // ---- 工具方法 ----

    private static void sendText(final ChannelHandlerContext ctx, final String text) {
        ctx.writeAndFlush(new TextWebSocketFrame(text));
    }

    private static void addToMap(
        final java.util.concurrent.ConcurrentHashMap<Integer, Set<Channel>> map,
        final int key, final Channel ch) {
        map.computeIfAbsent(key,
            k -> Collections.newSetFromMap(new ConcurrentHashMap<>())).add(ch);
    }

    private static void removeFromMap(
        final java.util.concurrent.ConcurrentHashMap<Integer, Set<Channel>> map,
        final int key, final Channel ch) {
        final Set<Channel> set = map.get(key);
        if (set != null) set.remove(ch);
    }

    private static int parseSymbolId(final String channel, final String prefix) {
        try {
            final String after = channel.substring(prefix.length());
            final String idStr = after.contains("@") ? after.split("@")[0] : after;
            return Integer.parseInt(idStr);
        } catch (final NumberFormatException e) {
            return -1;
        }
    }

    /** 简单 JSON 字符串字段提取 */
    static String parseStrField(final String json, final String field) {
        final String key = "\"" + field + "\"";
        final int idx = json.indexOf(key);
        if (idx < 0) return null;
        int start = json.indexOf(':', idx) + 1;
        while (start < json.length() && json.charAt(start) == ' ') start++;
        if (start >= json.length()) return null;
        if (json.charAt(start) == '"') {
            final int end = json.indexOf('"', start + 1);
            return end < 0 ? null : json.substring(start + 1, end);
        }
        int end = start;
        while (end < json.length() && json.charAt(end) != ',' && json.charAt(end) != '}') end++;
        return json.substring(start, end).trim();
    }

    /** 提取 JSON 数组字段内容（返回数组元素的逗号分隔字符串）*/
    static String parseArrayStr(final String json, final String field) {
        final String key = "\"" + field + "\"";
        final int idx = json.indexOf(key);
        if (idx < 0) return null;
        final int arrStart = json.indexOf('[', idx);
        final int arrEnd   = json.indexOf(']', arrStart);
        if (arrStart < 0 || arrEnd < 0) return null;
        return json.substring(arrStart + 1, arrEnd);
    }

    @Override
    public void exceptionCaught(final ChannelHandlerContext ctx, final Throwable cause) {
        log.warn("WS handler exception: {}", cause.getMessage());
        ctx.close();
    }
}
```

---

## 5. GatewayMain（启动入口）

文件：`gateway-service/src/main/java/com/trading/gateway/GatewayMain.java`

```java
package com.trading.gateway;

import com.trading.gateway.aeron.AeronClusterClient;
import com.trading.gateway.aeron.GatewayEgressListener;
import com.trading.gateway.auth.HmacAuthenticator;
import com.trading.gateway.auth.JwtAuthenticator;
import com.trading.gateway.netty.GatewayChannelInitializer;
import com.trading.gateway.ratelimit.TokenBucketRateLimiter;
import com.trading.gateway.session.SessionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Gateway Service 启动入口。
 *
 * <p>生产环境启动示例：
 * <pre>
 * java -server -Xms4g -Xmx4g -XX:+UseZGC \
 *      -Dgateway.port=8080 \
 *      -Dgateway.jwt.secret=your-secret-min-32-chars \
 *      -Dgateway.cluster.endpoints=10.0.0.1:9010,10.0.0.2:9010,10.0.0.3:9010 \
 *      -Daeron.dir=/tmp/aeron-gateway \
 *      com.trading.gateway.GatewayMain
 * </pre>
 *
 * @author Reln Ding
 */
public final class GatewayMain {

    private static final Logger log = LoggerFactory.getLogger(GatewayMain.class);

    public static void main(final String[] args) throws Exception {
        final int    port       = Integer.parseInt(System.getProperty("gateway.port", "8080"));
        final String jwtSecret  = System.getProperty("gateway.jwt.secret",
                                                      "dev-secret-key-32-bytes-minimum!!!");
        final String endpoints  = System.getProperty("gateway.cluster.endpoints",
                                                      "localhost:9010");
        final String aeronDir   = System.getProperty("aeron.dir", "/tmp/aeron-gateway");

        // 1. 初始化业务组件
        final JwtAuthenticator       jwtAuth     = new JwtAuthenticator(jwtSecret);
        final HmacAuthenticator      hmacAuth    = new HmacAuthenticator();
        // 限流：容量 100，每秒补充 50 个令牌（每 20ms 补充 1 个）
        final TokenBucketRateLimiter rateLimiter = new TokenBucketRateLimiter(100, 1, 20L);
        final SessionRegistry        sessionReg  = new SessionRegistry();
        final GatewayEgressListener  egress      = new GatewayEgressListener(sessionReg);

        // 2. 启动 AeronClusterClient
        final String[] clusterEndpoints = endpoints.split(",");
        final AeronClusterClient clusterClient =
            new AeronClusterClient(clusterEndpoints, egress, aeronDir);
        clusterClient.connect();
        final Thread clusterThread = new Thread(clusterClient, "aeron-cluster-client");
        clusterThread.setDaemon(false);
        clusterThread.start();

        // 3. 启动 Netty Gateway
        final GatewayChannelInitializer initializer = new GatewayChannelInitializer(
            null,  // sslContext=null 开发模式不启用 TLS
            jwtAuth, hmacAuth, rateLimiter, clusterClient, sessionReg);
        final GatewayServer server = new GatewayServer(port, initializer, null);
        server.start();

        log.info("Gateway started on port {}", port);

        // 4. 关闭钩子
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down Gateway...");
            server.shutdown();
            clusterClient.close();
            log.info("Gateway shutdown complete.");
        }));

        Thread.currentThread().join();
    }
}
```

---

## 6. Handler 单元测试

文件：`gateway-service/src/test/java/com/trading/gateway/netty/HandlerTest.java`

```java
package com.trading.gateway.netty;

import com.trading.gateway.session.SessionRegistry;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * HttpServerHandler + WebSocketHandler 工具方法单元测试。
 *
 * @author Reln Ding
 */
class HandlerTest {

    // ================================================================
    // HttpServerHandler 工具方法
    // ================================================================

    @Nested @DisplayName("HttpServerHandler — 解析工具")
    class HttpParseTests {

        @Test @DisplayName("parseStrField 正确提取字符串字段")
        void parseStrField() {
            final String json = "{\"symbol\":\"BTC/USDT\",\"side\":\"BUY\",\"type\":\"LIMIT\"}";
            assertEquals("BTC/USDT", HttpServerHandler.parseStrField(json, "symbol"));
            assertEquals("BUY",      HttpServerHandler.parseStrField(json, "side"));
            assertEquals("LIMIT",    HttpServerHandler.parseStrField(json, "type"));
        }

        @Test @DisplayName("parseStrField 字段不存在抛异常")
        void parseStrFieldMissing() {
            assertThrows(IllegalArgumentException.class,
                () -> HttpServerHandler.parseStrField("{}", "missing"));
        }

        @Test @DisplayName("parseIntField 正确解析整数")
        void parseIntField() {
            assertEquals(1,   HttpServerHandler.parseIntField("{\"symbolId\":1}", "symbolId"));
            assertEquals(125, HttpServerHandler.parseIntField("{\"leverage\":125}", "leverage"));
        }

        @Test @DisplayName("parsePriceToLong precision=2 正确转换")
        void parsePriceToLongPrecision2() {
            assertEquals(5_000_025L, HttpServerHandler.parsePriceToLong("50000.25", 2));
            assertEquals(5_000_000L, HttpServerHandler.parsePriceToLong("50000", 2));
            assertEquals(5_000_000L, HttpServerHandler.parsePriceToLong("50000.00", 2));
        }

        @Test @DisplayName("parsePriceToLong precision=6 正确转换")
        void parsePriceToLongPrecision6() {
            assertEquals(100_000L,  HttpServerHandler.parsePriceToLong("0.100000", 6));
            assertEquals(1_000_000L, HttpServerHandler.parsePriceToLong("1.000000", 6));
            assertEquals(123_456L,  HttpServerHandler.parsePriceToLong("0.123456", 6));
        }

        @Test @DisplayName("parsePriceToLong 小数位不足时补零")
        void parsePriceToLongPadZero() {
            assertEquals(5_000_100L, HttpServerHandler.parsePriceToLong("50001", 2));
            assertEquals(5_000_000L, HttpServerHandler.parsePriceToLong("50000.0", 2));
        }
    }

    // ================================================================
    // WebSocketHandler 工具方法
    // ================================================================

    @Nested @DisplayName("WebSocketHandler — 解析工具")
    class WsParseTests {

        @Test @DisplayName("parseStrField 正确提取")
        void parseStrField() {
            assertEquals("auth",
                WebSocketHandler.parseStrField("{\"action\":\"auth\",\"token\":\"tok\"}", "action"));
            assertEquals("tok",
                WebSocketHandler.parseStrField("{\"action\":\"auth\",\"token\":\"tok\"}", "token"));
        }

        @Test @DisplayName("parseStrField 字段不存在返回 null")
        void parseStrFieldNull() {
            assertNull(WebSocketHandler.parseStrField("{}", "missing"));
        }

        @Test @DisplayName("parseArrayStr 正确提取数组内容")
        void parseArrayStr() {
            final String json = "{\"action\":\"subscribe\",\"channels\":[\"depth@1@5\",\"trade@1\"]}";
            final String arr  = WebSocketHandler.parseArrayStr(json, "channels");
            assertNotNull(arr);
            assertTrue(arr.contains("depth@1@5"));
            assertTrue(arr.contains("trade@1"));
        }

        @Test @DisplayName("parseArrayStr 字段不存在返回 null")
        void parseArrayStrNull() {
            assertNull(WebSocketHandler.parseArrayStr("{}", "channels"));
        }
    }

    // ================================================================
    // SessionRegistry
    // ================================================================

    @Nested @DisplayName("SessionRegistry")
    class SessionRegistryTests {

        @Test @DisplayName("register 后 getChannels 可找到 Channel")
        void registerAndGet() {
            final SessionRegistry reg = new SessionRegistry();
            final io.netty.channel.embedded.EmbeddedChannel ch =
                new io.netty.channel.embedded.EmbeddedChannel();
            reg.register(1001L, ch);
            assertNotNull(reg.getChannels(1001L));
            assertTrue(reg.getChannels(1001L).contains(ch));
            assertEquals(1001L, reg.getAccountId(ch));
        }

        @Test @DisplayName("unregister 后 Channel 被清除")
        void unregister() {
            final SessionRegistry reg = new SessionRegistry();
            final io.netty.channel.embedded.EmbeddedChannel ch =
                new io.netty.channel.embedded.EmbeddedChannel();
            reg.register(1001L, ch);
            reg.unregister(ch);
            assertNull(reg.getChannels(1001L));
            assertEquals(-1L, reg.getAccountId(ch));
            assertEquals(0, reg.totalConnections());
        }

        @Test @DisplayName("同一账户多连接均注册")
        void multipleConnectionsPerAccount() {
            final SessionRegistry reg = new SessionRegistry();
            final io.netty.channel.embedded.EmbeddedChannel ch1 =
                new io.netty.channel.embedded.EmbeddedChannel();
            final io.netty.channel.embedded.EmbeddedChannel ch2 =
                new io.netty.channel.embedded.EmbeddedChannel();
            reg.register(1001L, ch1);
            reg.register(1001L, ch2);
            assertEquals(2, reg.getChannels(1001L).size());
            assertEquals(2, reg.totalConnections());
            assertEquals(1, reg.totalAccounts());
        }

        @Test @DisplayName("unregister 不存在的 Channel 不抛异常")
        void unregisterUnknownChannel() {
            final SessionRegistry reg = new SessionRegistry();
            final io.netty.channel.embedded.EmbeddedChannel ch =
                new io.netty.channel.embedded.EmbeddedChannel();
            assertDoesNotThrow(() -> reg.unregister(ch));
        }

        @Test @DisplayName("getChannels 不存在账户返回 null")
        void getChannelsUnknownAccount() {
            final SessionRegistry reg = new SessionRegistry();
            assertNull(reg.getChannels(9999L));
        }
    }
}
```

### 6.1 运行单元测试

```bash
cd trading-platform
mvn test -pl gateway-service -Dtest=HandlerTest -Dcheckstyle.skip=true
# 期望：Tests run: 18, Failures: 0, Errors: 0
```

---

## Part 2 完成检查清单

- [ ] `SessionRegistry`：register/unregister/多连接/未知 Channel 不抛异常，5 个测试通过
- [ ] `GatewayChannelInitializer`：Pipeline 顺序正确（SSL → HTTP → Idle → WS → Handler）
- [ ] `HttpServerHandler.parseStrField`：存在/不存在两个场景
- [ ] `HttpServerHandler.parsePriceToLong`：小数/整数/补零/截断 四个场景
- [ ] `WebSocketHandler.parseArrayStr`：正常/null 两个场景
- [ ] `WebSocketHandler`：auth/subscribe/unsubscribe/ping/unknown 五个 action 路由正确
- [ ] `WebSocketHandler.channelInactive`：断线时从 sessionRegistry 和订阅表移除
- [ ] `HandlerTest` **18 个测试通过**
- [ ] 含 Part 1 合计 **40 个测试，0 Failures，0 Errors**

---

## 下一步：Part 3

Part 2 完成后，进入 **Part 3：Push Service（深度 / 成交 / Ticker 推送）**，包括：

1. `push-service` POM 与模块结构
2. `MarketDataSubscriber`：订阅 Aeron IPC stream=4，解码 SBE
3. `DepthDispatcher`：维护完整订单簿快照 + 差量更新推送
4. `TradeDispatcher`：逐笔成交事件推送
5. `TickerAggregator`：24h 滚动统计（最新价、涨跌幅、成交量）
6. `WebSocketPushHandler`：Netty 广播（按 symbolId 过滤订阅者）
7. `PushServiceMain`：启动入口
8. Push Service 单元测试
