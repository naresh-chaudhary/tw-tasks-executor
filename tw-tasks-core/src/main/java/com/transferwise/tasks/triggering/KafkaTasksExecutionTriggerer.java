package com.transferwise.tasks.triggering;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Preconditions;
import com.transferwise.common.baseutils.ExceptionUtils;
import com.transferwise.common.baseutils.concurrency.LockUtils;
import com.transferwise.common.gracefulshutdown.GracefulShutdownStrategy;
import com.transferwise.tasks.ITasksService;
import com.transferwise.tasks.TasksProperties;
import com.transferwise.tasks.buckets.BucketProperties;
import com.transferwise.tasks.buckets.IBucketsManager;
import com.transferwise.tasks.dao.ITaskDao;
import com.transferwise.tasks.domain.BaseTask;
import com.transferwise.tasks.domain.TaskStatus;
import com.transferwise.tasks.entrypoints.IMdcService;
import com.transferwise.tasks.handler.interfaces.ITaskHandler;
import com.transferwise.tasks.handler.interfaces.ITaskHandlerRegistry;
import com.transferwise.tasks.helpers.ICoreMetricsTemplate;
import com.transferwise.tasks.helpers.IErrorLoggingThrottler;
import com.transferwise.tasks.helpers.executors.IExecutorsHelper;
import com.transferwise.tasks.helpers.kafka.ITopicPartitionsManager;
import com.transferwise.tasks.processing.GlobalProcessingState;
import com.transferwise.tasks.processing.ITasksProcessingService;
import com.transferwise.tasks.utils.JsonUtils;
import com.transferwise.tasks.utils.LogUtils;
import com.transferwise.tasks.utils.WaitUtils;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;
import javax.annotation.PostConstruct;
import lombok.Data;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.kafka.clients.consumer.CommitFailedException;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.CooperativeStickyAssignor;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.consumer.RangeAssignor;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.ReassignmentInProgressException;
import org.apache.kafka.common.errors.RebalanceInProgressException;
import org.apache.kafka.common.errors.RetriableException;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Slf4j
public class KafkaTasksExecutionTriggerer implements ITasksExecutionTriggerer, GracefulShutdownStrategy {

  @Autowired
  private ITasksProcessingService tasksProcessingService;
  @Autowired
  private ITaskDao taskDao;
  @Autowired
  private TasksProperties tasksProperties;
  @Autowired
  private ITaskHandlerRegistry taskHandlerRegistry;
  @Autowired
  private IExecutorsHelper executorsHelper;
  @Autowired
  private ITopicPartitionsManager topicPartitionsManager;
  @Autowired
  private GlobalProcessingState globalProcessingState;
  @Autowired
  private IBucketsManager bucketsManager;
  @Autowired
  private IErrorLoggingThrottler errorLoggingThrottler;
  @Autowired
  private IMdcService mdcService;
  @Autowired
  private ICoreMetricsTemplate coreMetricsTemplate;

  private ObjectMapper objectMapper;
  private KafkaProducer<String, String> kafkaProducer;
  private ExecutorService executorService;
  private volatile boolean shuttingDown;
  private String triggerTopic;
  private final Map<String, ConsumerBucket> consumerBuckets = new ConcurrentHashMap<>();
  private final Map<String, ProcessingBucket> processingBuckets = new ConcurrentHashMap<>();
  private final AtomicInteger pollingBucketsCount = new AtomicInteger();
  private final Lock lifecycleLock = new ReentrantLock();

  @PostConstruct
  public void init() {
    executorService = executorsHelper.newCachedExecutor("ktet");
    triggerTopic = "twTasks." + tasksProperties.getGroupId() + ".executeTask";

    tasksProcessingService.addTaskTriggeringFinishedListener(taskTriggering -> {
      if (taskTriggering.isSameProcessTrigger()) {
        return;
      }

      String bucketId = taskTriggering.getBucketId();
      ConsumerBucket consumerBucket = consumerBuckets.get(bucketId);
      TopicPartition topicPartition = taskTriggering.getTopicPartition();
      long offset = taskTriggering.getOffset();

      releaseCompletedOffset(consumerBucket, topicPartition, offset);
    });

    coreMetricsTemplate.registerPollingBucketsCount(pollingBucketsCount);

    objectMapper = new ObjectMapper();
    objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    kafkaProducer = createKafkaProducer();
  }

