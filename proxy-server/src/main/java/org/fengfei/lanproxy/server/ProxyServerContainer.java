package org.fengfei.lanproxy.server;

import java.io.IOException;
import java.net.BindException;
import java.util.Arrays;
import java.util.List;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;

import org.fengfei.lanproxy.common.Config;
import org.fengfei.lanproxy.common.container.Container;
import org.fengfei.lanproxy.common.container.ContainerHelper;
import org.fengfei.lanproxy.protocol.CompressHandler;
import org.fengfei.lanproxy.protocol.DecompressHandler;
import org.fengfei.lanproxy.protocol.IdleCheckHandler;
import org.fengfei.lanproxy.protocol.ProxyMessageDecoder;
import org.fengfei.lanproxy.protocol.ProxyMessageEncoder;
import org.fengfei.lanproxy.protocol.WebSocketEncoder;
import org.fengfei.lanproxy.server.config.ProxyConfig;
import org.fengfei.lanproxy.server.config.ProxyConfig.ConfigChangedListener;
import org.fengfei.lanproxy.server.config.db.JfinalConfig;
import org.fengfei.lanproxy.server.config.web.WebConfigContainer;
import org.fengfei.lanproxy.server.handlers.ServerChannelHandler;
import org.fengfei.lanproxy.server.handlers.UserChannelHandler;
import org.fengfei.lanproxy.server.handlers.WebSocketHandler;
import org.fengfei.lanproxy.server.handlers.WebSocketServerChannelHandler;
import org.fengfei.lanproxy.server.metrics.handler.BytesMetricsHandler;
import org.fengfei.lanproxy.server.updateConfig.Server;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.stream.ChunkedWriteHandler;

public class ProxyServerContainer implements Container, ConfigChangedListener {

	/**
	 * max packet is 2M.
	 */
	private static final int MAX_FRAME_LENGTH = 2 * 1024 * 1024;

	private static final int LENGTH_FIELD_OFFSET = 0;

	private static final int LENGTH_FIELD_LENGTH = 4;

	private static final int INITIAL_BYTES_TO_STRIP = 0;

	private static final int LENGTH_ADJUSTMENT = 0;

	private static Logger logger = LoggerFactory.getLogger(ProxyServerContainer.class);

	private NioEventLoopGroup serverWorkerGroup;

	private NioEventLoopGroup serverBossGroup;

	public ProxyServerContainer() {

		serverBossGroup = new NioEventLoopGroup();
		serverWorkerGroup = new NioEventLoopGroup();

		ProxyConfig.getInstance().addConfigChangedListener(this);
	}

	@Override
	public void start() {
		ServerBootstrap bootstrap = new ServerBootstrap();
		bootstrap.group(serverBossGroup, serverWorkerGroup).channel(NioServerSocketChannel.class)
				.childHandler(new ChannelInitializer<SocketChannel>() {

					@Override
					public void initChannel(SocketChannel ch) throws Exception {
						ch.pipeline().addLast(new ProxyMessageDecoder(MAX_FRAME_LENGTH, LENGTH_FIELD_OFFSET,
								LENGTH_FIELD_LENGTH, LENGTH_ADJUSTMENT, INITIAL_BYTES_TO_STRIP));
						ch.pipeline().addLast(new ProxyMessageEncoder());
						ch.pipeline().addLast(new IdleCheckHandler(IdleCheckHandler.READ_IDLE_TIME,
								IdleCheckHandler.WRITE_IDLE_TIME, 0));
						ch.pipeline().addLast(new ServerChannelHandler());
					}
				});

		try {
			bootstrap.bind(ProxyConfig.getInstance().getServerBind(), ProxyConfig.getInstance().getServerPort()).get();
			logger.info("proxy server start on port " + ProxyConfig.getInstance().getServerPort());
		} catch (Exception ex) {
			throw new RuntimeException(ex);
		}

		if (Config.getInstance().getBooleanValue("server.ssl.enable", false)) {
			String host = Config.getInstance().getStringValue("server.ssl.bind", "0.0.0.0");
			int port = Config.getInstance().getIntValue("server.ssl.port");
			initializeSSLTCPTransport(host, port, new SslContextCreator().initSSLContext());
		}

		startWebSocketServer("/ws");
		startUserPort();

	}

