package org.fengfei.lanproxy.client;

import java.util.Arrays;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;

import org.fengfei.lanproxy.client.handlers.ClientChannelHandler;
import org.fengfei.lanproxy.client.handlers.RealServerChannelHandler;
import org.fengfei.lanproxy.client.handlers.WebSocketHandler;
import org.fengfei.lanproxy.client.handlers.WebSocketServerChannelHandler;
import org.fengfei.lanproxy.client.listener.ChannelStatusListener;
import org.fengfei.lanproxy.common.Config;
import org.fengfei.lanproxy.common.container.Container;
import org.fengfei.lanproxy.common.container.ContainerHelper;
import org.fengfei.lanproxy.protocol.CompressHandler;
import org.fengfei.lanproxy.protocol.DecompressHandler;
import org.fengfei.lanproxy.protocol.IdleCheckHandler;
import org.fengfei.lanproxy.protocol.ProxyMessage;
import org.fengfei.lanproxy.protocol.ProxyMessageDecoder;
import org.fengfei.lanproxy.protocol.ProxyMessageEncoder;
import org.fengfei.lanproxy.protocol.WebSocketEncoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.stream.ChunkedWriteHandler;

public class ProxyClientContainer implements Container, ChannelStatusListener {

    private static Logger logger = LoggerFactory.getLogger(ProxyClientContainer.class);

    private static final int MAX_FRAME_LENGTH = 1024 * 1024;

    private static final int LENGTH_FIELD_OFFSET = 0;

    private static final int LENGTH_FIELD_LENGTH = 4;

    private static final int INITIAL_BYTES_TO_STRIP = 0;

    private static final int LENGTH_ADJUSTMENT = 0;

    private NioEventLoopGroup bossGroup;
    
    private NioEventLoopGroup workerGroup;

    private Bootstrap bootstrap;

    private Bootstrap realServerBootstrap;

    private Config config = Config.getInstance();

    private SSLContext sslContext;

    private long sleepTimeMill = 1000;

    public ProxyClientContainer() {
    	bossGroup = new NioEventLoopGroup();
        workerGroup = new NioEventLoopGroup();
        realServerBootstrap = new Bootstrap();
        realServerBootstrap.group(workerGroup);
        realServerBootstrap.channel(NioSocketChannel.class);
        realServerBootstrap.handler(new ChannelInitializer<SocketChannel>() {

            @Override
            public void initChannel(SocketChannel ch) throws Exception {
            	ch.pipeline().addLast(new CompressHandler());
            	ch.pipeline().addLast(new DecompressHandler());
                ch.pipeline().addLast(new RealServerChannelHandler());
            }
        });

//        startClient();
        
    }

    private void startClient(){
    	bootstrap = new Bootstrap();
        bootstrap.group(workerGroup);
        bootstrap.channel(NioSocketChannel.class);
        bootstrap.handler(new ChannelInitializer<SocketChannel>() {

            @Override
            public void initChannel(SocketChannel ch) throws Exception {
                if (Config.getInstance().getBooleanValue("ssl.enable", false)) {
                    if (sslContext == null) {
                        sslContext = SslContextCreator.createSSLContext();
                    }

                    ch.pipeline().addLast(createSslHandler(sslContext));
                }
                ch.pipeline().addLast(new ProxyMessageDecoder(MAX_FRAME_LENGTH, LENGTH_FIELD_OFFSET, LENGTH_FIELD_LENGTH, LENGTH_ADJUSTMENT, INITIAL_BYTES_TO_STRIP));
                ch.pipeline().addLast(new ProxyMessageEncoder());
                ch.pipeline().addLast(new IdleCheckHandler(IdleCheckHandler.READ_IDLE_TIME, IdleCheckHandler.WRITE_IDLE_TIME - 10, 0));
                ch.pipeline().addLast(new ClientChannelHandler(realServerBootstrap, bootstrap, ProxyClientContainer.this));
            }
        });
    }
    