  @Override
  public void trigger(BaseTask task) {
    if (tasksProperties.isAssertionsEnabled()) {
      Preconditions.checkState(!TransactionSynchronizationManager.isActualTransactionActive());
    }

    ITaskHandler taskHandler = taskHandlerRegistry.getTaskHandler(task);
    if (taskHandler == null) {
      log.error("Marking task {} as ERROR, because no task handler was found for type '" + task.getType() + "'.",
          LogUtils.asParameter(task.getVersionId()));
      coreMetricsTemplate.registerTaskMarkedAsError(null, task.getType());
      if (!taskDao.setStatus(task.getId(), TaskStatus.ERROR, task.getVersion())) {
        coreMetricsTemplate.registerFailedStatusChange(task.getType(), TaskStatus.UNKNOWN.name(), TaskStatus.ERROR);
        log.error("Marking task {} as ERROR failed, version may have changed.", LogUtils.asParameter(task.getVersionId()), new Throwable());
      }
      return;
    }

    String processingBucketId = taskHandler.getProcessingPolicy(task).getProcessingBucket(task);

    if (!bucketsManager.isConfiguredBucket(processingBucketId)) {
      log.error("Marking task {} as ERROR, because task handler has unknown bucket '{}'.", LogUtils.asParameter(task.getVersionId()),
          processingBucketId);
      coreMetricsTemplate.registerTaskMarkedAsError(processingBucketId, task.getType());
      if (!taskDao.setStatus(task.getId(), TaskStatus.ERROR, task.getVersion())) {
        coreMetricsTemplate.registerFailedStatusChange(task.getType(), TaskStatus.UNKNOWN.name(), TaskStatus.ERROR);
        log.error("Marking task {} as ERROR failed, version may have changed.", LogUtils.asParameter(task.getVersionId()), new Throwable());
      }
      return;
    }

    if (BooleanUtils.isTrue(bucketsManager.getBucketProperties(processingBucketId).getTriggerInSameProcess())) {
      TaskTriggering taskTriggering = new TaskTriggering().setTask(task).setBucketId(processingBucketId);
      ITasksProcessingService.AddTaskForProcessingResponse addTaskForProcessingResponse = tasksProcessingService.addTaskForProcessing(taskTriggering);

      if (addTaskForProcessingResponse.getResult() == ITasksProcessingService.AddTaskForProcessingResponse.ResultCode.OK) {
        return;
      }
    }

    String taskSt = JsonUtils.toJson(objectMapper, task);

    // TODO: Future improvement: try to also trigger in the same node, if there is room or more specifically if it is idle (for min latency)
    // TODO: Maybe needs another concurrency control for that. E.g. only trigger in node, when conc < 5, even max conc is 10.

    // We need to give a non-null random key, so triggers will be evenly spread around partitions.
    // With <null> key, the kafka client will start doing some kind of batch partitioning.

    String key = String.valueOf((char) ThreadLocalRandom.current().nextInt(0xFFFF));
    kafkaProducer
        .send(new ProducerRecord<>(getTopic(processingBucketId), key, taskSt),
            (metadata, exception) -> {
              if (exception != null) {
                if (log.isDebugEnabled() || errorLoggingThrottler.canLogError()) {
                  mdcService.with(() -> {
                    mdcService.put(task);
                    log.error("Task {} triggering failed through Kafka.", LogUtils.asParameter(task.getVersionId()), exception);
                  });
                }
              } else {
                if (log.isDebugEnabled()) {
                  mdcService.with(() -> {
                    mdcService.put(task);
                    log.debug("Task '{}' triggering acknowledged by Kafka.", task.getVersionId());
                  });
                }
              }
            });
  }

