package com.taobao.arthas.core.shell.term.impl.httptelnet;

import java.nio.charset.Charset;

import com.taobao.arthas.core.shell.term.impl.http.session.HttpSessionManager;

import io.netty.util.concurrent.EventExecutorGroup;
import io.termd.core.function.Consumer;
import io.termd.core.function.Supplier;
import io.termd.core.telnet.TelnetHandler;
import io.termd.core.telnet.TelnetTtyConnection;
import io.termd.core.tty.TtyConnection;
import io.termd.core.util.CompletableFuture;
import io.termd.core.util.Helper;

/**
 * 在telport端口，同时支持http协议和telnet协议
 * 实现原理是通过ProtocolDetectHandler实现。
 * ProtocolDetectHandler是ChannelInboundHandlerAdapter的一个子类，tcp消息发送过来时会触发channelRead函数
 * 知道http协议中，第一行时请求头，由于arthas所有对外提供的请求都是GET请求，
 * 所以我们只需要判断第一个请求的前3个字节是否是GET，如果非GET的情况下将ProtocolDetectHandler替换为TelnetChannelHandler，
 * 后续的处理逻辑全按照telnet协议处理。
 * 如果是GET情况下将ProtocolDetectHandler替换为Http相关的handler。
 * 总的一句话就是判断第一个应用层消息包前三个字节是否是GET。
 * @author <a href="mailto:julien@julienviet.com">Julien Viet</a>
 * @author hengyunabc 2019-11-05
 */
public class NettyHttpTelnetTtyBootstrap {

    private final NettyHttpTelnetBootstrap httpTelnetTtyBootstrap;
    private boolean outBinary;
    private boolean inBinary;
    private Charset charset = Charset.forName("UTF-8");

    public NettyHttpTelnetTtyBootstrap(EventExecutorGroup workerGroup, HttpSessionManager httpSessionManager) {
        this.httpTelnetTtyBootstrap = new NettyHttpTelnetBootstrap(workerGroup, httpSessionManager);
    }

    public String getHost() {
        return httpTelnetTtyBootstrap.getHost();
    }

    public NettyHttpTelnetTtyBootstrap setHost(String host) {
        httpTelnetTtyBootstrap.setHost(host);
        return this;
    }

    public int getPort() {
        return httpTelnetTtyBootstrap.getPort();
    }

    public NettyHttpTelnetTtyBootstrap setPort(int port) {
        httpTelnetTtyBootstrap.setPort(port);
        return this;
    }

    public boolean isOutBinary() {
        return outBinary;
    }

    /**
     * Enable or disable the TELNET BINARY option on output.
     *
     * @param outBinary
     *            true to require the client to receive binary
     * @return this object
     */
    public NettyHttpTelnetTtyBootstrap setOutBinary(boolean outBinary) {
        this.outBinary = outBinary;
        return this;
    }

    public boolean isInBinary() {
        return inBinary;
    }

    /**
     * Enable or disable the TELNET BINARY option on input.
     *
     * @param inBinary
     *            true to require the client to emit binary
     * @return this object
     */
    public NettyHttpTelnetTtyBootstrap setInBinary(boolean inBinary) {
        this.inBinary = inBinary;
        return this;
    }

    public Charset getCharset() {
        return charset;
    }

    public void setCharset(Charset charset) {
        this.charset = charset;
    }

    /**
     * 启动arthas-server服务
     * @param factory
     * @return
     */
    public CompletableFuture<?> start(Consumer<TtyConnection> factory) {
        CompletableFuture<?> fut = new CompletableFuture();
        // 启动arthas-server服务
        start(factory, Helper.startedHandler(fut));
        return fut;
    }

    public CompletableFuture<?> stop() {
        CompletableFuture<?> fut = new CompletableFuture();
        stop(Helper.stoppedHandler(fut));
        return fut;
    }

    /**
     * 启动arthas-server服务
     * @param factory
     * @param doneHandler
     */
    public void start(final Consumer<TtyConnection> factory, Consumer<Throwable> doneHandler) {
        httpTelnetTtyBootstrap.start(new Supplier<TelnetHandler>() {
            @Override
            public TelnetHandler get() {
                return new TelnetTtyConnection(inBinary, outBinary, charset, factory);
            }
        }, factory, doneHandler);
    }

    public void stop(Consumer<Throwable> doneHandler) {
        httpTelnetTtyBootstrap.stop(doneHandler);
    }
}
