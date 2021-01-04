# RocketMQ源码解读

>源码debug:
>
>1. 首先github下载rocketmq的4.7.1的代码
>2. 配置nameserver(NamesrvController),broker(BrokerStartup)的ROCKET_HOME环境变量和
>program argument -c "E:\Program Files\rocketmq-all-4.7.1-bin-release\conf\broker.conf"
>3.	依次启动namesrv/broker/producer/consumer

![image-20201228195616173](note_images\image-20201228195616173.png)



`requestCode 是broker和client之间数据处理的桥梁`

#问题?
- broker 双主的时候到底是怎么处理producer发送到的消息的,如果发送到master1,master2是怎么处理的呢?
> 猜测 如果正常逻辑,master1和master2的消息是不会同步,不同步的话消息会不一致?
- 读队列和写队列是如何工作的?
- consumer是如何定位到哪一个读队列,producer怎么选择哪一个写队列?

#第二章
NameServer如何保持一致?

    1. 服务注册(broker新增): broker启动的时候会向NameServer注册自己的信息
        2. 服务剔除(broker关闭或宕机):
       - broker主动关闭 会调用方法RouteInfoManager#unregisterBroker()
       - broker宕机 NameServer每隔10S会发送心跳包给broker探活,如果120s内没有回复则剔除broker
        3. 路由发现: producer/consumer 启动会主动拉取最新的路由
NameServer相互不通信如何保证一致?
        nameserver 集群之间不需要通信,broker服务启动的时候会向每一个NameServer注册自己的信息, producer会从NameServer之中获取broker的服务器信息

nameserver 动态路由发现与剔除机制?
    ap/cp 选型 zookeeper是cp的 强一致性,那么server是ap高可用,软一致性

NameServer启动流程
NameServer启动的时候,首先加载配置文件,然后启动了2个线程池 一个10s是扫描broker,一个是10分钟打印配置文件
初始化了netty remoteServer的必要参数. 然后注册了停止函数钩子(需要借鉴). 启动remoteServer netty.服务就启动了

broker启动 加载配置文件, 初始化很多定时任务, 通过netty包装了请求向namesrv发送连接

#第三章
1. 消息队列如何进行负载?
2. 消息发送如何实现高可用?
3. 批量消息发送如何实现一致?


producer发送消息基本流程:
验证消息->查找路由->消息发送
producer启动流程:
```
- producer.start();
    1. producer启动的时候会检查是否有producerGroup,然后把instanceName改为pid,避免同一个物理机启动2个producer无法启动
    2. 注册服务到MQClientInstance
    3. MQClientInstance启动,启动了如下的后台任务
    // Start request-response channel
    this.mQClientAPIImpl.start();
    // Start various schedule tasks
    this.startScheduledTask();
    // Start pull service
    this.pullMessageService.start();
    // Start rebalance service
    this.rebalanceService.start();
    // Start push service
    this.defaultMQProducer.getDefaultMQProducerImpl().start(false);

-  SendResult sendResult = producer.send(msg); 发送消息要先通过topic找到路由信息,然后找到对应的队列进行消息发送
查找路由信息 首先遍历broker然后遍历ConsumerQueue,所以如果是集群,查找的结构应该是:
broker-a-0,broker-a-1,broker-b-0,broker-b-1
```

具体如下:

```
this.defaultMQProducerImpl.send(msg) -> DefaultMQProducerImpl#sendDefaultImpl() ->
TopicPublishInfo topicPublishInfo = this.tryToFindTopicPublishInfo(msg.getTopic());
tryToFindTopicPublishInfo() 根据主题查找路由信息 -> 深入进去updateTopicRouteInfoFromNameServer() 从NameServer之中找到
路由信息之后,需要和本地缓存的topicRouteTable找到的TopicRouteData路由数据比较,如果数据改变了,会分别更新pub/sub的的路由信息.
topicRouteData2TopicPublishInfo(topic, topicRouteData);
for (int i = 0; i < qd.getWriteQueueNums(); i++) {
    MessageQueue mq = new MessageQueue(topic, qd.getBrokerName(), i);
    info.getMessageQueueList().add(mq);
}

```
 //选择一个MessageQueue
 MessageQueue mqSelected = this.selectOneMessageQueue(topicPublishInfo, lastBrokerName);
 当sendLatencyFaultEnable=true 延迟发送, 路由计算是:sendWhichQueue++%messageQueue.size()数量
 producer发送消息的时候会有故障检测,如果故障了 则会移除一段时间该broker.,移除的时间是下面计算的:
 updateFaultItem(final String brokerName, final long currentLatency, boolean isolation)里层方法
 computeNotAvailableDuration时间计算broker不参与消息发送队列负载,规避该broker.


 消息发送核心api
 - sendResult = this.sendKernelImpl(msg, mq, communicationMode, sendCallback, topicPublishInfo, timeout - costTime);
