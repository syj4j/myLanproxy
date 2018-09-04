package org.fengfei.lanproxy.server.config;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.Serializable;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.fengfei.lanproxy.common.Config;
import org.fengfei.lanproxy.common.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.reflect.TypeToken;
import com.jfinal.plugin.activerecord.Db;
import com.jfinal.plugin.activerecord.Record;

/**
 * server config
 *
 * @author fengfei
 *
 */
public class ProxyConfig implements Serializable {

	private static final long serialVersionUID = 1L;

	/** 配置文件为config.json */
	public static final String CONFIG_FILE;

	private static Logger logger = LoggerFactory.getLogger(ProxyConfig.class);

	static {

		// 代理配置信息存放在用户根目录下
		String dataPath = System.getProperty("user.home") + "/" + ".lanproxy/";
		File file = new File(dataPath);
		if (!file.isDirectory()) {
			file.mkdir();
		}

		CONFIG_FILE = dataPath + "/config.json";
	}

	/** 代理服务器绑定主机host */
	private String serverBind;

	/** 代理服务器与代理客户端通信端口 */
	private Integer serverPort;

	/** 配置服务绑定主机host */
	private String configServerBind;

	/** 配置服务端口 */
	private Integer configServerPort;

	/** 配置服务管理员用户名 */
	private String configAdminUsername;

	/** 配置服务管理员密码 */
	private String configAdminPassword;

	/** 代理客户端，支持多个客户端 */
	private List<Client> clients;

	/** 代理客户端，支持多个客户端 */
	private List<Record> dbClients;

	/** 更新配置后保证在其他线程即时生效 */
	private static ProxyConfig instance = new ProxyConfig();;

	/** 代理服务器为各个代理客户端（key）开启对应的端口列表（value） */
	private volatile Map<String, List<Integer>> clientInetPortMapping = new HashMap<String, List<Integer>>();

	/** 代理服务器上的每个对外端口（key）对应的代理客户端背后的真实服务器信息（value） */
	private volatile Map<Integer, String> inetPortLanInfoMapping = new HashMap<Integer, String>();

	/** 配置变化监听器 */
	private List<ConfigChangedListener> configChangedListeners = new ArrayList<ConfigChangedListener>();

	private ProxyConfig() {

		// 代理服务器主机和端口配置初始化
		this.serverPort = Config.getInstance().getIntValue("server.port");
		this.serverBind = Config.getInstance().getStringValue("server.bind", "0.0.0.0");

		// 配置服务器主机和端口配置初始化
		this.configServerPort = Config.getInstance().getIntValue("config.server.port");
		this.configServerBind = Config.getInstance().getStringValue("config.server.bind", "0.0.0.0");

		// 配置服务器管理员登录认证信息
		this.configAdminUsername = Config.getInstance().getStringValue("config.admin.username");
		this.configAdminPassword = Config.getInstance().getStringValue("config.admin.password");

		logger.info(
				"config init serverBind {}, serverPort {}, configServerBind {}, configServerPort {}, configAdminUsername {}, configAdminPassword {}",
				serverBind, serverPort, configServerBind, configServerPort, configAdminUsername, configAdminPassword);

		update(null);
	}

	public Integer getServerPort() {
		return this.serverPort;
	}

	public String getServerBind() {
		return serverBind;
	}

	public void setServerBind(String serverBind) {
		this.serverBind = serverBind;
	}

	public String getConfigServerBind() {
		return configServerBind;
	}

	public void setConfigServerBind(String configServerBind) {
		this.configServerBind = configServerBind;
	}

	public Integer getConfigServerPort() {
		return configServerPort;
	}

	public void setConfigServerPort(Integer configServerPort) {
		this.configServerPort = configServerPort;
	}

	public String getConfigAdminUsername() {
		return configAdminUsername;
	}

	public void setConfigAdminUsername(String configAdminUsername) {
		this.configAdminUsername = configAdminUsername;
	}

