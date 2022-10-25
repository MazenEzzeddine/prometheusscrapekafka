import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.Timer;
import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Collections;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

public class ConsumerThread {
    private static final Logger log = LogManager.getLogger(ConsumerThread.class);
    static double eventsViolating = 0;
    static double eventsNonViolating = 0;
    static double totalEvents = 0;
    public static KafkaConsumer<String, Customer> consumer = null;
    static float maxConsumptionRatePerConsumer = 0.0f;
    static float ConsumptionRatePerConsumerInThisPoll = 0.0f;
    static float averageRatePerConsumerForGrpc = 0.0f;

    static long pollsSoFar = 0;
    static Double maxConsumptionRatePerConsumer1 = 0.0d;
    //Long[] waitingTimes = new Long[10];


    static PrometheusMeterRegistry prometheusRegistry;
    static Timer timer;

    public static void main(String[] args) {


        KafkaConsumerConfig config = KafkaConsumerConfig.fromEnv();
        log.info(KafkaConsumerConfig.class.getName() + ": {}", config.toString());
        Properties props = KafkaConsumerConfig.createProperties(config);
        //props.put(ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG, CooperativeStickyAssignor.class.getName());
        props.put(ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG, StickyAssignor.class.getName());
        //props.put(ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG, LagBasedPartitionAssignor.class.getName());
      /*  props.put(ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG,
                org.apache.kafka.clients.consumer.RangeAssignor.class.getName());*/
        boolean commit = !Boolean.parseBoolean(config.getEnableAutoCommit());
        consumer = new KafkaConsumer<String, Customer>(props);
        consumer.subscribe(Collections.singletonList(config.getTopic()));
        log.info("Subscribed to topic {}", config.getTopic());

        initPrometheus();


        try {
            while (true) {
                Long timeBeforePolling = System.currentTimeMillis();
                ConsumerRecords<String, Customer> records = consumer.poll(Duration.ofMillis(Long.MAX_VALUE));
                //ConsumerRecords<String, Customer> records = consumer.poll(Duration.ofMillis(0));
                if (records.count() != 0) {
                    // Long timeBeforePolling = System.currentTimeMillis();
                    for (ConsumerRecord<String, Customer> record : records) {
                        totalEvents++;
                        if (System.currentTimeMillis() - record.timestamp() <= 5000) {
                            eventsNonViolating++;
                        } else {
                            eventsViolating++;
                        }
                        //TODO sleep per record or per batch
                        try {
                            Thread.sleep(Long.parseLong(config.getSleep()));
                            // log.info("Sleeping for {}", config.getSleep());
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }

                    if (commit) {
                        consumer.commitSync();
                    }
                    log.info("In this poll, received {} events", records.count());
                    Long timeAfterPollingProcessingAndCommit = System.currentTimeMillis();
                    ConsumptionRatePerConsumerInThisPoll = ((float) records.count() /
                            (float) (timeAfterPollingProcessingAndCommit - timeBeforePolling)) * 1000.0f;
                    pollsSoFar += 1;
                    averageRatePerConsumerForGrpc = averageRatePerConsumerForGrpc +
                            (ConsumptionRatePerConsumerInThisPoll - averageRatePerConsumerForGrpc) / (float) (pollsSoFar);

                    if (maxConsumptionRatePerConsumer < ConsumptionRatePerConsumerInThisPoll) {
                        maxConsumptionRatePerConsumer = ConsumptionRatePerConsumerInThisPoll;
                    }
                    maxConsumptionRatePerConsumer1 = Double.parseDouble(String.valueOf(averageRatePerConsumerForGrpc));
                    log.info("ConsumptionRatePerConsumerInThisPoll in this poll {}", ConsumptionRatePerConsumerInThisPoll);
                    log.info("maxConsumptionRatePerConsumer {}", maxConsumptionRatePerConsumer);

                    log.info("averageRatePerConsumerForGrpc  {}", averageRatePerConsumerForGrpc);
                    double percentViolating = (double) eventsViolating / (double) totalEvents;
                    double percentNonViolating = (double) eventsNonViolating / (double) totalEvents;
                    log.info("Percent violating so far {}", percentViolating);
                    log.info("Percent non violating so far {}", percentNonViolating);
                    log.info("total events {}", totalEvents);
                    log.info("Number of events non violating {}", eventsNonViolating);
                    log.info(" Number of events violating {}", eventsViolating);
                    timer.record(Duration.ofMillis(timeAfterPollingProcessingAndCommit - timeBeforePolling));
                }

            }
        } catch (WakeupException e) {
            // e.printStackTrace();
        } finally {
            consumer.close();
            log.info("You may want to print the events");
            log.info("Closed consumer and we are done");
        }

    }


    private static void initPrometheus() {
         prometheusRegistry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
            server.createContext("/prometheus", httpExchange -> {
                String response = prometheusRegistry.scrape();
                httpExchange.sendResponseHeaders(200, response.getBytes().length);
                try (OutputStream os = httpExchange.getResponseBody()) {
                    os.write(response.getBytes());
                }
            });

            new Thread(server::start).start();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        timer = prometheusRegistry.timer("timer");

    }
}