  public ConsumerBucket getConsumerBucket(String bucketId) {
    return ExceptionUtils.doUnchecked(() -> {
      ConsumerBucket consumerBucket = consumerBuckets.get(bucketId);
      if (consumerBucket == null) {
        consumerBuckets.put(bucketId, consumerBucket = new ConsumerBucket().setBucketId(bucketId));

        coreMetricsTemplate.registerKafkaTasksExecutionTriggererOffsetsToBeCommitedCount(bucketId, consumerBucket::getOffsetsToBeCommitedCount);
        coreMetricsTemplate.registerKafkaTasksExecutionTriggererOffsetsCompletedCount(bucketId, consumerBucket::getOffsetsCompletedCount);
        coreMetricsTemplate
            .registerKafkaTasksExecutionTriggererUnprocessedFetchedRecordsCount(bucketId, consumerBucket::getUnprocessedFetchedRecordsCount);
        coreMetricsTemplate.registerKafkaTasksExecutionTriggererOffsetsCount(bucketId, consumerBucket::getOffsetsCount);
      }

      BucketProperties bucketProperties = bucketsManager.getBucketProperties(bucketId);

      if (!consumerBucket.isTopicConfigured()) {
        String topic = getTopic(bucketId);
        topicPartitionsManager.setPartitionsCount(topic, bucketProperties.getTriggeringTopicPartitionsCount());
        consumerBucket.setTopicConfigured(true);
      }

      if (consumerBucket.getKafkaConsumer() == null) {
        consumerBucket.setKafkaConsumer(createKafkaConsumer(bucketId, bucketProperties));
      }
      return consumerBucket;
    });
  }

  public void poll(String bucketId) {
    log.info("Started to listen tasks triggers in bucket '" + bucketId + "'.");
    try {
      pollingBucketsCount.incrementAndGet();
      ConsumerBucket consumerBucket = getConsumerBucket(bucketId);
      GlobalProcessingState.Bucket bucket = globalProcessingState.getBuckets().get(bucketId);

      while (!shuttingDown && (getProcessingBucket(bucketId).getState() == ITasksService.TasksProcessingState.STARTED)) {
        ConsumerRecords<String, String> consumerRecords;
        try {
          consumerRecords = consumerBucket.getKafkaConsumer().poll(tasksProperties.getGenericMediumDelay());
        } catch (WakeupException ignored) {
          // Wake up was called, most likely to shut down, nothing erronous here.
          continue;
        }

        commitOffsets(consumerBucket, false);

        consumerBucket.setUnprocessedFetchedRecordsCount(consumerRecords.count());
        for (ConsumerRecord<String, String> consumerRecord : consumerRecords) {
          TopicPartition topicPartition = new TopicPartition(consumerRecord.topic(), consumerRecord.partition());

          long offset = consumerRecord.offset();
          registerPolledOffset(consumerBucket, topicPartition, offset);

          log.debug("Received Kafka message from topic '{}' partition {} offset {}.", consumerRecord.topic(), consumerRecord.partition(), offset);

          BaseTask task = JsonUtils.fromJson(objectMapper, consumerRecord.value(), BaseTask.class);
          mdcService.with(() -> {
            mdcService.put(task);

            TaskTriggering taskTriggering = new TaskTriggering().setTask(task).setBucketId(bucketId).setOffset(offset)
                .setTopicPartition(topicPartition);

            coreMetricsTemplate.registerKafkaTasksExecutionTriggererTriggersReceive(bucketId);

            while (!shuttingDown) {
              long processingStateVersion = bucket.getVersion().get();

              log.debug("Adding task '{}' for processing.", task.getVersionId());
              ITasksProcessingService.AddTaskForProcessingResponse addTaskForProcessingResponse = tasksProcessingService
                  .addTaskForProcessing(taskTriggering);

              /*
                  TODO: This can theoretically cause a big interval between KafkaConsumer polls and we may loose our partitions.
                  The whole algorithm in this method needs to be overlooked, because of this. Probably some kind of KafkaConsumer.cancel/pause
                  approach needs to be used.
                  Or we go over ResultCode.FULL and wait before polling, instead (Feels much better).
               */
              if (addTaskForProcessingResponse.getResult() == ITasksProcessingService.AddTaskForProcessingResponse.ResultCode.FULL) {
                Lock versionLock = bucket.getVersionLock();
                versionLock.lock();
                try {
                  // TODO: consumerBucket.getKafkaConsumer().pause(...)
                  while (bucket.getVersion().get() == processingStateVersion && !shuttingDown) {
                    try {
                      bucket.getVersionCondition().await(tasksProperties.getGenericMediumDelay().toMillis(), TimeUnit.MILLISECONDS);
                    } catch (InterruptedException e) {
                      log.error(e.getMessage(), e);
                    }
                  }
                  // TODO: consumerBucket.getKafkaConsumer().resume(...)
                } finally {
                  versionLock.unlock();
                }
              } else {
                break;
              }
            }
            consumerBucket.decrementUnprocessedFetchedRecordsCount();
          });
        }
      }
    } finally {
      pollingBucketsCount.decrementAndGet();
      // Notice, that now commits will not work anymore. However that is ok, we prefer to unsubscribe,
      // so other nodes can take the partitions to themselves asap.
      closeKafkaConsumer(consumerBuckets.get(bucketId));
    }
  }