	private void startWebSocketServer(String s) {
		final String router = s;
		final ServerBootstrap bootstrap = new ServerBootstrap();
		bootstrap.group(serverBossGroup,serverWorkerGroup).channel(NioServerSocketChannel.class)
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
						pipeline.addLast(new WebSocketServerChannelHandler());
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
		} catch (Exception e) {
			e.printStackTrace();
		}

	}

	private void initializeSSLTCPTransport(String host, int port, final SSLContext sslContext) {
		ServerBootstrap b = new ServerBootstrap();
		b.group(serverBossGroup, serverWorkerGroup).channel(NioServerSocketChannel.class)
				.childHandler(new ChannelInitializer<SocketChannel>() {

					@Override
					public void initChannel(SocketChannel ch) throws Exception {
						ChannelPipeline pipeline = ch.pipeline();
						try {
							pipeline.addLast("ssl", createSslHandler(sslContext,
									Config.getInstance().getBooleanValue("server.ssl.needsClientAuth", false)));
							ch.pipeline().addLast(new ProxyMessageDecoder(MAX_FRAME_LENGTH, LENGTH_FIELD_OFFSET,
									LENGTH_FIELD_LENGTH, LENGTH_ADJUSTMENT, INITIAL_BYTES_TO_STRIP));
							ch.pipeline().addLast(new ProxyMessageEncoder());
							ch.pipeline().addLast(new IdleCheckHandler(IdleCheckHandler.READ_IDLE_TIME,
									IdleCheckHandler.WRITE_IDLE_TIME, 0));
							ch.pipeline().addLast(new ServerChannelHandler());
						} catch (Throwable th) {
							logger.error("Severe error during pipeline creation", th);
							throw th;
						}
					}
				});
		try {

			// Bind and start to accept incoming connections.
			ChannelFuture f = b.bind(host, port);
			f.sync();
			logger.info("proxy ssl server start on port {}", port);
		} catch (InterruptedException ex) {
			logger.error("An interruptedException was caught while initializing server", ex);
		}
	}

	private void startUserPort() {
		ServerBootstrap bootstrap = new ServerBootstrap();
		bootstrap.group(serverBossGroup, serverWorkerGroup).channel(NioServerSocketChannel.class)
				.childHandler(new ChannelInitializer<SocketChannel>() {

					@Override
					public void initChannel(SocketChannel ch) throws Exception {
						ch.pipeline().addFirst(new BytesMetricsHandler());

						ch.pipeline().addLast(new CompressHandler());
						ch.pipeline().addLast(new DecompressHandler());
						ch.pipeline().addLast(new UserChannelHandler());
					}
				});

		List<Integer> ports = ProxyConfig.getInstance().getUserPorts();
		for (int port : ports) {
			try {
				bootstrap.bind(port).get();
				logger.info("bind user port " + port);
			} catch (Exception ex) {

				// BindException表示该端口已经绑定过
				if (!(ex.getCause() instanceof BindException)) {
					throw new RuntimeException(ex);
				}
			}
		}

	}

	@Override
	public void onChanged() {
		startUserPort();
	}

	@Override
	public void stop() {
		serverBossGroup.shutdownGracefully();
		serverWorkerGroup.shutdownGracefully();
	}

	private ChannelHandler createSslHandler(SSLContext sslContext, boolean needsClientAuth) {
		SSLEngine sslEngine = sslContext.createSSLEngine();
		sslEngine.setUseClientMode(false);
		if (needsClientAuth) {
			sslEngine.setNeedClientAuth(true);
		}

		return new SslHandler(sslEngine);
	}

	public static void main(String[] args) {
		JfinalConfig.start();
		Server.init();
		ContainerHelper.start(Arrays.asList(new Container[] { new ProxyServerContainer(), new WebConfigContainer() }));
	}

}
