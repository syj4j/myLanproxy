package org.fengfei.lanproxy.protocol;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xerial.snappy.Snappy;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOption;
import io.netty.channel.SimpleChannelInboundHandler;

public class CompressHandler extends ChannelInboundHandlerAdapter {
	private static Logger logger = LoggerFactory.getLogger(CompressHandler.class);

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
		ByteBuf buf = (ByteBuf) msg;
		if (ctx.channel().attr(Constants.USE_COMPRESS).get()) {
//			logger.debug("压缩前:" + buf.readableBytes());
			byte[] bytes = new byte[buf.readableBytes()];
			buf.readBytes(bytes);
			byte[] compress = deflateCompress(bytes);
//			logger.debug("压缩后:" + compress.length);
			ByteBuf newbuf = Unpooled.wrappedBuffer(compress);
			ctx.fireChannelRead(newbuf);
		} else {
//			logger.debug("不压缩:" + buf.readableBytes());
			// super.channelRead(ctx, msg);
			ctx.fireChannelRead(buf);
		}
	}

	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
		// cause.printStackTrace();
		logger.error(cause.getMessage());
		super.exceptionCaught(ctx, cause);
	}

	public byte[] deflateCompress(byte input[]) {
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		Deflater compressor = new Deflater(9);
		try {
			compressor.setInput(input);
			compressor.finish();
			final byte[] buf = new byte[2048];
			while (!compressor.finished()) {
				int count = compressor.deflate(buf);
				bos.write(buf, 0, count);
			}
		} finally {
			compressor.end();
		}
		return bos.toByteArray();
	}

}
