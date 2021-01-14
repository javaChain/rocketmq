FilterServer 调用shell创建进程已经被移除了版本>4.3.0,因为 错误: 找不到或无法加载主类 org.apache.rocketmq.filtersrv.FiltersrvStartup 这个类已经删除了
后面的文章基本不用看了. 下面的文章保留纪念
# 消息过滤FilterServer

基于类过滤是指在Broker端运行1个或多个消息过滤服务器(FilterServer),RocketMQ允许消息消费者自定义消息过滤实现类并将其代码上传到FilterServer上,消息消费者向FilterServer拉取消息,FilterServer将消息消费者的拉取命令转发到Broker,然后返回的消息执行消息过滤逻辑,最终返回给消费端

1. Broker进程所在的服务器会启动多个FilterServer进程
2. 消费者在订阅消息主题时会上传一个自定义的消息过滤实现类,FilterServer加载并实例化
3. 消息消费者(Consumer)向FilterServer发送消息拉取请求,FilterServer接收到消息消费者拉取消息请求后,FilterServer将消息拉取请求转发给Broker,Broker返回消息后在FilterServer端执行消息过滤逻辑,然后返回符合订阅信息的消息给消息消费者进行消费

![image-20210114092252902](note_images/image-20210114092252902.png)

## 实战

- 自定义FilterServer

```java
public class MessageFilterImpl implements MessageFilter {
 
    @Override
    public boolean match(MessageExt msg, FilterContext context) {
        String property = msg.getUserProperty("SequenceId");
        if (property != null) {
            int id = Integer.parseInt(property);
            if ((id % 3) == 0 && (id > 10)) {
                return true;
            }
        }
        return false;
    }
}
```

- Consumer

```java
public class Consumer {
   
      public static void main(String[] args) throws InterruptedException, MQClientException, IOException {
          DefaultMQPushConsumer consumer = new DefaultMQPushConsumer("ConsumerGroupNamecc4");
          consumer.setNamesrvAddr("127.0.0.1:9876");
          // 使用Java代码，在服务器做消息过滤
          String filterCode = MixAll.file2String("/Users/chenqi/IdeaProjects/rocketmq/example/src/main/java/org/apache/rocketmq/example/filter/MessageFilterImpl.java");
          consumer.subscribe("TopicFilter7", "org.apache.rocketmq.example.filter.MessageFilterImpl",
              filterCode);
   
          consumer.registerMessageListener(new MessageListenerConcurrently() {
   
              @Override
              public ConsumeConcurrentlyStatus consumeMessage(List<MessageExt> msgs,
                                                              ConsumeConcurrentlyContext context) {
                  System.out.println(Thread.currentThread().getName() + " Receive New Messages: " + msgs);
                  return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
              }
          });
   
          /**
           * Consumer对象在使用之前必须要调用start初始化，初始化一次即可<br>
           */
          consumer.start();
   
          System.out.println("Consumer Started.");
      }
  }
```

  





源码解析:

## FilterServer注册

1.Broker启动的时候会调用`filterServerManager#start`,每隔30s创建FilterServer进程

```java
//BrokerController#start
this.filterServerManager.start();

//FilterServerManager#start
/**
  * Broker 启动的时候会创建FilterServerManager定时任务每隔10s向Broker注册自己
  */
public void start() {

  this.scheduledExecutorService.scheduleAtFixedRate(new Runnable() {
    @Override
    public void run() {
      try {
        FilterServerManager.this.createFilterServer();
      } catch (Exception e) {
        log.error("", e);
      }
    }
  }, 1000 * 5, 1000 * 30, TimeUnit.MILLISECONDS);
}


	 /**
     * 读取配置文件中的FilterServerNums 如果当前运行的FilterServer进程数小于FilterServerNums
     * 那么就调用cmd命令行,启动FilterServer进程
     */
    public void createFilterServer() {
        int more =
            this.brokerController.getBrokerConfig().getFilterServerNums() - this.filterServerTable.size();
        String cmd = this.buildStartCommand();
        for (int i = 0; i < more; i++) {
            FilterServerUtil.callShell(cmd, log);
        }
    }
```