-> sendKernelImpl()这个很重要,里面封装把本地请求SendMessageRequestHeader(SendMessageRequestHeader implements CommandCustomHeader)
然后MQClientAPIImpl里面调用RemotingCommand.createRequestCommand()将SendMessageRequestHeader的请求头放入RemotingRequest,
后面会通过netty的channel.writeAndFlush(request) 进行请求发送.

上面看源码已经知道了producer发送消息到了broker,接下来我们看一下broker是如何处理的?
根据RequestCode.SEND_MESSAGE_V2进行匹配到rocketmq-broker服务的`AbstractSendMessageProcessor#parseRequestHeader`
SendMessageProcessor#asyncProcessRequest()调用了parseRequestHeader() ->
SendMessageRequestHeader requestHeader = parseRequestHeader(request);
->asyncSendMessage()

1. 首先检查消息是否合理
2. 消息重试是否达到最大重试次数,进入死信队列%DLQ%+消费组名
3. 调用putMessageResult = this.brokerController.getMessageStore().asyncPutMessage(msgInner); //存储消息


RocketMQ存储核心
三大组件:
CommitLog: 存储所有topic的消息文件
ConsumerQueue: CommitLog offset,文件大小size, tag hashcode. 主要用来给消费者消费的消息队列.消息到达CommitLog文件之后,将异步转发
到消息消费队列,给消息 消费者消费.
IndexFile: hash结构,key为hashcode,value为CommitLog offset. 消息索引文件

猜测IndexFile应该是索引文件,传递key的hashcode 直接找到CommitLog的消息文件

//DefaultMessageStore#asyncPutMessage()

putMessageResult = this.brokerController.getMessageStore().asyncPutMessage(msgInner);

从上面这行进行分析:

存储消息会调用`CompletableFuture<PutMessageResult> putResultFuture = this.commitLog.asyncPutMessage(msg);`
这里实现类为`CommitLog的asyncPutMessage(msg);`,然后处理消息的字段,处理CommitLog offset的逻辑,
`AppendMessageResult result = new AppendMessageResult(AppendMessageStatus.PUT_OK, wroteOffset, msgLen, msgId,
              msgInner.getStoreTimestamp(), queueOffset, CommitLog.this.defaultMessageStore.now() - beginTimeMills);`
会先将消息追加在内存中等待刷盘.

 //执行刷盘操作
handleDiskFlush(result, putMessageResult, msg);
//执行HA主从复制
 handleHA(result, putMessageResult, msg);



~~上面以前都是txt格式,后面会改为md格式放图以后好回顾~~

### MappedFileQueue
MappedFileQueue是MappedFile的文件管理容器,MappedFileQueue是对存储目录的封装.例如:CommitLog的存储目录为
${ROCKETMQ_HOME}/store/commitlog,该目录下会存在多个内存映射文件MappedFile.

![image-20201228201320180](note_images/image-20201228201320180.png)

### MappedFile

Java Memory-Mapped File所使用的内存分配在物理内存而不是JVM堆内存，且分配在OS内核。

每一个文件对应一个MappedFile.默认情况下大小位1g

```java
//通过上面我们知道消息发送是在asyncPutMessage()方法中
//那么MappedFile是如何创建并且维护的呢?
public CompletableFuture<PutMessageResult> asyncPutMessage(final MessageExtBrokerInner msg) {
  // ...省略
 MappedFile mappedFile = this.mappedFileQueue.getLastMappedFile();
  //...省略
  if (null == mappedFile || mappedFile.isFull()) {
                mappedFile = this.mappedFileQueue.getLastMappedFile(0); // Mark: NewFile may be cause noise
            }
}

```

