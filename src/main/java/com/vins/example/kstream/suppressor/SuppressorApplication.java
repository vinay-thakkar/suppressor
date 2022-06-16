package com.vins.example.kstream.suppressor;

import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerde;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.utils.Bytes;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.kstream.*;
import org.apache.kafka.streams.state.SessionStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import static io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG;
import static java.time.Duration.ofSeconds;

@SpringBootApplication
@Slf4j
public class SuppressorApplication {

  private Random random = new Random(1234567L);
  private AtomicInteger countOfRequest = new AtomicInteger(60);
  private AtomicInteger countOfResponse = new AtomicInteger(60);
  private static int ERROR_FACTOR = 6;

  public static void main(String[] args) {
    SpringApplication.run(SuppressorApplication.class, args);
  }

  /**
   * Supplier Function bound by the Kafka Binder to generate the data on input topic
   *
   * @return Supplier Function that generates Message<RequestResponse> on each poll
   */
  @Bean
  public Supplier<Message<RequestResponse>> dataGenerator() {
    return () -> {
			var requestId = String.valueOf(UUID.randomUUID());

			return countOfRequest.getAndDecrement() > 0 ? MessageBuilder
					.withPayload(RequestResponse.newBuilder()
							.setRequestId(requestId)
							.setRequestTime(Calendar.getInstance().getTime().getTime())
							.build())
					.setHeader(KafkaHeaders.MESSAGE_KEY, requestId)
					.build() : null;
    };
  }

  /**
   * Function bound by the Kafka-Streams Binder to process the data on input topic and
   * send the processed message to output topic
   *
   * Purpose:
   * - READ the incoming message on input topic,
   * - creates a request object (enrich)
   * - WRITE to output topic
   *
   * @return Processor Function that receives KStream from input topic and produces KStream on output topic
   */
  @Bean
  public Function<KStream<String, RequestResponse>, KStream<DataKey, RequestResponse>> requestProcessor() {
    return dataStream -> dataStream.map((k, v) -> new KeyValue(DataKey.newBuilder()
        .setRequestId(v.getRequestId())
        .build(), createRequest(v)));
  }

  /**
   * Function bound by the Kafka-Streams Binder to process the data on input topic and
   * send the processed message to output topic
   *
   * Purpose:
   * - READ the incoming message on input topic,
   * - processes the input,
   * - creates a response object (processes the message using external system)
   * - WRITE to output topic
   *
   * @return Processor Function that receives KStream from input topic and produces KStream on output topic
   */
  @Bean
  public Function<KStream<String, RequestResponse>, KStream<DataKey, RequestResponse>> responseProcessor() {
    return dataStream -> dataStream
        .peek((k,v) -> {
          if ((countOfResponse.get()) % ERROR_FACTOR == 0)
            log.info("Skipping {}, {} -> {}", countOfResponse.get(), k, v);
        })
        .filter((k,v) ->  (countOfResponse.getAndDecrement()) % ERROR_FACTOR != 0)     // every sixth is skipped so we have missing response
        .map((k, v) -> new KeyValue<>(DataKey.newBuilder()
        .setRequestId(v.getRequestId())
        .build(), this.createResponse(v)));
  }

  /**
   * request enriching business process
   * @param input
   * @return RequestResponse object with enriched data
   */
  private RequestResponse createRequest(RequestResponse input) {
    return RequestResponse.newBuilder(input)
        .setMessageType(MessageType.REQUEST)
        .setRequestTime(Calendar.getInstance().getTime().getTime())
        .build();
  }
  /**
   * request enriching business process
   * @param input
   * @return RequestResponse object with enriched data
   */
  @SneakyThrows
  private RequestResponse createResponse(RequestResponse input) {
    long millis = random.nextInt(1000); // Upto 1000 millis
    Thread.sleep(millis);
    return RequestResponse.newBuilder(input)
        .setResponseId(String.valueOf(UUID.randomUUID()))
        .setMessageType(MessageType.RESPONSE)
        .setResponseTime(Calendar.getInstance().getTime().getTime())
        .setAcceleration(random.nextFloat(0.01F, 0.99F))
        .setVelocity(random.nextFloat(0.5F, 1.5F))
        .setTemperature(random.nextFloat(16.0F, 28.0F))
        .build();
  }

