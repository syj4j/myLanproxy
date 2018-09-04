package org.fengfei.lanproxy.server.updateConfig;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.net.UnknownHostException;

import org.fengfei.lanproxy.common.Config;

public class BioClient {
	public static void main(String[] args) throws UnknownHostException, IOException {
		Socket socket = new Socket("202.102.86.26",
				Config.getInstance().getIntValue("updateConfig.port"));// BIO 阻塞
		System.out.println("连接成功");
		BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(socket.getInputStream()));

		// 下面这种写法，不用关闭客户端，服务器端也是可以收到的
		{
			 PrintWriter printWriter = new
			 PrintWriter(socket.getOutputStream(), true);
			 printWriter.println("updateConfig");
			 printWriter.flush();
		}
		// 这种写法必须关闭客户端，服务器端才可以收到 NIO不用
//		{
//			socket.getOutputStream().write("updateConfig".getBytes());
//			socket.getOutputStream().flush();
//			// 必须关闭BIO服务器才能收到消息.NIO服务器不需要关闭
//			socket.close();
//		}
		byte[] buf = new byte[2048];
		System.out.println("准备读取数据~~");

//		while (true) {
//			try {
				// 两种读取数据方式
				int count = socket.getInputStream().read(buf); // 会阻塞
				String fromServer = new String(buf).trim();
				System.out.println("方式一： 读取数据" + fromServer + " count = " + count);
				if(fromServer.equals("updateConfig")){
					System.out.println("updateConfig...");
				}
//				 String readFromServer = bufferedReader.readLine();//可以读取到数据,会阻塞,直到遇见\n
//				 System.out.println("方式二： 读取数据" + readFromServer);
//				Thread.sleep(1 * 1000);
				socket.close();
//			} catch (InterruptedException e) {
//				// TODO Auto-generated catch block
//				e.printStackTrace();
//			}
			// break;
//		}

	}

}