    private void startWebSocketServer(String s) {
		final String router = s;
		final ServerBootstrap bootstrap = new ServerBootstrap();
		bootstrap.group(bossGroup,workerGroup).channel(NioServerSocketChannel.class)
				.childHandler(new ChannelInitializer<Channel>() {

					// 添加处理的Handler，通常包括消息编解码、业务处理，也可以是日志、权限、过滤等
					@Override
					protected void initChannel(Channel ch) throws Exception {
						// 获取职责链
						ChannelPipeline pipeline = ch.pipeline();
						pipeline.addLast("http-codec", new HttpServerCodec());
						//ChunkedWriteHandler分块写处理，文件过大会将内存撑爆
				        pipeline.addLast("chunkedWriteHandler", new ChunkedWriteHandler());
				        /**
				         * 作用是将一个Http的消息组装成一个完成的HttpRequest或者HttpResponse，那么具体的是什么
				         * 取决于是请求还是响应, 该Handler必须放在HttpServerCodec后的后面
				         */
						pipeline.addLast("aggregator", new HttpObjectAggregator(8192));
						// 设置webSocket的请求路由ip:port+/ws
						pipeline.addLast("protocol", new WebSocketServerProtocolHandler(router));
						pipeline.addLast("handler", new WebSocketHandler());
						pipeline.addLast(new WebSocketEncoder());
						pipeline.addLast(new ProxyMessageDecoder(MAX_FRAME_LENGTH, LENGTH_FIELD_OFFSET,
								LENGTH_FIELD_LENGTH, LENGTH_ADJUSTMENT, INITIAL_BYTES_TO_STRIP));
						pipeline.addLast(new ProxyMessageEncoder());
						pipeline.addLast(new WebSocketServerChannelHandler(realServerBootstrap, bootstrap));
					}
				})// bootstrap 还可以设置TCP参数，根据需要可以分别设置主线程池和从线程池参数，来优化服务端性能。
					// 其中主线程池使用option方法来设置，从线程池使用childOption方法设置。
					// backlog表示主线程池中在套接口排队的最大数量，队列由未连接队列（三次握手未完成的）和已连接队列
				.option(ChannelOption.SO_BACKLOG, 5)
				// 表示连接保活，相当于心跳机制，默认为7200s
				.childOption(ChannelOption.SO_KEEPALIVE, true);

		try {
			// 绑定端口，启动select线程，轮询监听channel事件，监听到事件之后就会交给从线程池处理
			bootstrap.bind(Config.getInstance().getStringValue("websocket.server.bind"),Config.getInstance().getIntValue("websocket.server.port")).get();
			System.out.println("Web socket server started at port " + Config.getInstance().getIntValue("websocket.server.port") + '.');
			System.out.println("Open your browser and navigate to http://localhost:" + Config.getInstance().getIntValue("websocket.server.port") + router);
			// 等待服务端口关闭
		} catch (Exception e) {
			e.printStackTrace();
		}

	}
    
    @Override
    public void start() {
    	if(Config.getInstance().getBooleanValue("use.websocket",false)){
    		startWebSocketServer("/ws");
    	}else{
    		startClient();
    		connectProxyServer();
    	}
    }

    private ChannelHandler createSslHandler(SSLContext sslContext) {
        SSLEngine sslEngine = sslContext.createSSLEngine();
        sslEngine.setUseClientMode(true);
        return new SslHandler(sslEngine);
    }

    private void connectProxyServer() {

        bootstrap.connect(config.getStringValue("server.host"), config.getIntValue("server.port")).addListener(new ChannelFutureListener() {

            @Override
            public void operationComplete(ChannelFuture future) throws Exception {
                if (future.isSuccess()) {

                    // 连接成功，向服务器发送客户端认证信息（clientKey）
                    ClientChannelMannager.setCmdChannel(future.channel());
                    ProxyMessage proxyMessage = new ProxyMessage();
                    proxyMessage.setType(ProxyMessage.C_TYPE_AUTH);
                    proxyMessage.setUri(config.getStringValue("client.key"));
                    future.channel().writeAndFlush(proxyMessage);
                    sleepTimeMill = 1000;
                    logger.info("connect proxy server success, {}", future.channel());
                    System.out.println("connect success!");
                } else {
                    logger.warn("connect proxy server failed", future.cause());
                    System.out.println("connect failed ! Retry...");
                    // 连接失败，发起重连
                    reconnectWait();
                    connectProxyServer();
                }
            }
        });
    }

    @Override
    public void stop() {
    	bossGroup.shutdownGracefully();
        workerGroup.shutdownGracefully();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
    	System.out.println("disconnected ! Retry...");
        reconnectWait();
        connectProxyServer();
    }

    private void reconnectWait() {
        try {
            if (sleepTimeMill > 60000) {
                sleepTimeMill = 1000;
            }

            synchronized (this) {
                sleepTimeMill = sleepTimeMill * 2;
                wait(sleepTimeMill);
            }
        } catch (InterruptedException e) {
        }
    }

    public static void main(String[] args) {

        ContainerHelper.start(Arrays.asList(new Container[] { new ProxyClientContainer() }));
    }

}
