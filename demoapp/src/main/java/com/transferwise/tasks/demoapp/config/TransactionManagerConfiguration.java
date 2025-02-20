package com.transferwise.tasks.demoapp.config;

import com.transferwise.common.gaffer.ServiceRegistry;
import com.transferwise.common.gaffer.ServiceRegistryHolder;
import com.transferwise.common.gaffer.jdbc.DataSourceImpl;
import com.zaxxer.hikari.HikariDataSource;
import java.util.Properties;
import javax.sql.DataSource;
import javax.transaction.TransactionManager;
import javax.transaction.UserTransaction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.flyway.FlywayDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.datasource.IsolationLevelDataSourceAdapter;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.jta.JtaTransactionManager;

@Configuration
@EnableTransactionManagement
@Slf4j
public class TransactionManagerConfiguration {

  @Autowired
  private Environment env;

  @Bean
  public UserTransaction gafferUserTransaction() {
    ServiceRegistry serviceRegistry = ServiceRegistryHolder.getServiceRegistry();
    return serviceRegistry.getUserTransaction();
  }

  @Bean
  public TransactionManager gafferTransactionManager() {
    ServiceRegistry serviceRegistry = ServiceRegistryHolder.getServiceRegistry();
    return serviceRegistry.getTransactionManager();
  }

  @Bean
  public JtaTransactionManager transactionManager() {
    ServiceRegistry serviceRegistry = ServiceRegistryHolder.getServiceRegistry();
    JtaTransactionManager jtaTransactionManager = new JtaTransactionManager(gafferUserTransaction(), gafferTransactionManager());
    jtaTransactionManager.setAllowCustomIsolationLevels(true);
    jtaTransactionManager.setTransactionSynchronizationRegistry(serviceRegistry.getTransactionSynchronizationRegistry());
    return jtaTransactionManager;
  }

  @Bean
  @Primary
  public DataSource jtaDataSource() {
    DataSourceImpl dataSourceImpl = new DataSourceImpl();
    dataSourceImpl.setUniqueName("twTasksDemoappDb");
    dataSourceImpl.setDataSource(dataSource());
    dataSourceImpl.setValidationTimeoutSeconds(10);
    dataSourceImpl.setRegisterAsMBean(false);

    IsolationLevelDataSourceAdapter da = new IsolationLevelDataSourceAdapter();
    da.setTargetDataSource(dataSourceImpl);
    return da;
  }

  @Bean
  @FlywayDataSource
  public DataSource flywayDataSource() {
    HikariDataSource hds = new HikariDataSource();
    hds.setPoolName("demoapp_flyway");
    hds.setJdbcUrl(env.getProperty("spring.datasource.url"));
    hds.setUsername(env.getProperty("spring.flyway.user"));
    hds.setPassword(env.getProperty("spring.flyway.password"));

    hds.setMinimumIdle(0);
    hds.setMaximumPoolSize(1);

    return hds;
  }

  private DataSource dataSource() {
    HikariDataSource hds = new HikariDataSource();
    hds.setPoolName("demoapp");
    hds.setJdbcUrl(env.getProperty("spring.datasource.url"));
    hds.setUsername(env.getProperty("spring.datasource.username"));
    hds.setPassword(env.getProperty("spring.datasource.password"));
    hds.setConnectionTimeout(10000);
    hds.setTransactionIsolation("TRANSACTION_READ_COMMITTED");
    hds.setRegisterMbeans(true);
    hds.setMaxLifetime(1800000);
    hds.setMaximumPoolSize(100);
    hds.setMinimumIdle(10);
    hds.setIdleTimeout(60000);
    hds.setValidationTimeout(10000);
    //hds.setLeakDetectionThreshold(10000);

    if (hds.getJdbcUrl().contains("mariadb")) {
      Properties props = hds.getDataSourceProperties();
      props.put("useServerPrepStmts", "true");
      props.put("prepStmtCacheSize", "256");
      props.put("prepStmtCacheSqlLimit", "4096");
    } else if (hds.getJdbcUrl().contains("mysql")) {
      hds.addDataSourceProperty("cachePrepStmts", "true");
      hds.addDataSourceProperty("prepStmtCacheSize", "250");
      hds.addDataSourceProperty("prepStmtCacheSqlLimit", "4096"); // Long live the Hibernate!
      hds.addDataSourceProperty("useServerPrepStmts", "true");

      hds.addDataSourceProperty("cacheCallableStmts", "true");
      hds.addDataSourceProperty("callableStmtCacheSize", "250");

      hds.addDataSourceProperty("cacheResultSetMetadata", "true");
      hds.addDataSourceProperty("metadataCacheSize", "250");

      hds.addDataSourceProperty("useLocalSessionState", "true");
      hds.addDataSourceProperty("useLocalTransactionState", "true"); // Dangerous to have true.
      hds.addDataSourceProperty("cacheServerConfiguration", "true");
      hds.addDataSourceProperty("elideSetAutoCommits", "true");
      hds.addDataSourceProperty("alwaysSendSetIsolation", "false");

      hds.addDataSourceProperty("rewriteBatchedStatements", "true");
      hds.addDataSourceProperty("maintainTimeStats", "false");
      hds.addDataSourceProperty("useJvmCharsetConverters", "true");
      hds.addDataSourceProperty("useSSL", "false");
      hds.addDataSourceProperty("characterEncoding", "UTF-8");
      hds.addDataSourceProperty("useUnicode", "true");

      hds.addDataSourceProperty("dontTrackOpenResources", "true");
      hds.addDataSourceProperty("holdResultsOpenOverStatementClose", "true");
      hds.addDataSourceProperty("enableQueryTimeouts", "false");

      hds.addDataSourceProperty("connectTimeout", "10000");
      hds.addDataSourceProperty("socketTimeout", "600000");
    }

    return hds;
  }
}
