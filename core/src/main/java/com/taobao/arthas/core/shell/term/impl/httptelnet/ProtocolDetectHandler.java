package com.taobao.arthas.core.shell.term.impl.httptelnet;

import java.util.concurrent.TimeUnit;

import com.taobao.arthas.common.ArthasConstants;
import com.taobao.arthas.core.shell.term.impl.http.BasicHttpAuthenticatorHandler;
import com.taobao.arthas.core.shell.term.impl.http.HttpRequestHandler;

import com.taobao.arthas.core.shell.term.impl.http.TtyWebSocketFrameHandler;
import com.taobao.arthas.core.shell.term.impl.http.session.HttpSessionManager;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.group.ChannelGroup;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.concurrent.EventExecutorGroup;
import io.netty.util.concurrent.ScheduledFuture;
import io.termd.core.function.Consumer;
import io.termd.core.function.Supplier;
import io.termd.core.telnet.TelnetHandler;
import io.termd.core.telnet.netty.TelnetChannelHandler;
import io.termd.core.tty.TtyConnection;

/**
 * arthas-server的指令接收入口，实现了netty的ChannelInboundHandlerAdapter
 * @author hengyunabc 2019-11-04
 *
 */
public class ProtocolDetectHandler extends ChannelInboundHandlerAdapter {
    private ChannelGroup channelGroup;
    private Supplier<TelnetHandler> handlerFactory;
    private Consumer<TtyConnection> ttyConnectionFactory;
    private EventExecutorGroup workerGroup;
    private HttpSessionManager httpSessionManager;

    public ProtocolDetectHandler(ChannelGroup channelGroup, final Supplier<TelnetHandler> handlerFactory,
                                 Consumer<TtyConnection> ttyConnectionFactory, EventExecutorGroup workerGroup,
                                 HttpSessionManager httpSessionManager) {
        this.channelGroup = channelGroup;
        this.handlerFactory = handlerFactory;
        this.ttyConnectionFactory = ttyConnectionFactory;
        this.workerGroup = workerGroup;
        this.httpSessionManager = httpSessionManager;
    }

    private ScheduledFuture<?> detectTelnetFuture;

    @Override
    public void channelActive(final ChannelHandlerContext ctx) throws Exception {
        detectTelnetFuture = ctx.channel().eventLoop().schedule(new Runnable() {
            @Override
            public void run() {
                channelGroup.add(ctx.channel());
                TelnetChannelHandler handler = new TelnetChannelHandler(handlerFactory);
                ChannelPipeline pipeline = ctx.pipeline();
                pipeline.addLast(handler);
                pipeline.remove(ProtocolDetectHandler.this);
                ctx.fireChannelActive(); // trigger TelnetChannelHandler init
            }

        }, 1000, TimeUnit.MILLISECONDS);
    }

    /**
     * 数据接收
     * @param ctx
     * @param msg
     * @throws Exception
     */
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        ByteBuf in = (ByteBuf) msg;
        if (in.readableBytes() < 3) {
            return;
        }

        if (detectTelnetFuture != null && detectTelnetFuture.isCancellable()) {
            detectTelnetFuture.cancel(false);
        }
        // 取报文中的前三字节，因为arthas-client的http发送请示全部为GET请示
        // 而GET请求的特点就是前三位是字符串GET，所以可以通过前3位是否等于GET来判断是http请求还是telnet请求
        byte[] bytes = new byte[3];
        in.getBytes(0, bytes);
        String httpHeader = new String(bytes);

        ChannelPipeline pipeline = ctx.pipeline();
        if (!"GET".equalsIgnoreCase(httpHeader)) { // telnet
            channelGroup.add(ctx.channel());
            // 如果是telnet请求，则使用TelnetChannelHandler来处理
            TelnetChannelHandler handler = new TelnetChannelHandler(handlerFactory);
            pipeline.addLast(handler);
            ctx.fireChannelActive(); // trigger TelnetChannelHandler init
        } else {// 否则使用http请求来处理
            pipeline.addLast(new HttpServerCodec());
            pipeline.addLast(new ChunkedWriteHandler());
            pipeline.addLast(new HttpObjectAggregator(ArthasConstants.MAX_HTTP_CONTENT_LENGTH));
            pipeline.addLast(new BasicHttpAuthenticatorHandler(httpSessionManager));
            pipeline.addLast(workerGroup, "HttpRequestHandler", new HttpRequestHandler(ArthasConstants.DEFAULT_WEBSOCKET_PATH));
            pipeline.addLast(new WebSocketServerProtocolHandler(ArthasConstants.DEFAULT_WEBSOCKET_PATH, true));
            pipeline.addLast(new IdleStateHandler(0, 0, ArthasConstants.WEBSOCKET_IDLE_SECONDS));
            pipeline.addLast(new TtyWebSocketFrameHandler(channelGroup, ttyConnectionFactory));
            ctx.fireChannelActive();
        }
        pipeline.remove(this);
        // 关键方法：触发数据读，关键触发
        // telnet协议：TelnetChannelHandler#channelRead0
        // http协议：HttpRequestHandler#channelRead0
        ctx.fireChannelRead(in);
    }

}