```java
/**
     * 获取最后一个MappedFile,如果不存在或者已经满了,就创建
     * @param startOffset
     * @param needCreate
     * @return org.apache.rocketmq.store.MappedFile
     * @author chenqi
     * @date 2020/12/28 16:43
     */
    public MappedFile getLastMappedFile(final long startOffset, boolean needCreate) {
        //开始创建文件,-1时不创建
        long createOffset = -1;
        MappedFile mappedFileLast = getLastMappedFile();

        if (mappedFileLast == null) {
            createOffset = startOffset - (startOffset % this.mappedFileSize);
        }

        if (mappedFileLast != null && mappedFileLast.isFull()) {
            createOffset = mappedFileLast.getFileFromOffset() + this.mappedFileSize;
        }

        //创建文件
        if (createOffset != -1 && needCreate) {
            // 计算文件名。从此处我们可 以得知，MappedFile的文件命名规则：
            // 00000001000000000000 00100000000000000000 09000000000020000000二十位
            // fileName[n] = fileName[n - 1] + n * mappedFileSize fileName[0] = startOffset - (startOffset % this.mappedFileSize)
            String nextFilePath = this.storePath + File.separator + UtilAll.offset2FileName(createOffset);
            String nextNextFilePath = this.storePath + File.separator
                + UtilAll.offset2FileName(createOffset + this.mappedFileSize);

            MappedFile mappedFile = null;
            // 两种方式创建文件
            if (this.allocateMappedFileService != null) {
                //由allocateMappedFileService服务来维护MappedFile
               //查看资料https://my.oschina.net/u/4226611/blog/4353076
                mappedFile = this.allocateMappedFileService.putRequestAndReturnMappedFile(nextFilePath,
                    nextNextFilePath, this.mappedFileSize);
            } else {
                try {
                    //通过init()方法创建
                    mappedFile = new MappedFile(nextFilePath, this.mappedFileSize);
                } catch (IOException e) {
                    log.error("create mappedFile exception", e);
                }
            }
            
            //添加到mappedFile
            if (mappedFile != null) {
                if (this.mappedFiles.isEmpty()) {
                    mappedFile.setFirstCreateInQueue(true);
                }
                this.mappedFiles.add(mappedFile);
            }

            return mappedFile;
        }

        return mappedFileLast;
    }
```

```java
private void init(final String fileName, final int fileSize) throws IOException {
        this.fileName = fileName;
        this.fileSize = fileSize;
        this.file = new File(fileName);
        this.fileFromOffset = Long.parseLong(this.file.getName());
        boolean ok = false;

        ensureDirOK(this.file.getParent());

        try {
            this.fileChannel = new RandomAccessFile(this.file, "rw").getChannel();
            this.mappedByteBuffer = this.fileChannel.map(MapMode.READ_WRITE, 0, fileSize);
            TOTAL_MAPPED_VIRTUAL_MEMORY.addAndGet(fileSize);
            TOTAL_MAPPED_FILES.incrementAndGet();
            ok = true;
        } catch (FileNotFoundException e) {
            log.error("Failed to create file " + this.fileName, e);
            throw e;
        } catch (IOException e) {
            log.error("Failed to map file " + this.fileName, e);
            throw e;
        } finally {
            if (!ok && this.fileChannel != null) {
                this.fileChannel.close();
            }
        }
    }
```

现在已经知道MappedFile的文件的创建和内存映射,那么如何刷盘呢?

刷盘方式有三种：

| 线程服务              | 场景                           | 写消息性能 |
| --------------------- | ------------------------------ | ---------- |
| CommitRealTimeService | 异步刷盘 && 开启内存字节缓冲区 | 第一       |
| FlushRealTimeService  | 异步刷盘                       | 第二       |
| GroupCommitService    | 同步刷盘                       | 第三       |

` handleDiskFlush(result, putMessageResult, msg);`找到异步提交和刷盘`commitLogService.wakeup();`继续跟踪

```java
public void handleDiskFlush(AppendMessageResult result, PutMessageResult putMessageResult, MessageExt messageExt) {
	// 上面省略
    // Asynchronous flush
    else {
            //如果 堆外内存没有开启
            if (!this.defaultMessageStore.getMessageStoreConfig().isTransientStorePoolEnable()) {
                flushCommitLogService.wakeup();//进入
            } else {
              //唤醒的服务是CommitRealTimeServer
                commitLogService.wakeup();
            }
        }
}
```


### **FlushRealTimeService**

broker启动后，会启动许多服务线程，包括刷盘服务线程.,我们先搞清楚`FlushRealTimeService`的来龙去脉

