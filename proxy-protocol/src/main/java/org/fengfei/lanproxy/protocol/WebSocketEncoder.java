package org.fengfei.lanproxy.protocol;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;

public class WebSocketEncoder extends ChannelOutboundHandlerAdapter{
	private static Logger logger = LoggerFactory.getLogger(WebSocketEncoder.class);

	@Override
	public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
		if(msg instanceof FullHttpResponse){
			ctx.write(msg);
		}else{
			ctx.write(new BinaryWebSocketFrame((ByteBuf)msg));
		}
	}

	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
//		cause.printStackTrace();
		logger.error(cause.getStackTrace().toString());
		super.exceptionCaught(ctx, cause);
	}
}