	public String getConfigAdminPassword() {
		return configAdminPassword;
	}

	public void setConfigAdminPassword(String configAdminPassword) {
		this.configAdminPassword = configAdminPassword;
	}

	public void setServerPort(Integer serverPort) {
		this.serverPort = serverPort;
	}

	public List<Client> getClients() {
		return clients;
	}

	public List<Record> getDbClients() {
		return dbClients;
	}

	/**
	 * 解析配置文件
	 */
	@SuppressWarnings("unused")
	public void update(String proxyMappingConfigJson) {
		int clientFlag = 0;
		int mappingFlag = 0;

		if (proxyMappingConfigJson == null) {
			update();
			return;
		}
		File file = new File(CONFIG_FILE);
		try {
			if (proxyMappingConfigJson == null && file.exists()) {
				InputStream in = new FileInputStream(file);
				byte[] buf = new byte[1024];
				ByteArrayOutputStream out = new ByteArrayOutputStream();
				int readIndex;
				while ((readIndex = in.read(buf)) != -1) {
					out.write(buf, 0, readIndex);
				}

				in.close();
				proxyMappingConfigJson = new String(out.toByteArray(), Charset.forName("UTF-8"));
			}
		} catch (Exception e) {
			throw new RuntimeException(e);
		}

		List<Client> clients = JsonUtil.json2object(proxyMappingConfigJson, new TypeToken<List<Client>>() {
		});
		if (clients == null) {
			clients = new ArrayList<Client>();
		}

		Map<String, List<Integer>> clientInetPortMapping = new HashMap<String, List<Integer>>();
		Map<Integer, String> inetPortLanInfoMapping = new HashMap<Integer, String>();

		// 获取数据库中的配置信息
		List<Record> dbClients = Db.find("select * from client");

		// 构造端口映射关系
		for (Client client : clients) {
			List<Record> ms = null;
			clientFlag = 0;

			String clientKey = client.getClientKey();
			if (clientInetPortMapping.containsKey(clientKey)) {
				throw new IllegalArgumentException("密钥同时作为客户端标识，不能重复： " + clientKey);
			}
			List<ClientProxyMapping> mappings = client.getProxyMappings();
			List<Integer> ports = new ArrayList<Integer>();
			clientInetPortMapping.put(clientKey, ports);

			// ++++++++++++++++++++++++++++
			Iterator<Record> iterator = dbClients.iterator();
			Record next = null;
			while (iterator.hasNext()) {
				next = iterator.next();
				// 更新数据库中的配置关系 by syj--2018.8.8
				if (next.getStr("clientKey").equals(client.getClientKey())) {
					next.set("name", client.getName());
					Db.update("client", next);
					iterator.remove();
					clientFlag = 1;
					ms = Db.find("select * from client_proxy_mapping where clientId=?", next.getInt("id"));
					break;
				}
			}
			if (clientFlag == 0) {
				Record dbClient = new Record().set("name", client.getName()).set("clientKey", client.getClientKey())
						.set("status", 2).set("serverHost", Config.getInstance().getStringValue("RemoteServerHost"))
						.set("serverPort", Config.getInstance().getIntValue("RemoteServerPort")).set("remoteConfig", 0);
				Db.save("client", dbClient);
			}
			// ++++++++++++++++++++++++++++

			for (ClientProxyMapping mapping : mappings) {
				mappingFlag = 0;

				Integer port = mapping.getInetPort();
				ports.add(port);
				if (inetPortLanInfoMapping.containsKey(port)) {
					throw new IllegalArgumentException("一个公网端口只能映射一个后端信息，不能重复: " + port);
				}

				inetPortLanInfoMapping.put(port, mapping.getLan());

				// ++++++++++++++++++++++++++++
				// 更新数据库中的配置关系 by syj--2018.8.8
				if (clientFlag == 0 || ms == null) {
					Record m = new Record().set("inetPort", mapping.getInetPort()).set("lan", mapping.getLan())
							.set("name", mapping.getName()).set("clientId", next.getInt("id")).set("onUsed", 0);
					Db.save("client_proxy_mapping", m);
				} else {
					Iterator<Record> iterator2 = ms.iterator();
					while (iterator2.hasNext()) {
						Record next2 = iterator2.next();
						if (next2.getInt("inetPort").equals(mapping.getInetPort())) {
							next2.set("lan", mapping.getLan()).set("name", mapping.getName());
							Db.update("client_proxy_mapping", next2);
							iterator2.remove();
							mappingFlag = 1;
							break;
						}
					}
					if (mappingFlag == 0) {
						Record m = new Record().set("inetPort", mapping.getInetPort()).set("lan", mapping.getLan())
								.set("name", mapping.getName()).set("clientId", next.getInt("id")).set("onUsed", 0);
						Db.save("client_proxy_mapping", m);
					}
				}
				// ++++++++++++++++++++++++++++
			}
			if (ms != null) {
				for (Record m : ms) {
					Db.delete("client_proxy_mapping", m);
				}
			}
		}

		for (Record c : dbClients) {
			Db.delete("delete from client_proxy_mapping where clientId=?", c.getInt("id"));
			Db.delete("client", c);
		}

		// 替换之前的配置关系
		// this.clientInetPortMapping = clientInetPortMapping;
		// this.inetPortLanInfoMapping = inetPortLanInfoMapping;
		// this.clients = clients;

		if (proxyMappingConfigJson != null) {
			try {
				FileOutputStream out = new FileOutputStream(file);
				out.write(proxyMappingConfigJson.getBytes(Charset.forName("UTF-8")));
				out.flush();
				out.close();
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		}

		// notifyconfigChangedListeners();
		update();
	}

	/**
	 * 读取数据库中的配置 by--syj 2018.8.8
	 */
	public void update() {
		List<Record> dbClients = Db.find("select * from client");
		List<Client> clients = new ArrayList<Client>();

		if (dbClients == null) {
			dbClients = new ArrayList<Record>();
		}

		Map<String, List<Integer>> clientInetPortMapping = new HashMap<String, List<Integer>>();
		Map<Integer, String> inetPortLanInfoMapping = new HashMap<Integer, String>();

		// 构造端口映射关系
		for (Record dbClient : dbClients) {
			Client client = new Client();
			client.setClientKey(dbClient.getStr("clientKey"));
			client.setName(dbClient.getStr("name"));
			client.setStatus(dbClient.getInt("status"));

			String clientKey = dbClient.getStr("clientKey");
			if (clientInetPortMapping.containsKey(clientKey)) {
				throw new IllegalArgumentException("密钥同时作为客户端标识，不能重复： " + clientKey);
			}
			List<Record> ms = Db.find("select * from client_proxy_mapping where clientId=?", dbClient.getInt("id"));
			List<ClientProxyMapping> mappings = new ArrayList<ClientProxyMapping>();
			for (Record record : ms) {
				ClientProxyMapping clientProxyMapping = new ClientProxyMapping();
				clientProxyMapping.setInetPort(record.getInt("inetPort"));
				clientProxyMapping.setLan(record.getStr("lan"));
				clientProxyMapping.setName(record.getStr("name"));
				mappings.add(clientProxyMapping);
			}

			List<Integer> ports = new ArrayList<Integer>();
			clientInetPortMapping.put(clientKey, ports);
			for (ClientProxyMapping mapping : mappings) {
				Integer port = mapping.getInetPort();
				ports.add(port);
				if (inetPortLanInfoMapping.containsKey(port)) {
					throw new IllegalArgumentException("一个公网端口只能映射一个后端信息，不能重复: " + port);
				}

				inetPortLanInfoMapping.put(port, mapping.getLan());
			}
			client.setProxyMappings(mappings);
			clients.add(client);
		}

		// 替换之前的配置关系
		this.clientInetPortMapping = clientInetPortMapping;
		this.inetPortLanInfoMapping = inetPortLanInfoMapping;
		this.clients = clients;

		notifyconfigChangedListeners();
	}

	/**
	 * 配置更新通知
	 */
	private void notifyconfigChangedListeners() {
		List<ConfigChangedListener> changedListeners = new ArrayList<ConfigChangedListener>(configChangedListeners);
		for (ConfigChangedListener changedListener : changedListeners) {
			changedListener.onChanged();
		}
	}

	/**
	 * 添加配置变化监听器
	 *
	 * @param configChangedListener
	 */
	public void addConfigChangedListener(ConfigChangedListener configChangedListener) {
		configChangedListeners.add(configChangedListener);
	}

	/**
	 * 移除配置变化监听器
	 *
	 * @param configChangedListener
	 */
	public void removeConfigChangedListener(ConfigChangedListener configChangedListener) {
		configChangedListeners.remove(configChangedListener);
	}

	/**
	 * 获取代理客户端对应的代理服务器端口
	 *
	 * @param clientKey
	 * @return
	 */
	public List<Integer> getClientInetPorts(String clientKey) {
		return clientInetPortMapping.get(clientKey);
	}

	/**
	 * 获取所有的clientKey
	 *
	 * @return
	 */
	public Set<String> getClientKeySet() {
		return clientInetPortMapping.keySet();
	}

	/**
	 * 根据代理服务器端口获取后端服务器代理信息
	 *
	 * @param port
	 * @return
	 */
	public String getLanInfo(Integer port) {
		return inetPortLanInfoMapping.get(port);
	}

	/**
	 * 返回需要绑定在代理服务器的端口（用于用户请求）
	 *
	 * @return
	 */
	public List<Integer> getUserPorts() {
		List<Integer> ports = new ArrayList<Integer>();
		Iterator<Integer> ite = inetPortLanInfoMapping.keySet().iterator();
		while (ite.hasNext()) {
			ports.add(ite.next());
		}

		return ports;
	}

	public static ProxyConfig getInstance() {
		return instance;
	}

	/**
	 * 代理客户端
	 *
	 * @author fengfei
	 *
	 */
	public static class Client implements Serializable {

		private static final long serialVersionUID = 1L;

		/** 客户端备注名称 */
		private String name;

		/** 代理客户端唯一标识key */
		private String clientKey;

		/** 代理客户端与其后面的真实服务器映射关系 */
		private List<ClientProxyMapping> proxyMappings;

		private int status;

		public String getClientKey() {
			return clientKey;
		}

		public void setClientKey(String clientKey) {
			this.clientKey = clientKey;
		}

		public List<ClientProxyMapping> getProxyMappings() {
			return proxyMappings;
		}

		public void setProxyMappings(List<ClientProxyMapping> proxyMappings) {
			this.proxyMappings = proxyMappings;
		}

		public String getName() {
			return name;
		}

		public void setName(String name) {
			this.name = name;
		}

		public int getStatus() {
			return status;
		}

		public void setStatus(int status) {
			this.status = status;
		}

	}

	/**
	 * 代理客户端与其后面真实服务器映射关系
	 *
	 * @author fengfei
	 *
	 */
	public static class ClientProxyMapping {

		/** 代理服务器端口 */
		private Integer inetPort;

		/** 需要代理的网络信息（代理客户端能够访问），格式 192.168.1.99:80 (必须带端口) */
		private String lan;

		/** 备注名称 */
		private String name;

		public Integer getInetPort() {
			return inetPort;
		}

		public void setInetPort(Integer inetPort) {
			this.inetPort = inetPort;
		}

		public String getLan() {
			return lan;
		}

		public void setLan(String lan) {
			this.lan = lan;
		}

		public String getName() {
			return name;
		}

		public void setName(String name) {
			this.name = name;
		}

	}

	/**
	 * 配置更新回调
	 *
	 * @author fengfei
	 *
	 */
	public static interface ConfigChangedListener {

		void onChanged();
	}
}
