/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package net.raphimc.viabedrock.api.resourcepack.http;

import com.viaversion.viaversion.api.connection.UserConnection;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.stream.ChunkedStream;
import io.netty.handler.stream.ChunkedWriteHandler;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.api.resourcepack.ResourcePack;
import net.raphimc.viabedrock.api.resourcepack.content.Content;
import net.raphimc.viabedrock.experimental.resourcepack.JavaPackCache;
import net.raphimc.viabedrock.protocol.rewriter.ResourcePackRewriter;
import net.raphimc.viabedrock.protocol.storage.ResourcePackStorage;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public class ResourcePackHttpServer {

    private final InetSocketAddress bindAddress;
    private final ChannelFuture channelFuture;
    private final Map<UUID, ConnectionInfo> connections = new ConcurrentHashMap<>();
    private final Map<String, Object> cacheLocks = new ConcurrentHashMap<>();

    private record ConnectionInfo(UserConnection user, String cacheKey) {
    }

    public ResourcePackHttpServer(final InetSocketAddress bindAddress) {
        this.bindAddress = bindAddress;
        this.channelFuture = new ServerBootstrap()
                .group(new NioEventLoopGroup(0))
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, 128)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childHandler(new ChannelInitializer<>() {
                    @Override
                    protected void initChannel(Channel channel) {
                        channel.pipeline().addLast("http_codec", new HttpServerCodec());
                        channel.pipeline().addLast("chunked_writer", new ChunkedWriteHandler());
                        channel.pipeline().addLast("http_handler", new SimpleChannelInboundHandler<>() {
                            @Override
                            protected void channelRead0(ChannelHandlerContext ctx, Object msg) throws InterruptedException {
                                if (msg instanceof HttpRequest request) {
                                    if (!request.method().equals(HttpMethod.GET)) {
                                        ctx.close();
                                        return;
                                    }

                                    final QueryStringDecoder queryStringDecoder = new QueryStringDecoder(request.uri());
                                    if (!queryStringDecoder.parameters().containsKey("token")) {
                                        ctx.close();
                                        return;
                                    }
                                    final UUID uuid = UUID.fromString(queryStringDecoder.parameters().get("token").get(0));
                                    final ConnectionInfo connInfo = ResourcePackHttpServer.this.connections.get(uuid);
                                    if (connInfo == null) {
                                        ctx.close();
                                        return;
                                    }

                                    final UserConnection user = connInfo.user();
                                    while (!user.has(ResourcePackStorage.class)) {
                                        Thread.sleep(100);
                                    }
                                    final ResourcePackStorage resourcePackStorage = user.get(ResourcePackStorage.class);

                                    try {
                                        String cacheKey = connInfo.cacheKey();
                                        if (cacheKey == null) {
                                            cacheKey = JavaPackCache.computeCacheKey(resourcePackStorage.getPackStackTopToBottom(), resourcePackStorage.isSupportsFreeRotation());
                                        }

                                        final JavaPackCache cache = ViaBedrock.getJavaPackCache();
                                        final byte[] data;
                                        if (cache != null) {
                                            final Object cacheLock = ResourcePackHttpServer.this.cacheLocks.computeIfAbsent(cacheKey, key -> new Object());
                                            try {
                                                synchronized (cacheLock) {
                                                    data = ResourcePackHttpServer.this.getOrCreateJavaPack(resourcePackStorage, cache, cacheKey);
                                                }
                                            } finally {
                                                ResourcePackHttpServer.this.cacheLocks.remove(cacheKey, cacheLock);
                                            }
                                        } else {
                                            data = ResourcePackHttpServer.this.convertJavaPack(resourcePackStorage);
                                        }

                                        final DefaultHttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
                                        response.headers().set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);
                                        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/octet-stream");
                                        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, data.length);
                                        response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
                                        ctx.write(response);
                                        ctx.writeAndFlush(new HttpChunkedInput(new ChunkedStream(new ByteArrayInputStream(data), 65535))).addListener(ChannelFutureListener.CLOSE);
                                    } catch (Throwable e) {
                                        ViaBedrock.getPlatform().getLogger().log(Level.SEVERE, "Failed to convert resource packs", e);
                                        ctx.close();
                                    }
                                }
                            }

                            @Override
                            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                                ctx.close();
                            }
                        });
                    }
                })
                .bind(bindAddress)
                .syncUninterruptibly();
    }

    private byte[] getOrCreateJavaPack(final ResourcePackStorage resourcePackStorage, final JavaPackCache cache, final String cacheKey) throws IOException {
        if (cache.has(cacheKey)) {
            ViaBedrock.getPlatform().getLogger().log(Level.INFO, "Loaded converted resource pack from cache");
            return cache.getData(cacheKey);
        }

        final byte[] data = this.convertJavaPack(resourcePackStorage);
        cache.put(cacheKey, data);
        return data;
    }

    private byte[] convertJavaPack(final ResourcePackStorage resourcePackStorage) throws IOException {
        final long start = System.nanoTime();
        final Content javaContent = ResourcePackRewriter.bedrockToJava(resourcePackStorage);
        for (ResourcePack pack : resourcePackStorage.getPackStackTopToBottom()) {
            final String mcpackPath = "bedrock/" + pack.id() + ".mcpack";
            if (!javaContent.contains(mcpackPath)) {
                javaContent.put(mcpackPath, pack.content().toZip());
            }
        }
        final byte[] data = javaContent.toZip();
        final long end = System.nanoTime();
        ViaBedrock.getPlatform().getLogger().log(Level.INFO, "Converted resource packs in " + ((end - start) / 1_000_000L) + "ms");
        System.gc(); // Resource pack conversion is very memory intensive, so we trigger a GC after conversion to free up memory as soon as possible
        return data;
    }

    public void addConnection(final UUID uuid, final UserConnection connection, final String cacheKey) {
        this.connections.put(uuid, new ConnectionInfo(connection, cacheKey));

        connection.getChannel().closeFuture().addListener(future -> this.connections.remove(uuid));
    }

    public void addConnection(final UUID uuid, final UserConnection connection) {
        this.addConnection(uuid, connection, null);
    }

    public void stop() {
        if (this.channelFuture != null) {
            this.channelFuture.channel().close();
        }
    }

    public String getUrl() {
        final String overrideUrl = ViaBedrock.getConfig().getResourcePackUrl();
        if (!overrideUrl.isEmpty()) {
            return overrideUrl;
        } else {
            final InetSocketAddress bindAddress = (InetSocketAddress) this.channelFuture.channel().localAddress();
            return "http://" + this.bindAddress.getHostString() + ":" + bindAddress.getPort() + "/";
        }
    }

    public String getArtifactUrl(final String hash) {
        final String baseUrl = this.getUrl();
        return (baseUrl.endsWith("/") ? baseUrl : baseUrl + "/") + "packs/" + hash + ".zip";
    }

    public Channel getChannel() {
        return this.channelFuture.channel();
    }

}