```java
//初始化CommitLog的流程
//BrokerController->initialize()->new DefaultMessageStore(this.messageStoreConfig, this.brokerStatsManager, this.messageArrivingListener, this.brokerConfig)
//this.commitLog = new CommitLog(this);
public CommitLog(final DefaultMessageStore defaultMessageStore) {
        this.mappedFileQueue = new MappedFileQueue(defaultMessageStore.getMessageStoreConfig().getStorePathCommitLog(),
            defaultMessageStore.getMessageStoreConfig().getMappedFileSizeCommitLog(), defaultMessageStore.getAllocateMappedFileService());
        this.defaultMessageStore = defaultMessageStore;

        //同步调用
        if (FlushDiskType.SYNC_FLUSH == defaultMessageStore.getMessageStoreConfig().getFlushDiskType()) {
            this.flushCommitLogService = new GroupCommitService();
        } else {
            //异步调用
            this.flushCommitLogService = new FlushRealTimeService();
        }
        //构造commitLogService服务
        this.commitLogService = new CommitRealTimeService();

        this.appendMessageCallback = new DefaultAppendMessageCallback(defaultMessageStore.getMessageStoreConfig().getMaxMessageSize());
        batchEncoderThreadLocal = new ThreadLocal<MessageExtBatchEncoder>() {
            @Override
            protected MessageExtBatchEncoder initialValue() {
                return new MessageExtBatchEncoder(defaultMessageStore.getMessageStoreConfig().getMaxMessageSize());
            }
        };
        this.putMessageLock = defaultMessageStore.getMessageStoreConfig().isUseReentrantLockWhenPutMessage() ? new PutMessageReentrantLock() : new PutMessageSpinLock();

    }
```

```java
//启动服务
public void start() {
  			//调用FlushRealTimeService()服务的run()方法
        this.flushCommitLogService.start();

        if (defaultMessageStore.getMessageStoreConfig().isTransientStorePoolEnable()) {
          //调用CommitRealTimeService提交消息到内存并映射物理文件
            this.commitLogService.start();
        }
    }
```

CommitLog的父类是`DefaultMessageStore`,然后是在`BrokerController#start()`方法中调用`this.messageStore.start();`启动服务.

`CommitRealTimeService#start()->run()`

```java
class CommitRealTimeService extends FlushCommitLogService {

        private long lastCommitTimestamp = 0;

        @Override
        public String getServiceName() {
            return CommitRealTimeService.class.getSimpleName();
        }

        @Override
        public void run() {
            CommitLog.log.info(this.getServiceName() + " service started");
            while (!this.isStopped()) {
                int interval = CommitLog.this.defaultMessageStore.getMessageStoreConfig().getCommitIntervalCommitLog();

                int commitDataLeastPages = CommitLog.this.defaultMessageStore.getMessageStoreConfig().getCommitCommitLogLeastPages();

                int commitDataThoroughInterval =
                    CommitLog.this.defaultMessageStore.getMessageStoreConfig().getCommitCommitLogThoroughInterval();

                long begin = System.currentTimeMillis();
                if (begin >= (this.lastCommitTimestamp + commitDataThoroughInterval)) {
                    this.lastCommitTimestamp = begin;
                    commitDataLeastPages = 0;
                }

                try {
                    boolean result = CommitLog.this.mappedFileQueue.commit(commitDataLeastPages);
                    long end = System.currentTimeMillis();
                    //判断是否应该刷盘,根据上面commit的提交来判断的,如果为false就需要刷盘
                    if (!result) {
                        this.lastCommitTimestamp = end; // result = false means some data committed.
                        //now wake up flush thread.
                        //唤醒刷盘服务
                        flushCommitLogService.wakeup();
                    }

                    if (end - begin > 500) {
                        log.info("Commit data to file costs {} ms", end - begin);
                    }
                    this.waitForRunning(interval);
                } catch (Throwable e) {
                    CommitLog.log.error(this.getServiceName() + " service has exception. ", e);
                }
            }

            //重试提交
            boolean result = false;
            for (int i = 0; i < RETRY_TIMES_OVER && !result; i++) {
                result = CommitLog.this.mappedFileQueue.commit(0);
                CommitLog.log.info(this.getServiceName() + " service shutdown, retry " + (i + 1) + " times " + (result ? "OK" : "Not OK"));
            }
            CommitLog.log.info(this.getServiceName() + " service end");
        }
    }
```

`CommitLog.this.mappedFileQueue.commit(commitDataLeastPages);`分析`commit(commitDataLeastPages)`

