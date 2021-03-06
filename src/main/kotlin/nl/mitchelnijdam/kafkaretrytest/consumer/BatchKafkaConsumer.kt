package nl.mitchelnijdam.kafkaretrytest.consumer

import nl.mitchelnijdam.kafkaretrytest.configuration.KafkaBatchConsumerConfiguration
import nl.mitchelnijdam.kafkaretrytest.service.ExceptionService
import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

/**
 * These consumers is configured as a batchListener (see [KafkaBatchConsumerConfiguration]).
 *
 * @author Mitchel Nijdam on 28-11-2019
 */
@Component
class BatchKafkaConsumer(private val exceptionService: ExceptionService = ExceptionService(recoverAfterAttempt = 10)) {

    private val logger: Logger = LoggerFactory.getLogger(BatchKafkaConsumer::class.java)

    @KafkaListener(topics = ["test-retry-batch"], containerFactory = "kafkaBatchFactory")
    fun listen(records: List<ConsumerRecord<String, String>>, consumer: Consumer<*, *>) {
        logger.info("received ${records.size} '${records.first().topic()}' record(s)!")

        exceptionService.withRecords(records).iFailButWillRecover()
    }

    @KafkaListener(topics = ["test-retry-batch-custom"], containerFactory = "customErrorHandlerKafkaBatchFactory")
    fun listenForCustomErrorHandlerMessage(records: List<ConsumerRecord<String, String>>, consumer: Consumer<*, *>) {
        logger.info("received ${records.size} '${records.first().topic()}' record(s)!")

        exceptionService.withRecords(records).iFailButWillRecover()
    }

    @KafkaListener(topics = ["test-retry-batch-custom-e"], containerFactory = "extensiveCustomErrorHandlerKafkaBatchFactory")
    fun listenForExtensiveCustomErrorHandlerMessage(records: List<ConsumerRecord<String, String>>, consumer: Consumer<*, *>) {
        logger.info("received ${records.size} '${records.first().topic()}' record(s)!")

        exceptionService.withRecords(records).iFailButWillRecover()
    }
}