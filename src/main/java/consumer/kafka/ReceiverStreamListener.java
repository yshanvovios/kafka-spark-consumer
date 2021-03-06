package consumer.kafka;

import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.spark.streaming.scheduler.StreamingListener;
import org.apache.spark.streaming.scheduler.StreamingListenerBatchCompleted;
import org.apache.spark.streaming.scheduler.StreamingListenerBatchStarted;
import org.apache.spark.streaming.scheduler.StreamingListenerBatchSubmitted;
import org.apache.spark.streaming.scheduler.StreamingListenerOutputOperationCompleted;
import org.apache.spark.streaming.scheduler.StreamingListenerOutputOperationStarted;
import org.apache.spark.streaming.scheduler.StreamingListenerReceiverError;
import org.apache.spark.streaming.scheduler.StreamingListenerReceiverStarted;
import org.apache.spark.streaming.scheduler.StreamingListenerReceiverStopped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import scala.Option;

public class ReceiverStreamListener implements StreamingListener {
    private static Logger LOG = LoggerFactory.getLogger(ReceiverStreamListener.class);

    private int batchSubmittedCount = 0;
    private int batchCompletedCount = 0;
    private KafkaConfig config;
    private int MAX_RATE;
    private int THROTTLE_QUEUE;
    private int MIN_RATE;
    private int fillFreqMs;
    private AtomicBoolean terminateOnFailure;
    private long batchDuration;
    private int partitionCount;
    private PIDController controller;

    public ReceiverStreamListener(KafkaConfig config,
                                  long batchDuration, int partitionCount, AtomicBoolean terminateOnFailure) {
      this.config = config;
      MAX_RATE = config._maxFetchSizeBytes;
      THROTTLE_QUEUE = config._batchQueueToThrottle;
      MIN_RATE = config._minFetchSizeBytes;
      fillFreqMs = config._fillFreqMs;
      this.terminateOnFailure = terminateOnFailure;
      this.batchDuration = batchDuration;
      this.partitionCount = partitionCount;

      controller = new PIDController(
              config._proportional,
              config._integral,
              config._derivative);
    }

    @Override
    public void onReceiverStopped(StreamingListenerReceiverStopped receiverStopped) {
      LOG.error("Receiver stopped with error {}", receiverStopped.receiverInfo().lastErrorMessage());
    }

    @Override
    public void onReceiverStarted(StreamingListenerReceiverStarted arg0) {
    }

    @Override
    public void onReceiverError(StreamingListenerReceiverError error) {
    }

    @Override
    public void onOutputOperationStarted(StreamingListenerOutputOperationStarted outPutOpsStart) {
    }

    @Override
    public void onOutputOperationCompleted(StreamingListenerOutputOperationCompleted outPutOpsComplete) {
      Option<String> reason = outPutOpsComplete.outputOperationInfo().failureReason();
      if (!reason.isEmpty()) {
          String failure = reason.get();
          if (failure != null) {
              LOG.error("Output Operation failed due to {}", failure);
          }
      }
    }

    @Override
    public void onBatchSubmitted(StreamingListenerBatchSubmitted arg0) {
      batchSubmittedCount++;
      int queueSize = batchSubmittedCount - batchCompletedCount;
      if (queueSize > THROTTLE_QUEUE) {
        LOG.warn("stop consumer as pending queue {} greater than configured limit {}",queueSize, THROTTLE_QUEUE);
        //Set fetch size to 1 KB to throttle the consumer
        Utils.setFetchRate(config,1024);
      } else if(!config._backpressureEnabled) {
        Utils.setFetchRate(config, config._fetchSizeBytes);
      }
    }

    @Override
    public void onBatchStarted(StreamingListenerBatchStarted arg0) {
    }

    @Override
    public void onBatchCompleted(
            StreamingListenerBatchCompleted batchCompleted) {
        batchCompletedCount++;
        boolean backPressureEnabled = (boolean) config._backpressureEnabled;
        if (backPressureEnabled) {
          long processingDelay =
                  (Long) batchCompleted.batchInfo().processingDelay().get();
          long schedulingDelay =
                  (Long) batchCompleted.batchInfo().schedulingDelay().get();
          long numrecords = batchCompleted.batchInfo().numRecords();
          //Skip first batch as it may pull very less records which can have wrong rate for next batch
          if(batchCompletedCount > 1) {
            // Get last batch fetch size
            int batchFetchSize = getFetchSize();
            LOG.info("Current Rate in ZooKeeper  : " + batchFetchSize);
            // Revise rate on last rate
            int newRate = controller.calculateRate(config, batchDuration, partitionCount,
                    batchFetchSize, fillFreqMs, schedulingDelay, processingDelay, numrecords);
            LOG.info("Modified Rate by Controller  : " + newRate);
            // Setting to Min Rate
            if (newRate < MIN_RATE) {
              newRate = MIN_RATE;
            }
            // Setting to Max Rate
            if (newRate > MAX_RATE) {
              newRate = MAX_RATE;
            }
            Utils.setFetchRate(config, newRate);
          }
        }
    }

    private int getFetchSize() {
      int newFetchSize = 0;
      ZkState state = new ZkState((String) config._stateConf
          .get(Config.ZOOKEEPER_CONSUMER_CONNECTION));
      try {
        byte[] rate = state.readBytes(ratePath());
        if (rate != null) {
            newFetchSize = Integer.valueOf(new String(rate));
            LOG.info("Current Fetch Rate for topic {} is {}",
                config._stateConf.get("kafka.input.topic"), newFetchSize);
         } else {
            newFetchSize = config._fetchSizeBytes;
        }
      } catch (Throwable e) {
          newFetchSize = config._fetchSizeBytes;
      }finally {
        state.close();
      }
      return newFetchSize;
  }

  public String ratePath() {
      return config._stateConf.get(Config.ZOOKEEPER_CONSUMER_PATH)
              + "/" + config._stateConf.get(Config.KAFKA_CONSUMER_ID) + "/newrate";
  }
}