```java
public boolean commit(final int commitLeastPages) {
        boolean result = true;
        //通过offset找到MappedFile
        MappedFile mappedFile = this.findMappedFileByOffset(this.committedWhere, this.committedWhere == 0);
        if (mappedFile != null) {
            //主要看commit
            int offset = mappedFile.commit(commitLeastPages);
            long where = mappedFile.getFileFromOffset() + offset;
            result = where == this.committedWhere;
            this.committedWhere = where;
        }

        return result;
    }

/**
     * 提交数据到磁盘
     * @param commitLeastPages 最小提交页数
     * @return int
     * @author chenqi
     * @date 2020/12/28 19:21
    */
    public int commit(final int commitLeastPages) {
        if (writeBuffer == null) {
            //no need to commit data to file channel, so just regard wrotePosition as committedPosition.
            return this.wrotePosition.get();
        }
        //判断是否能提交
        if (this.isAbleToCommit(commitLeastPages)) {
            if (this.hold()) {
                commit0(commitLeastPages); 
                this.release();
            } else {
                log.warn("in commit, hold failed, commit offset = " + this.committedPosition.get());
            }
        }

        // All dirty data has been committed to FileChannel.
        if (writeBuffer != null && this.transientStorePool != null && this.fileSize == this.committedPosition.get()) {
            this.transientStorePool.returnBuffer(writeBuffer);
            this.writeBuffer = null;
        }

        return this.committedPosition.get();
    }

    protected void commit0(final int commitLeastPages) {
        int writePos = this.wrotePosition.get();
        int lastCommittedPosition = this.committedPosition.get();

        //判断还有未提交的数据
        if (writePos - this.committedPosition.get() > 0) {
            try {
                //writeBuffer在appendMessagesInner()的逻辑中进行过赋值
                //创建writeBuffer的共享缓存区
                ByteBuffer byteBuffer = writeBuffer.slice();
                //设置position位置
                byteBuffer.position(lastCommittedPosition);
                //设置limit
                byteBuffer.limit(writePos);
                //把lastCommittedPosition写入发哦FileChannelPosition中
                this.fileChannel.position(lastCommittedPosition);
                //将数据提交到文件通道FileChannel,还是在内存之中
                this.fileChannel.write(byteBuffer);
                this.committedPosition.set(writePos);
            } catch (Throwable e) {
                log.error("Error occurred when commit data to FileChannel.", e);
            }
        }
    }
```

上面只看了`commit()`方法,将数据写入内存,那么是什么时候刷到磁盘的呢?

上文说到还有一个线程也会启动就是`FlushRealTimeService#run()`

```java
class FlushRealTimeService extends FlushCommitLogService {


        public void run() {
				//忽略上下文            
                    long begin = System.currentTimeMillis();
                    CommitLog.this.mappedFileQueue.flush(flushPhysicQueueLeastPages);
                    long storeTimestamp = CommitLog.this.mappedFileQueue.getStoreTimestamp();
        }
}
```

`CommitLog.this.mappedFileQueue.flush(flushPhysicQueueLeastPages);`->`MappedFileQueue#flush()`->

`MappedFile#flush()`

```java
/**
     * @return The current flushed position
     *
     */
    public int flush(final int flushLeastPages) {
        //校验是否能够flush
        if (this.isAbleToFlush(flushLeastPages)) {
            if (this.hold()) {
                int value = getReadPosition();

                try {
                    //调用mappedByteBuffer/fileChannel的force方法将内存的数据持久化到磁盘上
                    if (writeBuffer != null || this.fileChannel.position() != 0) {
                        this.fileChannel.force(false);
                    } else {
                        this.mappedByteBuffer.force();
                    }
                } catch (Throwable e) {
                    log.error("Error occurred when force data to disk.", e);
                }

                this.flushedPosition.set(value);
                this.release();
            } else {
                log.warn("in flush, hold failed, flush offset = " + this.flushedPosition.get());
                this.flushedPosition.set(getReadPosition());
            }
        }
        return this.getFlushedPosition();
    }
```

# 第四章

- CommitLog:存储所有的消息,文件的集合,每个文件默认1G大小.当第一个文件写满了,第二个文件会以初始偏移量命名.比如其实偏移量1080802673,第二个文件名为00000000001080802673,以此类推.CommitLog的内容在消费之后是不会被删除的,支持消息回溯,可以随时搜索.