  void registerPolledOffset(ConsumerBucket consumerBucket, TopicPartition topicPartition, long offset) {
    ConsumerTopicPartition consumerTopicPartition = consumerBucket.getConsumerTopicPartitions().get(topicPartition);
    if (consumerTopicPartition == null) {
      consumerBucket.getConsumerTopicPartitions().put(topicPartition, consumerTopicPartition = new ConsumerTopicPartition());
    }

    consumerBucket.getOffsetsStorageLock().lock();
    try {
      consumerTopicPartition.getOffsetsCompleted().remove(offset);
      consumerTopicPartition.getOffsets().add(offset);
    } finally {
      consumerBucket.getOffsetsStorageLock().unlock();
    }
  }

  void releaseCompletedOffset(ConsumerBucket consumerBucket, TopicPartition topicPartition, long offset) {
    consumerBucket.getOffsetsStorageLock().lock();
    // TODO: Lots of stuff inside this lock...
    try {
      ConsumerTopicPartition consumerTopicPartition = consumerBucket.getConsumerTopicPartitions().get(topicPartition);

      TreeSet<Long> offsets = consumerTopicPartition.getOffsets();
      if (!offsets.contains(offset)) {
        // Theoretically possible, when we reconnect to Kafka and we had registered one offset multiple times
        // (in this case there is only single record in TreeSet for it).

        coreMetricsTemplate.registerKafkaTasksExecutionTriggererAlreadyCommitedOffset(consumerBucket.getBucketId());
        log.debug("Offset {} has already been commited.", offset);
        return;
      }
      consumerTopicPartition.getOffsetsCompleted().put(offset, Boolean.TRUE);

      boolean isFirst = offsets.first() == offset;
      if (isFirst) {
        while (!offsets.isEmpty()) {
          long firstOffset = offsets.first();
          if (consumerTopicPartition.isDone(firstOffset)) {
            // From Kafka Docs
            // Note: The committed offset should always be the offset of the next message that your application will read.
            consumerBucket.getOffsetsToBeCommited().put(topicPartition, new OffsetAndMetadata(firstOffset + 1));
            offsets.pollFirst();
            consumerTopicPartition.getOffsetsCompleted().remove(firstOffset);
          } else {
            break;
          }
        }
      }
    } finally {
      consumerBucket.getOffsetsStorageLock().unlock();
    }
  }

