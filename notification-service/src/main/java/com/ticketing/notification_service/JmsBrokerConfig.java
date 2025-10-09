package com.ticketing.notification_service;

import org.apache.activemq.broker.BrokerService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JmsBrokerConfig {
  @Bean(initMethod = "start", destroyMethod = "stop")
  public BrokerService brokerService() throws Exception {
    BrokerService broker = new BrokerService();
    broker.setPersistent(false); // in-memory
    broker.setUseJmx(false);
    broker.addConnector("tcp://localhost:61616");
    return broker;
  }
}