- ConsumeQueue:CommitLog的索引文件,消息消费队列是RocketMQ专门为消息订阅的索引文件,提高消息检索速度.一个Topic可以有多个ConsumerQueue,每一个文件代表一个逻辑队列.

  从实际的物理存储来说,ConsumeQueue对应每个Topic和QueueId下面的文件,单个文件30W条数据组成,大小600万字节(约5.72M).当一个ConsumeQueue类型的文件写满了,则写下一个文件

  ![image-20201230153000026](note_images/image-20201230153000026.png)

- Index:引入Hash索引机制为消息建立索引

  单个IndexFile可以保存2000W个索引,文件大小约为400M

  索引的目的在于根据关键字快速定位消息.

  ![image-20201230145523437](note_images/image-20201230145523437.png)

- CheckPoint CommitLog,ConsumeQueue,IndexFile 文件的刷盘时间点,存储格式为

  ![image-20201230160419131](note_images/image-20201230160419131.png)

physicMsgTimestamp: CommitLog文件刷盘时间点

logicsMsgTimestamp: ConsumeQueue文件刷盘时间点

indexMsgTimestamp: IndexFile文件刷盘时间点

## 过期文件删除策略

由于RocketMQ被消费过的消息是不会被删除的,所以保证的消息的顺序写如.如果不清理文件的话,文件数量不断的增加,最终会导致磁盘可用空间越来越少.

所以主要要清理的文件为CommitLog,ConsumeQueue的过期文件.

删除策略:

- 通过定时任务,每天凌晨4点执行*默认超过72小时的文件为过期文件*进行删除
- 磁盘使用空间超过75%,开始删除过期文件
- 如果磁盘使用率超过85%,开始批量清理文件,不管是否过期,直到空间充足
- 如果磁盘使用率超过90%,拒绝消息写入

以下我们查看源码追踪:

`DefaultMessageStore#addScheduleTask()`

```java
private void addScheduleTask() {

        this.scheduledExecutorService.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
              //每10s定期清理文件
                DefaultMessageStore.this.cleanFilesPeriodically();
            }
        }, 1000 * 60, this.messageStoreConfig.getCleanResourceInterval(), TimeUnit.MILLISECONDS);

        this.scheduledExecutorService.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
              //CommitLog自我检查
                DefaultMessageStore.this.checkSelf();
            }
        }, 1, 10, TimeUnit.MINUTES);
  

        this.diskCheckScheduledExecutorService.scheduleAtFixedRate(new Runnable() {
            public void run() {
              //空间是否满了
                DefaultMessageStore.this.cleanCommitLogService.isSpaceFull();
            }
        }, 1000L, 10000L, TimeUnit.MILLISECONDS);
    }
```

```java
	/**
     * commitLog清除和ConsumeQueue清除服务
     * @return void
     * @author chenqi
     * @date 2021/1/4 10:09
     */
    private void cleanFilesPeriodically() {
        this.cleanCommitLogService.run();
        this.cleanConsumeQueueService.run();
    }

public void run() {
            try {
              //删除
                this.deleteExpiredFiles();

                this.redeleteHangedFile();
            } catch (Throwable e) {
                DefaultMessageStore.log.warn(this.getServiceName() + " service has exception. ", e);
            }
        }
```

```java
private void deleteExpiredFiles() {
            int deleteCount = 0;
            long fileReservedTime = DefaultMessageStore.this.getMessageStoreConfig().getFileReservedTime();
            int deletePhysicFilesInterval = DefaultMessageStore.this.getMessageStoreConfig().getDeleteCommitLogFilesInterval();
            int destroyMapedFileIntervalForcibly = DefaultMessageStore.this.getMessageStoreConfig().getDestroyMapedFileIntervalForcibly();

            //通过deleteWhen设置一天的固定执行一次删除,默认为凌晨4点
            boolean timeup = this.isTimeToDelete();
            //磁盘空间是否充足,如果磁盘空间不充足 返回true 立即执行删除
            boolean spacefull = this.isSpaceToDelete();
            //预留 手工触发删除策略
            boolean manualDelete = this.manualDeleteFileSeveralTimes > 0;

            if (timeup || spacefull || manualDelete) {

                if (manualDelete)
                    this.manualDeleteFileSeveralTimes--;

                boolean cleanAtOnce = DefaultMessageStore.this.getMessageStoreConfig().isCleanFileForciblyEnable() && this.cleanImmediately;

                log.info("begin to delete before {} hours file. timeup: {} spacefull: {} manualDeleteFileSeveralTimes: {} cleanAtOnce: {}",
                    fileReservedTime,
                    timeup,
                    spacefull,
                    manualDeleteFileSeveralTimes,
                    cleanAtOnce);

                //过期时间
                fileReservedTime *= 60 * 60 * 1000;
 //入参分析: 删除文件的过期时间,两次删除文件的间隔时间,强制销毁映射文件,是否立刻执行
                deleteCount = DefaultMessageStore.this.commitLog.deleteExpiredFile(fileReservedTime, deletePhysicFilesInterval,
                    destroyMapedFileIntervalForcibly, cleanAtOnce);
                if (deleteCount > 0) {
                } else if (spacefull) {
                    log.warn("disk space will be full soon, but delete file failed.");
                }
            }
        }
```