  /**
   * @return Consumer Function that receives KStream from output topic and
   * verifies all the supplied data was received correctly
   */
  @Bean
  public Consumer<KStream<DataKey, RequestResponse>> dataVerifier() {
    return dataStream -> dataStream
        .filter((k,v) -> v.getMessageType().equals(MessageType.RESPONSE))
//        .peek((k, v) -> log.info("Time Taken: {}, {} -> {}", v.getResponseTime() - v.getRequestTime(), k, v))
        ;
  }


  private static final Duration WINDOW_TIME_IN_SECONDS = ofSeconds(20L);  //TODO: agree time and change to minutes
  private static final Duration GRACE_TIME_IN_SECONDS = ofSeconds(10L);  //TODO: agree time and change to minutes
  private final SessionWindows window = SessionWindows.ofInactivityGapAndGrace(WINDOW_TIME_IN_SECONDS, GRACE_TIME_IN_SECONDS);

  /**
   * @return Consumer Function that receives KStream from output topic and
   * verifies all the supplied data was received correctly
   */
  @Bean
  public Function<KStream<DataKey, RequestResponse>, KStream<DataKey, GroupedRequestResponse>> tryingSuppress() {
    return dataStream -> dataStream
        .peek((k, v) -> {
          if (v.getMessageType().equals(MessageType.RESPONSE)) {
            log.info("RECEIVED RESPONSE: {}, {} -> {}", v.getResponseTime() - v.getRequestTime(), k, v);
          }
        })
        .groupByKey()
        .windowedBy(window)
        .aggregate(init,
            agg,
            merge,
            Named.as("Aggregator"),
            Materialized.as("Aggregator-store"))
        .suppress(Suppressed.untilWindowCloses(Suppressed.BufferConfig
              .unbounded()
              .shutDownWhenFull())
            .withName("Suppressor"))
        .filter((k,v) -> v.getCount() == 1)
        .toStream((k,v) -> k.key())
        .peek((k,v) -> log.info("MISSING RESPONSE: {}, {} -> {}", -1, k, v))
        ;
  }

  /**
   * Group object initialisation
   */
  private final Initializer<GroupedRequestResponse> init = () -> GroupedRequestResponse.newBuilder()
      .setMessages(new ArrayList<>())
      .setCount(0)
      .setDuration(-1)
      .build();

  /**
   * adding the incoming messages (REQUEST and RESPONSE) to the group for the given datakey
   * increments the count of message if response  received it should be 2 else it should be 1
   * duration is set only if the RESPONSE is received
   */
  private final Aggregator<DataKey, RequestResponse, GroupedRequestResponse> agg = (dataKey, newMessage, group) -> {

    var newGroup = GroupedRequestResponse.newBuilder(group)
        .build();


    newGroup.getMessages().add(newMessage);
    newGroup.setCount(newGroup.getMessages().size());
    if (newMessage.getMessageType().equals(MessageType.RESPONSE)) {
      newGroup.setDuration(newMessage.getResponseTime() - newMessage.getRequestTime());
    }
    return newGroup;
  };

  /**
   * merging the 2 group by adding all the items from the new group to old group
   * used by the session window when the incoming message suggests the 2 windows need to be merged
   */
  private final Merger<DataKey, GroupedRequestResponse> merge = (ids, group1, group2) -> {
    group1.getMessages().addAll(group2.getMessages());
    // COUNT and DURATION calculation missing.
    // with current setup there will never be more than 1 request and 1 response. So it's ok for now
    return group1;
  };

}