  private void commitOffsets(ConsumerBucket consumerBucket, boolean finalize) {
    // No need to commit after every fast poll.
    if (!finalize && (System.currentTimeMillis() - consumerBucket.getLastCommitTime() < tasksProperties.getGenericMediumDelay().toMillis())) {
      return;
    }

    String bucketId = consumerBucket.getBucketId();
    consumerBucket.getOffsetsStorageLock().lock();
    try {
      if (consumerBucket.getOffsetsToBeCommited().isEmpty()) {
        return;
      }
      try {
        if (log.isDebugEnabled()) {
          log.debug("Commiting bucket '" + bucketId + "' offsets to Kafka: " + consumerBucket.getOffsetsToBeCommited().entrySet().stream()
              .map(e -> e.getKey() + ":" + e.getValue().offset()).collect(Collectors.joining(", ")));
        }
        coreMetricsTemplate.registerKafkaTasksExecutionTriggererCommit(bucketId);

        if (finalize) {
          consumerBucket.getKafkaConsumer().commitSync(consumerBucket.getOffsetsToBeCommited());
        } else {
          consumerBucket.getKafkaConsumer().commitAsync(consumerBucket.getOffsetsToBeCommited(), (map, e) -> {
            if (e != null) {
              registerCommitException(bucketId, e);
            }
          });
        }
      } catch (Throwable t) {
        registerCommitException(bucketId, t);
      }
      // Notice, we even clear commitable offsets on error.
      consumerBucket.getOffsetsToBeCommited().clear();
    } finally {
      consumerBucket.getOffsetsStorageLock().unlock();
    }
    consumerBucket.setLastCommitTime(System.currentTimeMillis());
  }

  protected void registerCommitException(String bucketId, Throwable t) {
    if (t instanceof RebalanceInProgressException || t instanceof ReassignmentInProgressException || t instanceof CommitFailedException
        || t instanceof RetriableException) { // Topic got rebalanced on shutdown.
      coreMetricsTemplate.registerKafkaTasksExecutionTriggererFailedCommit(bucketId);
      log.debug("Committing Kafka offset failed for bucket '" + bucketId + "'.", t);
      return;
    }

    if (errorLoggingThrottler.canLogError()) {
      log.error("Committing Kafka offset failed for bucket '" + bucketId + "'.", t);
    }
  }

  private KafkaProducer<String, String> createKafkaProducer() {
    Map<String, Object> configs = new HashMap<>();
    configs.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, tasksProperties.getTriggering().getKafka().getBootstrapServers());

