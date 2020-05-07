package org.bptlab.cepta.operators;

import com.google.protobuf.Message;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.ServerAddress;
import com.mongodb.MongoCredential;
import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoClients;
import com.mongodb.reactivestreams.client.MongoCollection;
import com.mongodb.reactivestreams.client.MongoDatabase;
import static com.mongodb.client.model.Filters.*;
import static com.mongodb.client.model.Updates.*;

import org.bson.BsonReader;
import org.bson.BsonWriter;
import org.bson.codecs.Codec;
import org.bson.codecs.DecoderContext;
import org.bson.codecs.EncoderContext;
import org.bson.codecs.configuration.CodecProvider;
import org.bson.codecs.configuration.CodecRegistry;
import org.bson.codecs.pojo.PojoCodecProvider;
import org.bson.Document;
import static org.bson.codecs.configuration.CodecRegistries.*;

import org.apache.flink.streaming.api.functions.async.RichAsyncFunction;
import org.apache.flink.streaming.api.functions.ProcessFunction;
import org.apache.flink.streaming.api.functions.async.ResultFuture;
import org.apache.flink.util.Collector;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import java.util.Arrays;
import java.util.Collections;

import org.bptlab.cepta.config.MongoConfig;
import org.bptlab.cepta.utils.Util;
import org.bptlab.cepta.utils.Util.ProtoKeyValues;
import org.bptlab.cepta.models.events.train.LiveTrainDataOuterClass.LiveTrainData;
import org.bptlab.cepta.models.events.train.PlannedTrainDataOuterClass.PlannedTrainData;
import org.bptlab.cepta.models.events.train.TrainDelayNotificationOuterClass.TrainDelayNotification;



public class DelayShiftFunctionMongo extends
    RichAsyncFunction<LiveTrainData, TrainDelayNotification> {

    private MongoConfig mongoConfig = new MongoConfig();
    private transient MongoClient mongoClient;

    public DelayShiftFunctionMongo(MongoConfig mongoConfig) {
        this.mongoConfig = mongoConfig;    }

    public void open(org.apache.flink.configuration.Configuration parameters) throws Exception {
        super.open(parameters);
        CodecProvider eventCodecProvider = PojoCodecProvider.builder().register("org.bptlab.cepta.models.events.train.PlannedTrainDataOuterClass.PlannedTrainData").build();
        CodecProvider protoTimestampCodecProvider = PojoCodecProvider.builder().register("com.google.protobuf.Timestamp").build();
        CodecRegistry pojoCodecRegistry = fromRegistries(
            MongoClientSettings.getDefaultCodecRegistry(),
            fromProviders(eventCodecProvider),
            fromCodecs(new TimestampCodec()),
            fromProviders(protoTimestampCodecProvider));
        MongoClientSettings settings = MongoClientSettings.builder()
            .codecRegistry(pojoCodecRegistry)
            .applyConnectionString(new ConnectionString("mongodb://"+mongoConfig.getUser()+":"+mongoConfig.getPassword()+"@"+mongoConfig.getHost()+":"+mongoConfig.getPort()+"/?authSource=admin"))
            .build();
        this.mongoClient = MongoClients.create(settings);
    }

    @Override
    public void close() throws Exception {
        this.mongoClient.close();
    }

    @Override
    public void asyncInvoke(LiveTrainData dataset,
        final ResultFuture<TrainDelayNotification> resultFuture) throws Exception {
        /*
        asyncInvoke will be called for each incoming element
        the resultFuture is where the outgoing element(s) will be
        */
        MongoDatabase database = mongoClient.getDatabase(mongoConfig.getName());
        MongoCollection<Document> plannedTrainDataCollection = database.getCollection("plannedTrainData");

        //https://github.com/mongodb/mongo-java-driver/blob/eac754d2eed76fe4fa07dbc10ad3935dfc5f34c4/driver-reactive-streams/src/examples/reactivestreams/helpers/SubscriberHelpers.java#L53
        //https://github.com/reactive-streams/reactive-streams-jvm/tree/v1.0.3#2-subscriber-code
        Subscriber subscriber = new Subscriber() {
            @Override
            public void onSubscribe(Subscription subscription) {
                //Number of elements the subscriber want to get from the publisher
                //System.out.println("Mongo Operation Subscribed");
                subscription.request(Integer.MAX_VALUE);
            }

            @Override
            public void onNext(Object o) {
                System.out.println(o.toString());
                System.out.println("Mongo Operation Next");
            }

            @Override
            public void onError(Throwable throwable) {
                System.out.println("Mongo Operation Failed");
            }

            @Override
            public void onComplete() {
                System.out.println("Mongo Operation Successful");
                //mongoClient.close();
            }
        };
        plannedTrainDataCollection.find(eq("trainSectionId", 41534705)).first().subscribe(subscriber);
        TrainDelayNotification delay = TrainDelayNotification.newBuilder().setDelay(666).build();
        resultFuture.complete(Collections.singleton(delay));        
    }

    public class TimestampCodec implements Codec<com.google.protobuf.Timestamp> {
        @Override
        public void encode(final BsonWriter writer, final com.google.protobuf.Timestamp ts, final EncoderContext encoderContext) {
            writer.writeStartDocument();
                writer.writeName("seconds");
                writer.writeInt64(ts.getSeconds());
                writer.writeName("nanos");
                writer.writeInt32(ts.getNanos());
            writer.writeEndDocument();
        }
    
        @Override
        public com.google.protobuf.Timestamp decode(final BsonReader reader, final DecoderContext decoderContext) {
            return com.google.protobuf.Timestamp.newBuilder()
                    .setSeconds(reader.readInt64("seconds"))
                    .setNanos(reader.readInt32("nanos"))
                    .build();
        }
    
        @Override
        public Class<com.google.protobuf.Timestamp> getEncoderClass() {
            return com.google.protobuf.Timestamp.class;
        }
    }
}