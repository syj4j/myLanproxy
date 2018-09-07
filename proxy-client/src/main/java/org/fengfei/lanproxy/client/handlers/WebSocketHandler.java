package org.fengfei.lanproxy.client.handlers;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.fengfei.lanproxy.client.ClientChannelMannager;
import org.fengfei.lanproxy.common.Config;
import org.fengfei.lanproxy.protocol.ProxyMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.ContinuationWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshakerFactory;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.util.CharsetUtil;

public class WebSocketHandler extends ChannelInboundHandlerAdapter {
	private static Logger logger = LoggerFactory.getLogger(WebSocketHandler.class);
	
	// 这个map用于保存userId和SocketChannel的对应关系
		public static Map<String, Channel> map = new ConcurrentHashMap<String, Channel>();

		// 用于websocket握手的处理类
		private WebSocketServerHandshaker handshaker;

		@Override
		public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
			if (msg instanceof FullHttpRequest) {
				// websocket连接请求
				handleHttpRequest(ctx, (FullHttpRequest) msg);
			} else if (msg instanceof WebSocketFrame) {
				// websocket业务处理
				handleWebSocketRequest(ctx, (WebSocketFrame) msg);
			}
		}

		@Override
		public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
			ctx.flush();
		}
		
		@Override
		public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
			cause.printStackTrace();
			ctx.close();
		}

		@Override
		public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
			if (evt instanceof WebSocketServerProtocolHandler.HandshakeComplete) {
				// 完成握手
				// System.out.println("握手完成");
			} else {
				super.userEventTriggered(ctx, evt);
			}
		}

		@Override
		public void channelActive(ChannelHandlerContext ctx) throws Exception {
			logger.info("proxy channel connect, {}", ctx.channel());
			super.channelActive(ctx);
		}

		@Override
		public void channelInactive(ChannelHandlerContext ctx) throws Exception {

			ctx.channel().close();
			logger.info("proxy channel close, {}", ctx.channel());
			super.channelInactive(ctx);
		}

		private void handleHttpRequest(ChannelHandlerContext ctx, FullHttpRequest req) throws Exception {
			// 获取userId
//			QueryStringDecoder uridecoder = new QueryStringDecoder(req.uri());
//			Map<String, List<String>> params = uridecoder.parameters();
//			String clientKey = params.get("clientKey").get(0);
//			List<Integer> ports = ProxyConfig.getInstance().getClientInetPorts(clientKey);
//	        if (ports == null) {
//	            logger.info("error clientKey {}, {}", clientKey, ctx.channel());
//	            ctx.channel().close();
//	            return;
//	        }
//
//	        Channel channel = ProxyChannelManager.getCmdChannel(clientKey);
//	        if (channel != null) {
//	            logger.warn("exist channel for key {}, {}", clientKey, channel);
//	            ctx.channel().close();
//	            return;
//	        }
//
//	        logger.info("set port => channel, {}, {}, {}", clientKey, ports, ctx.channel());
//	        ProxyChannelManager.addCmdChannel(ports, clientKey, ctx.channel());

			// Http解码失败，向服务器指定传输的协议为Upgrade：websocket
			if (!req.decoderResult().isSuccess() || (!"websocket".equals(req.headers().get("Upgrade")))) {
				sendHttpResponse(ctx, req,
						new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.BAD_REQUEST));
				return;
			}
			// 握手相应处理,创建websocket握手的工厂类，
			WebSocketServerHandshakerFactory wsFactory = new WebSocketServerHandshakerFactory("ws://localhost:33891/ws",
					null, false);
			// 根据工厂类和HTTP请求创建握手类
			handshaker = wsFactory.newHandshaker(req);
			if (ctx == null || this.handshaker == null || ctx.isRemoved()) {
				throw new Exception("尚未握手成功，无法向客户端发送WebSocket消息");
			}
			if (handshaker == null) {
				// 不支持websocket
				WebSocketServerHandshakerFactory.sendUnsupportedVersionResponse(ctx.channel());
			} else {
				// 通过它构造握手响应消息返回给客户端
				handshaker.handshake(ctx.channel(), req);
			}

		}

		private void handleWebSocketRequest(ChannelHandlerContext ctx, WebSocketFrame req) {
			if (req instanceof CloseWebSocketFrame) {
				// 关闭websocket连接
				handshaker.close(ctx.channel(), (CloseWebSocketFrame) req.retain());
				return;
			}
			if (req instanceof PingWebSocketFrame) {
				ctx.write(new PongWebSocketFrame(req.content().retain()));
				return;
			}
			if (req instanceof PongWebSocketFrame) {
				logger.debug("PongWebSocketFrame");
				return;
			}
			if (req instanceof TextWebSocketFrame) {
				if("auth".equals(((TextWebSocketFrame) req).text())){
					// 连接成功，向服务器发送客户端认证信息（clientKey）
                    ClientChannelMannager.setCmdChannel(ctx.channel());
                    ProxyMessage proxyMessage = new ProxyMessage();
                    proxyMessage.setType(ProxyMessage.C_TYPE_AUTH);
                    proxyMessage.setUri(Config.getInstance().getStringValue("client.key"));
                    ctx.channel().writeAndFlush(proxyMessage);
                    logger.info("connect proxy server success, {}. ProxyMessage:{}", ctx.channel(),proxyMessage);
				}else{
					sendWebSocketResponse(ctx, "fromServer:" + ((TextWebSocketFrame) req).text());
				}
				return;
			}
			if (!(req instanceof BinaryWebSocketFrame || req instanceof ContinuationWebSocketFrame)) {
				throw new UnsupportedOperationException("不支持的消息类型:"+req.getClass());
			}
			if(req instanceof BinaryWebSocketFrame){
				logger.debug("BinaryWebSocketFrame");
			}else{
				logger.debug("ContinuationWebSocketFrame");
			}
			ByteBuf buf = req.content();
			ctx.fireChannelRead(buf);
		}

		private void sendHttpResponse(ChannelHandlerContext ctx, FullHttpRequest req, FullHttpResponse res) {
			// BAD_REQUEST(400) 客户端请求错误返回的应答消息
			if (res.status().code() != 200) {
				// 将返回的状态码放入缓存中，Unpooled没有使用缓存池
				ByteBuf buf = Unpooled.copiedBuffer(res.status().toString(), CharsetUtil.UTF_8);
				res.content().writeBytes(buf);
				buf.release();
				HttpUtil.setContentLength(res, res.content().readableBytes());
			}
			// 发送应答消息
			ChannelFuture cf = ctx.channel().writeAndFlush(res);
			// 非法连接直接关闭连接
			if (!HttpUtil.isKeepAlive(req) || res.status().code() != 200) {
				cf.addListener(ChannelFutureListener.CLOSE);
			}
		}

		private void sendWebSocketResponse(ChannelHandlerContext ctx, String response) {
			ctx.write(new TextWebSocketFrame(response));
		}

		private void broadcastWebSocketResponse(String response) {
			// channelGroup.writeAndFlush(new TextWebSocketFrame(response));
		}
}
