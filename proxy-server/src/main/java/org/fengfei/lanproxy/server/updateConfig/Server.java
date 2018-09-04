package org.fengfei.lanproxy.server.updateConfig;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Set;

import org.fengfei.lanproxy.common.Config;
import org.fengfei.lanproxy.server.config.ProxyConfig;

public class Server extends Thread {
	private Selector selector;
	private ByteBuffer readBuffer = ByteBuffer.allocate(1024);// 调整缓存的大小可以看到打印输出的变化
	private ByteBuffer sendBuffer = ByteBuffer.allocate(1024);// 调整缓存的大小可以看到打印输出的变化

	String str;

	// static part goes here
	private static Server server = new Server();

	public static void init() {
		server.start();
		System.out.println("server started...");
	}

	@Override
	public void run() {
		startWork();
	}

	public void startWork() {
		try {
			// 打开服务器套接字通道
			ServerSocketChannel ssc = ServerSocketChannel.open();
			// 服务器配置为非阻塞
			ssc.configureBlocking(false);
			// 进行服务的绑定
			ssc.bind(new InetSocketAddress(Config.getInstance().getStringValue("updateConfig.server"),
					Config.getInstance().getIntValue("updateConfig.port")));

			// 通过open()方法找到Selector
			selector = Selector.open();
			// 注册到selector，等待连接
			ssc.register(selector, SelectionKey.OP_ACCEPT);

			while (!Thread.currentThread().isInterrupted()) {
				selector.select();
				Set<SelectionKey> keys = selector.selectedKeys();
				Iterator<SelectionKey> keyIterator = keys.iterator();
				while (keyIterator.hasNext()) {
					SelectionKey key = keyIterator.next();
					if (!key.isValid()) {
						continue;
					}
					if (key.isAcceptable()) {
						accept(key);
					} else if (key.isReadable()) {
						read(key);
					} else if (key.isWritable()) {
						write(key);
					}
					keyIterator.remove(); // 该事件已经处理，可以丢弃
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	private void write(SelectionKey key) throws IOException, ClosedChannelException {
		SocketChannel channel = (SocketChannel) key.channel();

		sendBuffer.clear();
		sendBuffer.put(str.getBytes());
		sendBuffer.flip();
		channel.write(sendBuffer);
		channel.register(selector, SelectionKey.OP_READ);
	}

	private void read(SelectionKey key) throws IOException {
		SocketChannel socketChannel = (SocketChannel) key.channel();

		// Clear out our read buffer so it's ready for new data
		this.readBuffer.clear();
		// readBuffer.flip();
		// Attempt to read off the channel
		int numRead;
		try {
			numRead = socketChannel.read(this.readBuffer);
			if (numRead > 0) {
				str = new String(readBuffer.array(), 0, numRead).trim();
				System.out.println(str);
				if (str.equals("updateConfig")) {
					System.out.println("updateConfig...");
					ProxyConfig.getInstance().update();
				}
				socketChannel.register(selector, SelectionKey.OP_WRITE);
			} else {
				key.cancel();
			}
		} catch (IOException e) {
			// The remote forcibly closed the connection, cancel
			// the selection key and close the channel.
			key.cancel();
			return;
		}

	}

	private void accept(SelectionKey key) throws IOException {
		ServerSocketChannel ssc = (ServerSocketChannel) key.channel();
		SocketChannel clientChannel = ssc.accept();
		clientChannel.configureBlocking(false);
		clientChannel.register(selector, SelectionKey.OP_READ);
		System.out.println("updateConfig_client connected " + clientChannel.getRemoteAddress());
	}

	public static void main(String[] args) throws IOException {
		System.out.println("server started...");
		new Server().start();
	}
}
