package com.ecommerce.shipping.config;

import com.ecommerce.shipping.exception.DependencyFailures;
import com.ecommerce.shipping.kafka.DeadLetterRecoverer;
import com.ecommerce.shipping.kafka.ListenerFailureLogger;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.JacksonMapperUtils;
import org.springframework.kafka.support.ProducerListener;
import org.springframework.kafka.support.converter.JacksonJsonMessageConverter;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.ExponentialBackOff;
import org.springframework.util.backoff.FixedBackOff;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

import java.util.HashMap;
import java.util.Map;

@Configuration
@EnableKafka
public class KafkaProducerConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    /**
     * Event JSON as it has always looked on the wire: spring-kafka's mapper defaults,
     * but with java.time as numeric arrays ([y, M, d, H, m, s, nanos]) instead of the
     * ISO strings Jackson 3 writes by default. Every consumer and the Pact contracts
     * pin the array form. The events are written to the outbox table as this JSON
     * (see {@code ShipmentEventProducer}) and the relay sends the text as it is.
     */
    public static JsonMapper eventJsonMapper() {
        return JacksonMapperUtils.enhancedJsonMapper().rebuild()
                .enable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
    }

    /**
     * Bounded producers: with the broker down a send used to wait the Kafka defaults (60 s metadata wait,
     * 120 s delivery), and reconnect attempts every 50 ms to 1 s logged a warning each. Now a send fails
     * within 5 s (30 s once the metadata is cached) and the client warns once per ~10 s.
     */
    static Map<String, Object> producerProps(String bootstrapServers) {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.RECONNECT_BACKOFF_MS_CONFIG, 1000);
        props.put(ProducerConfig.RECONNECT_BACKOFF_MAX_MS_CONFIG, 10000);
        props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 5000);
        props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 10000);
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 30000);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        return props;
    }

    /**
     * The one producer: the outbox relay publishes the stored event JSON through it, and so does the
     * dead-letter recoverer (original record value, unchanged). Strings in and out, so the bytes on the wire
     * are exactly the stored text.
     */
    @Bean
    public ProducerFactory<String, String> producerFactory() {
        return new DefaultKafkaProducerFactory<>(producerProps(bootstrapServers), new StringSerializer(), new StringSerializer());
    }

    @Bean
    public KafkaTemplate<String, String> kafkaTemplate() {
        KafkaTemplate<String, String> template = new KafkaTemplate<>(producerFactory());
        // The relay and the dead-letter recoverer report a failed send themselves, throttled and without a
        // stack trace; the default listener would log an ERROR with one per failed send.
        template.setProducerListener(new ProducerListener<>() { });
        return template;
    }

    @Bean
    public ConsumerFactory<String, String> consumerFactory() {
        Map<String, Object> configs = new HashMap<>();
        configs.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        configs.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        configs.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        configs.put(ConsumerConfig.GROUP_ID_CONFIG, "shipping-service");
        configs.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        // With the broker down: one "could not be established" warning per ~10 s instead of one per second.
        configs.put(ConsumerConfig.RECONNECT_BACKOFF_MS_CONFIG, 1000);
        configs.put(ConsumerConfig.RECONNECT_BACKOFF_MAX_MS_CONFIG, 10000);
        return new DefaultKafkaConsumerFactory<>(configs);
    }

    /** {@code payment.completed} to {@code payment.completed.DLT}, partition left to the producer. */
    @Bean(destroyMethod = "close")
    public DeadLetterRecoverer deadLetterRecoverer(KafkaTemplate<String, String> kafkaTemplate,
                                                   ObjectProvider<MeterRegistry> meters) {
        return new DeadLetterRecoverer(kafkaTemplate,
                (record, ex) -> new TopicPartition(record.topic() + ".DLT", -1), meters.getIfAvailable());
    }

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(DeadLetterRecoverer recoverer) {
        return errorHandlerFor(recoverer);
    }

    /**
     * A record that fails is retried, then dead-lettered, and never blocks its partition for good:
     * <ul>
     *   <li>A dependency that does not answer (database down, pool or lock timeout) is waited for: 1 s doubling
     *       to 30 s between attempts, <b>no deadline</b>, so good records are not dead-lettered because the
     *       database was away. Each pause is far below {@code max.poll.interval.ms}.</li>
     *   <li>Anything else gets two more attempts a second apart, then goes to {@code <topic>.DLT}.</li>
     *   <li>Records that cannot be converted are not retried (spring-kafka's default classification) and go
     *       straight to the dead-letter topic.</li>
     * </ul>
     */
    public static DefaultErrorHandler errorHandlerFor(DeadLetterRecoverer recoverer) {
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 2));
        handler.setBackOffFunction(KafkaProducerConfig::backOffFor);
        handler.setRetryListeners(new ListenerFailureLogger());
        return handler;
    }

    static BackOff backOffFor(ConsumerRecord<?, ?> record, Exception failure) {
        if (DependencyFailures.isUnavailable(failure)) {
            ExponentialBackOff wait = new ExponentialBackOff(1000L, 2.0);
            wait.setMaxInterval(30_000L);
            return wait;
        }
        return new FixedBackOff(1000L, 2);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(DefaultErrorHandler kafkaErrorHandler) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory());
        factory.setRecordMessageConverter(new JacksonJsonMessageConverter());
        factory.setCommonErrorHandler(kafkaErrorHandler);
        return factory;
    }
}