`磁盘使用率`

```java
private boolean isSpaceToDelete() {
            //获得磁盘最大使用率
            double ratio = DefaultMessageStore.this.getMessageStoreConfig().getDiskMaxUsedSpaceRatio() / 100.0;

            cleanImmediately = false;

            {
                String storePathPhysic = DefaultMessageStore.this.getMessageStoreConfig().getStorePathCommitLog();
                //CommitLog目录所在的磁盘分区使用率
                double physicRatio = UtilAll.getDiskPartitionSpaceUsedPercent(storePathPhysic);
                if (physicRatio > diskSpaceWarningLevelRatio) {
                    //如果当前磁盘使用率>0.9 磁盘拒绝写入消息
                    boolean diskok = DefaultMessageStore.this.runningFlags.getAndMakeDiskFull();
                    if (diskok) {
                        DefaultMessageStore.log.error("physic disk maybe full soon " + physicRatio + ", so mark disk full");
                    }

                    cleanImmediately = true;
                } else if (physicRatio > diskSpaceCleanForciblyRatio) {
                    //如果当前磁盘使用率>0.85 立即执行删除
                    cleanImmediately = true;
                } else {
                    //磁盘使用率<0.85 为正常 返回true
                    boolean diskok = DefaultMessageStore.this.runningFlags.getAndMakeDiskOK();
                    if (!diskok) {
                        DefaultMessageStore.log.info("physic disk space OK " + physicRatio + ", so mark disk ok");
                    }
                }

                if (physicRatio < 0 || physicRatio > ratio) {
                    DefaultMessageStore.log.info("physic disk maybe full soon, so reclaim space, " + physicRatio);
                    return true;
                }
            }

            {
                //获得ConsumeQueue逻辑路径,与上面逻辑一致
                String storePathLogics = StorePathConfigHelper
                    .getStorePathConsumeQueue(DefaultMessageStore.this.getMessageStoreConfig().getStorePathRootDir());
                double logicsRatio = UtilAll.getDiskPartitionSpaceUsedPercent(storePathLogics);
                if (logicsRatio > diskSpaceWarningLevelRatio) {
                    boolean diskok = DefaultMessageStore.this.runningFlags.getAndMakeDiskFull();
                    if (diskok) {
                        DefaultMessageStore.log.error("logics disk maybe full soon " + logicsRatio + ", so mark disk full");
                    }

                    cleanImmediately = true;
                } else if (logicsRatio > diskSpaceCleanForciblyRatio) {
                    cleanImmediately = true;
                } else {
                    boolean diskok = DefaultMessageStore.this.runningFlags.getAndMakeDiskOK();
                    if (!diskok) {
                        DefaultMessageStore.log.info("logics disk space OK " + logicsRatio + ", so mark disk ok");
                    }
                }

                if (logicsRatio < 0 || logicsRatio > ratio) {
                    DefaultMessageStore.log.info("logics disk maybe full soon, so reclaim space, " + logicsRatio);
                    return true;
                }
            }

            return false;
        }
```

# 第五章

- 消息队列的负载与重新分布

- 消息消费模式(集群/广播)

- 消息拉取方式(push(实际上也是pull)/pull)

- 消息进度反馈

- 消息过滤

- 顺序消息

  

  

  







# 第五章














producer消息的类型:
1. 普通消息(批量)
2. 顺序消息
3. 事务消息
4. 延迟消息

broker:
1. 物理存储文件分析
2. 文件清理策略

consumer:
1. 消息重试与死信队列
2. 消费者负载与rebalance