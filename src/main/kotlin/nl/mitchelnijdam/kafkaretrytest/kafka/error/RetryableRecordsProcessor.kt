package nl.mitchelnijdam.kafkaretrytest.kafka.error

import nl.mitchelnijdam.kafkaretrytest.kafka.seekToCurrent
import nl.mitchelnijdam.kafkaretrytest.kafka.seekToNext
import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.ConsumerRecords
import org.apache.kafka.common.TopicPartition
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer
import org.springframework.util.backoff.BackOff
import org.springframework.util.backoff.BackOffExecution
import kotlin.concurrent.getOrSet

/**
 * Will maintain a list of retried record batches ([ConsumerRecords]) and their backoff.
 * Uses all unique TopicPartitions in the batch together with their current (minimal) offset to identify if existing
 * BackOff should be used.
 *
 * Inspired by [org.springframework.kafka.listener.FailedRecordTracker].
 *
 * @param backOff used for delays between seeks
 *
 * @author Mitchel Nijdam
 */
class RetryableRecordBatchProcessor(private val backOff: BackOff, private val recoverer: DeadLetterPublishingRecoverer) {

    private val logger: Logger = LoggerFactory.getLogger(RetryableRecordBatchProcessor::class.java)

    private val failingBatches = ThreadLocal<Map<RecordBatchIdentifier, FailedRecordBatch>>()

    /**
     * Identifies if the current batch already has a [BackOffExecution] and uses that or starts a new one.
     * If the backOff has exceeded the configured attempts, it will recover the records.
     *
     * @param recordBatch the batch that failed
     * @param consumer the consumer from the error handler used for seeking to current
     * @param thrownException exception used to add to recovered records
     */
    fun seekToCurrentOrRecover(recordBatch: ConsumerRecords<*, *>, consumer: Consumer<*, *>, thrownException: Exception) {
        val records = recordBatch.toList()
        val failuresMap = failingBatches.getOrSet { emptyMap() }.toMutableMap()

        val batchIdentifier = getBatchIdentifier(records)

        val offsets = getSmallestOffsetByTopicPartition(records)

        var failedRecordBatch = failuresMap[batchIdentifier]
        if (failedRecordBatch == null || failedRecordBatch.offsets != offsets) {
            logger.debug("Adding new batch to the list of failing batches. batchIdentifier: $batchIdentifier")
            failedRecordBatch = FailedRecordBatch(offsets, this.backOff.start())
            failuresMap[batchIdentifier] = failedRecordBatch
        } else {
            logger.debug("Current batch already exists (identifier: $batchIdentifier), reusing backOffExecution")
        }

        val nextBackOff = failedRecordBatch.backOffExecution.nextBackOff()
        if (nextBackOff != BackOffExecution.STOP) {
            logger.debug("Backoff not stopped yet, will wait for $nextBackOff ms")
            try {
                Thread.sleep(nextBackOff)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }

            recordBatch.seekToCurrent(consumer)
        } else {
            logger.warn("Kafka batch ${batchIdentifier.identifier} has exceeded backOff retries, sending batch to DLQ")
            recordBatch.forEach { recoverer.accept(it, thrownException) }
            recordBatch.seekToNext(consumer)
            failuresMap.remove(batchIdentifier)
        }

        if (failuresMap.isEmpty()) {
            this.failingBatches.remove()
        } else {
            this.failingBatches.set(failuresMap)
        }
    }

    private fun getBatchIdentifier(records: List<ConsumerRecord<*, *>>): RecordBatchIdentifier {
        val topicPartitions = records
                .groupBy { record -> TopicPartition(record.topic(), record.partition()) }.keys

        return RecordBatchIdentifier(topicPartitions)
    }

    private fun getSmallestOffsetByTopicPartition(records: List<ConsumerRecord<*, *>>): Map<TopicPartition, Long> {
        return records
                .groupBy { record -> TopicPartition(record.topic(), record.partition()) }
                .mapValues { (_, value) -> value.minBy { it.offset() }!!.offset() }
    }


    private data class FailedRecordBatch(val offsets: Map<TopicPartition, Long>, val backOffExecution: BackOffExecution)

    private data class RecordBatchIdentifier(val identifier: Set<TopicPartition>)
}