    configs.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    configs.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);

    configs.put(ProducerConfig.ACKS_CONFIG, "all");
    configs.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
    configs.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, "5000");
    configs.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
    configs.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, "5000");
    configs.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, "10000");
    configs.put(ProducerConfig.LINGER_MS_CONFIG, "5");
    configs.put(ProducerConfig.CLIENT_ID_CONFIG, tasksProperties.getGroupId() + ".tw-tasks-triggerer");
    configs.put(ProducerConfig.RECONNECT_BACKOFF_MAX_MS_CONFIG, "5000");
    configs.put(ProducerConfig.RECONNECT_BACKOFF_MS_CONFIG, "100");

    configs.putAll(tasksProperties.getTriggering().getKafka().getProperties());

    KafkaProducer<String, String> kafkaProducer = new KafkaProducer<>(configs);
    coreMetricsTemplate.registerKafkaProducer(kafkaProducer);

    return kafkaProducer;
  }

  private KafkaConsumer<String, String> createKafkaConsumer(String bucketId, BucketProperties bucketProperties) {
    String groupId = tasksProperties.getGroupId();

    if (bucketProperties.getTriggerSameTaskInAllNodes()) {
      log.info("Using same task triggering on all nodes strategy for bucket '{}'.", bucketId);
      groupId += "." + tasksProperties.getClientId();
    }

    Map<String, Object> configs = new HashMap<>();
    configs.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, tasksProperties.getTriggering().getKafka().getBootstrapServers());
    configs.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, bucketProperties.getTriggersFetchSize());
    configs.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
    configs.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
    configs.put(ConsumerConfig.CLIENT_ID_CONFIG, tasksProperties.getClientId() + ".tw-tasks.bucket." + bucketId);
    configs.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    configs.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    configs.put(ConsumerConfig.RECONNECT_BACKOFF_MAX_MS_CONFIG, "5000");
    configs.put(ConsumerConfig.RECONNECT_BACKOFF_MS_CONFIG, "100");
    configs.put(ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG, CooperativeStickyAssignor.class.getName() + "," + RangeAssignor.class.getName());

    configs.putAll(tasksProperties.getTriggering().getKafka().getProperties());

    if (bucketProperties.getAutoResetOffsetToDuration() != null) {
      configs.remove(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG);
    } else {
      configs.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, tasksProperties.getAutoResetOffsetTo());
    }

    KafkaConsumer<String, String> kafkaConsumer = new KafkaConsumer<>(configs);
    coreMetricsTemplate.registerKafkaConsumer(kafkaConsumer);

    List<String> topics = getTopics(bucketId);
    log.info("Subscribing to Kafka topics '{}'", topics);
    if (bucketProperties.getAutoResetOffsetToDuration() == null) {
      kafkaConsumer.subscribe(topics);
    } else {
      kafkaConsumer.subscribe(topics, new SeekToDurationOnRebalanceListener(kafkaConsumer, bucketProperties.getAutoResetOffsetToDuration()));
    }
    return kafkaConsumer;
  }

  private void closeKafkaConsumer(ConsumerBucket consumerBucket) {
    if (consumerBucket == null) {
      return;
    }
    KafkaConsumer<String, String> kafkaConsumer = consumerBucket.getKafkaConsumer();
    if (kafkaConsumer == null) {
      return;
    }

    commitOffsets(consumerBucket, true);

    try {
      kafkaConsumer.unsubscribe();
    } catch (Throwable t) {
      log.error(t.getMessage(), t);
    }

    try {
      kafkaConsumer.close();
    } catch (Throwable t) {
      log.error(t.getMessage(), t);
    }
    log.info("Closed Kafka consumer for bucket '" + consumerBucket.getBucketId() + "'.");

    consumerBucket.setKafkaConsumer(null);
  }

  private String getTopic(String bucketId) {
    String topic = triggerTopic;
    if (StringUtils.isNotEmpty(bucketId)) {
      topic += "." + bucketId;
    }
    if (StringUtils.isNotEmpty(tasksProperties.getKafkaTopicsNamespace())) {
      topic = tasksProperties.getKafkaTopicsNamespace() + "." + topic;
    }
    return topic;
  }

  private List<String> getTopics(String bucketId) {
    List<String> result = new ArrayList<>();
    String topic = getTopic(bucketId);
    result.add(topic);
    for (String dataCenterPrefix : StringUtils.split(tasksProperties.getKafkaDataCenterPrefixes(), ",")) {
      result.add(dataCenterPrefix + topic);
    }
    return result;
  }

  @Override
  public void applicationStarted() {
    LockUtils.withLock(lifecycleLock, () -> {
      for (String bucketId : bucketsManager.getBucketIds()) {
        if (Boolean.TRUE.equals(bucketsManager.getBucketProperties(bucketId).getAutoStartProcessing())) {
          if (getProcessingBucket(bucketId).getState() == ITasksService.TasksProcessingState.STOPPED) {
            startBucketProcessing(bucketId);
          }
        }
      }
    });
  }

  private void startBucketProcessing(String bucketId) {
    getProcessingBucket(bucketId).setState(ITasksService.TasksProcessingState.STARTED);
    executorService.submit(() -> {
      while (!shuttingDown && (getProcessingBucket(bucketId).getState() == ITasksService.TasksProcessingState.STARTED)) {
        try {
          poll(bucketId);
        } catch (Throwable t) {
          log.error(t.getMessage(), t);
          try {
            closeKafkaConsumer(consumerBuckets.get(bucketId));
          } catch (Throwable t1) {
            log.error(t1.getMessage(), t1);
          }
          WaitUtils.sleepQuietly(tasksProperties.getGenericMediumDelay());
        }
      }
      LockUtils.withLock(lifecycleLock, () -> {
        ProcessingBucket processingBucket = getProcessingBucket(bucketId);
        processingBucket.setState(ITasksService.TasksProcessingState.STOPPED);
        if (processingBucket.getStopFuture() != null) {
          processingBucket.getStopFuture().complete(null);
        }
        processingBucket.setStopFuture(null);
        log.info("Stopped triggers processing for bucket '" + bucketId + "'.");
      });
    });
    log.info("Started triggers processing for bucket '" + bucketId + "'.");
  }

  @Override
  public void startTasksProcessing(String bucketId) {
    String safeBucketId = bucketId == null ? IBucketsManager.DEFAULT_ID : bucketId;
    LockUtils.withLock(lifecycleLock, () -> {
      if (getProcessingBucket(safeBucketId).getState() == ITasksService.TasksProcessingState.STOPPED) {
        startBucketProcessing(safeBucketId);
      }
    });
  }

  @Override
  public Future<Void> stopTasksProcessing(String bucketId) {
    return LockUtils.withLock(lifecycleLock, () -> {
      ProcessingBucket bucket = getProcessingBucket(bucketId);
      CompletableFuture<Void> future = new CompletableFuture<>();
      if (bucket.getState() != ITasksService.TasksProcessingState.STARTED) {
        future.complete(null);
        return future;
      }
      bucket.setStopFuture(future);
      bucket.setState(ITasksService.TasksProcessingState.STOP_IN_PROGRESS);

      ConsumerBucket consumerBucket = consumerBuckets.get(bucketId);
      if (consumerBucket != null) {
        KafkaConsumer<String, String> kafkaConsumer = consumerBucket.getKafkaConsumer();
        if (kafkaConsumer != null) {
          kafkaConsumer.wakeup();
        }
      }
      return future;
    });
  }

  @Override
  public ITasksService.TasksProcessingState getTasksProcessingState(String bucketId) {
    return getProcessingBucket(bucketId).getState();
  }

  @Override
  public void prepareForShutdown() {
    shuttingDown = true;
    executorService.shutdown();
  }

  @Override
  public boolean canShutdown() {
    return executorService.isTerminated();
  }

  @Data
  @Accessors(chain = true)
  public static class ConsumerBucket {

    private String bucketId;
    private long lastCommitTime = System.currentTimeMillis();
    private KafkaConsumer<String, String> kafkaConsumer;
    private Map<TopicPartition, ConsumerTopicPartition> consumerTopicPartitions = new ConcurrentHashMap<>();
    private Lock offsetsStorageLock = new ReentrantLock();
    private Map<TopicPartition, OffsetAndMetadata> offsetsToBeCommited = new ConcurrentHashMap<>();
    private int unprocessedFetchedRecordsCount;
    private boolean topicConfigured;

    public int getOffsetsToBeCommitedCount() {
      return offsetsToBeCommited.size();
    }

    public int getUnprocessedFetchedRecordsCount() {
      return unprocessedFetchedRecordsCount;
    }

    public int getOffsetsCount() {
      return consumerTopicPartitions.values().stream().mapToInt(cp -> cp.getOffsets().size()).sum();
    }

    public int getOffsetsCompletedCount() {
      return consumerTopicPartitions.values().stream().mapToInt(cp -> cp.getOffsetsCompleted().size()).sum();
    }

    public void decrementUnprocessedFetchedRecordsCount() {
      unprocessedFetchedRecordsCount--;
    }
  }

  @Data
  @Accessors(chain = true)
  public static class ConsumerTopicPartition {

    private TreeSet<Long> offsets = new TreeSet<>();
    private Map<Long, Boolean> offsetsCompleted = new HashMap<>();

    public boolean isDone(Long offset) {
      Boolean done = offsetsCompleted.get(offset);
      return done != null && done;
    }
  }

  private ProcessingBucket getProcessingBucket(String bucketId) {
    return processingBuckets.computeIfAbsent(bucketId == null ? IBucketsManager.DEFAULT_ID : bucketId, (k) -> new ProcessingBucket());
  }

  @Data
  @Accessors(chain = true)
  private static class ProcessingBucket {

    private ITasksService.TasksProcessingState state = ITasksService.TasksProcessingState.STOPPED;
    private CompletableFuture<Void> stopFuture;
  }
}