2. `BrokerStartup#start`,调用`BrokerController#registerBrokerAll`向NameSrv注册信息,包含了broker集群名称,broker地址,broker名称,brokerId,HAServerAddr,topicConfigWrapper,buildNewFilterServerList,oneway,broker注册超时时间和是否压缩注册

   ```java
    //broker向namesrv注册节点信息,包括了
   this.scheduledExecutorService.scheduleAtFixedRate(new Runnable() {
               @Override
               public void run() {
                   try {
                       //broker向namesrv注册节点信息
                       BrokerController.this.registerBrokerAll(true, false, brokerConfig.isForceRegister());
                   } catch (Throwable e) {
                       log.error("registerBrokerAll Exception", e);
                   }
               }
           }, 1000 * 10, Math.max(10000, Math.min(brokerConfig.getRegisterNameServerPeriod(), 60000)), TimeUnit.MILLISECONDS);
   ```

   ```java
   //BrokerController#doRegisterBrokerAll
   List<RegisterBrokerResult> registerBrokerResultList = this.brokerOuterAPI.registerBrokerAll(
               this.brokerConfig.getBrokerClusterName(),
               this.getBrokerAddr(),
               this.brokerConfig.getBrokerName(),
               this.brokerConfig.getBrokerId(),
               this.getHAServerAddr(),
               topicConfigWrapper,
               this.filterServerManager.buildNewFilterServerList(),
               oneway,
               this.brokerConfig.getRegisterBrokerTimeoutMills(),
               this.brokerConfig.isCompressedRegister());
   ```

   总结,broker启动的时候会判断FilterServer是否启动,没有启动则调用callshell启动FilterServer,然后broker在向NameSrv注册信息的时候,也会提交FilterServer信息.

## 类过滤模式订阅

消费者通过`DefaultMQPushConsumerImpl#subscribe`方法基于类过滤模式的消息过滤.

```java
public void subscribe(String topic, String fullClassName, String filterClassSource) throws MQClientException {
        try {
            SubscriptionData subscriptionData = FilterAPI.buildSubscriptionData(this.defaultMQPushConsumer.getConsumerGroup(),
                topic, "*");
            subscriptionData.setSubString(fullClassName);
            subscriptionData.setClassFilterMode(true);
            subscriptionData.setFilterClassSource(filterClassSource);
            this.rebalanceImpl.getSubscriptionInner().put(topic, subscriptionData);
          	//第一次不会进入,因为consumer没有启动mQClientFactory为null
            if (this.mQClientFactory != null) {
                this.mQClientFactory.sendHeartbeatToAllBrokerWithLock();
            }

        } catch (Exception e) {
            throw new MQClientException("subscription exception", e);
        }
    }
```

然后查看`consumer.start`

```
//获得订阅数据,如果为null 延迟拉取.
final SubscriptionData subscriptionData = this.rebalanceImpl.getSubscriptionInner().get(pullRequest.getMessageQueue().getTopic());
```

如果此时没有消息发送,继续向下走, 

```java
String subExpression = null;
        boolean classFilter = false;
//根据主题获取当前订阅信息,然后下面是构造 远程请求参数
        SubscriptionData sd = this.rebalanceImpl.getSubscriptionInner().get(pullRequest.getMessageQueue().getTopic());
        if (sd != null) {
            if (this.defaultMQPushConsumer.isPostSubscriptionWhenPull() && !sd.isClassFilterMode()) {
                subExpression = sd.getSubString();
            }

            classFilter = sd.isClassFilterMode();
        }

        //构建消息拉取时系统标记
        int sysFlag = PullSysFlag.buildSysFlag(
            commitOffsetEnable, // commitOffset
            true, // suspend
            subExpression != null, // subscription
            classFilter // class filter
        );
//pullKernelImpl与broker服务端进行交互
            this.pullAPIWrapper.pullKernelImpl(
                pullRequest.getMessageQueue(),
                subExpression,
                subscriptionData.getExpressionType(),
                subscriptionData.getSubVersion(),
                pullRequest.getNextOffset(),
                this.defaultMQPushConsumer.getPullBatchSize(),
                sysFlag,
                commitOffsetValue,
                BROKER_SUSPEND_MAX_TIME_MILLIS,
                CONSUMER_TIMEOUT_MILLIS_WHEN_SUSPEND,
                CommunicationMode.ASYNC,
                pullCallback
            );
```

然后到了`pullAPIWrapper#pullKernelImpl`

```
//如果消息过滤模式为类模式
if (PullSysFlag.hasClassFilterFlag(sysFlagInner)) {
    //通过topic和broker地址找到注册在broker上的filterServer地址,从FilterServer上拉取消息
    brokerAddr = computPullFromWhichFilterServer(mq.getTopic(), brokerAddr);
}
//向broker发送请求
PullResult pullResult = this.mQClientFactory.getMQClientAPIImpl().pullMessage(
brokerAddr,
requestHeader,
timeoutMillis,
communicationMode,
pullCallback);
```

