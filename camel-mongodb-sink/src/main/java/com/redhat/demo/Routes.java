package com.redhat.demo;

import com.mongodb.client.MongoClient;
import io.quarkus.mongodb.MongoClientName;
import org.apache.camel.builder.RouteBuilder;

import javax.inject.Inject;

public class Routes extends RouteBuilder {
    @Inject
    @MongoClientName("mongodbSink")
    MongoClient mongodbSink;

    @Override
    public void configure() throws Exception {

        from("kafka:{{kafka.topic.name}}?groupId=from-kafka-2-mongo-route&autoOffsetReset=earliest") // Ask to start from the beginning if we have unknown offset
                .routeId("FromKafka2Mongo")
                .log("Received from Kafka topic : \"${body}\"")
                .to("mongodb:mongodbSink?database={{quarkus.mongodb.database}}&collection={{quarkus.mongodb.collection}}&operation=insert")
                .log("Sent message to MongoDB");
    }
}