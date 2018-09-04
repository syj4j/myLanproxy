package org.fengfei.lanproxy.protocol;

import java.io.ByteArrayOutputStream;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xerial.snappy.Snappy;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;

public class DecompressHandler extends ChannelOutboundHandlerAdapter{
	private static Logger logger = LoggerFactory.getLogger(DecompressHandler.class);

	@Override
	public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
		
		ByteBuf buf=(ByteBuf)msg;
		if(ctx.channel().attr(Constants.USE_COMPRESS).get()){
//			logger.debug("解压前:"+buf.readableBytes());
			byte[] bytes = new byte[buf.readableBytes()];
			buf.readBytes(bytes);
			byte[] uncompress = deflateUncompress(bytes);
//			logger.debug("解压后:"+uncompress.length);
			ByteBuf newbuf = Unpooled.wrappedBuffer(uncompress);
			ctx.write(newbuf);
		}else{
//			logger.debug("不解压:"+buf.readableBytes());
//			super.write(ctx, msg, promise);
			ctx.write(buf);
		}
	}

	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
//		cause.printStackTrace();
		logger.error(cause.getStackTrace().toString());
		super.exceptionCaught(ctx, cause);
	}

	public byte[] deflateUncompress(byte[] input) throws DataFormatException {
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		Inflater decompressor = new Inflater();
		try {
			decompressor.setInput(input);
			final byte[] buf = new byte[2048];
			while (!decompressor.finished()) {
				int count = decompressor.inflate(buf);
				bos.write(buf, 0, count);
			}
		} finally {
			decompressor.end();
		}
		return bos.toByteArray();
	}

}
