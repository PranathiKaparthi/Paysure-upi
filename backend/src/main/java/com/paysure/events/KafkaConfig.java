package com.paysure.events;

import java.util.ArrayList;
import java.util.List;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class KafkaConfig {

    /** Event topics plus a dead-letter topic for each (compose runs the broker with auto-creation disabled). */
    @Bean
    @ConditionalOnProperty(prefix = "paysure.events", name = "create-topics", havingValue = "true", matchIfMissing = true)
    KafkaAdmin.NewTopics paysureTopics(EventsProperties props) {
        List<NewTopic> topics = new ArrayList<>();
        for (String topic : EventTypes.allTopics()) {
            topics.add(TopicBuilder.name(topic).partitions(props.partitions()).replicas(props.replicas()).build());
            topics.add(TopicBuilder.name(topic + ".DLT").partitions(props.partitions()).replicas(props.replicas()).build());
        }
        return new KafkaAdmin.NewTopics(topics.toArray(NewTopic[]::new));
    }

    /**
     * A record whose handler keeps failing (e.g. a malformed event) is retried 3 times one second apart, then sent to
     * {@code <topic>.DLT} and its offset is committed, so one poison record cannot stall a partition.
     */
    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> template) {
        // Explicit destination: the library default is "<topic>-dlt", which would not exist on a broker that has topic
        // auto-creation disabled (as in docker-compose). Same partition number, so the DLT needs >= as many partitions.
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template,
                (record, exception) -> new TopicPartition(record.topic() + ".DLT", record.partition()));
        return new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 3));
    }
}
