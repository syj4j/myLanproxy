package org.fengfei.lanproxy.server.config.db;

import com.jfinal.kit.PropKit;
import com.jfinal.plugin.activerecord.ActiveRecordPlugin;
import com.jfinal.plugin.activerecord.Db;
import com.jfinal.plugin.activerecord.Record;
import com.jfinal.plugin.activerecord.dialect.MysqlDialect;
import com.jfinal.plugin.druid.DruidPlugin;

public class JfinalConfig {
	public static void start() {
		PropKit.use("jfinal.properties");
		DruidPlugin dp = new DruidPlugin(PropKit.get("mySqlUrl"), PropKit.get("user"), PropKit.get("password"),
				PropKit.get("driverClass"));
		ActiveRecordPlugin arp = new ActiveRecordPlugin(dp);
//		arp.setShowSql(true);
		arp.setDialect(new MysqlDialect());// 切记配置方言
//		EhCachePlugin ehCachePlugin = new EhCachePlugin();
//		ehCachePlugin.start();
		dp.start();
		arp.start();
	}

	public static void main(String[] args) {
		start();
		String deviceId = "ffffffffd16dee4606c17b4b70b85f86";
		Record record = Db.findFirst(
				"select c.publicKey from device_nfc d join company c on d.company=c.companyName where d.device_uuid='"
						+ deviceId + "'");
		System.out.println(record.getStr("publicKey"));
	}
}
