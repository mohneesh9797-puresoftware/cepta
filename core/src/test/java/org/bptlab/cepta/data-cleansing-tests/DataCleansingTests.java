package org.bptlab.cepta;

import java.util.ArrayList;
import java.util.Iterator;
import java.io.IOException;
import org.junit.Assert;
import org.junit.Test;
import org.junit.Ignore;
import org.apache.flink.streaming.api.datastream.DataStreamUtils;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.bptlab.cepta.operators.DataCleansingFunction;
import org.bptlab.cepta.providers.JavaDataProvider;
import org.bptlab.cepta.providers.LiveTrainDataProvider;
import sun.security.util.Length;
import org.bptlab.cepta.models.events.train.LiveTrainDataProtos.LiveTrainData;
import java.lang.reflect.*;

public class DataCleansingTests {

    @Test
    public void TestIntegerCleansing() throws IOException {
        boolean pass = true;
        // get Stream of Integers with an element with the value of Integer.MIN_VALUE
        DataStream<Integer> integerStream = JavaDataProvider.integerDataStreamWithElement(Integer.MIN_VALUE);  
        // cleanse all Integer.MIN_VALUE elements from our Stream
        DataCleansingFunction DataCleansingFunction = new DataCleansingFunction<Integer>();        
        DataStream<Integer> cleansedStream = DataCleansingFunction.cleanseStream(integerStream, Integer.MIN_VALUE);
        // add all remaining elements of the Stream in an ArrayList
        ArrayList<Integer> cleansedInteger = new ArrayList<>();
        Iterator<Integer> iterator = DataStreamUtils.collect(cleansedStream);
        while(iterator.hasNext()){
            Integer integer = iterator.next();
            cleansedInteger.add(integer);
        }
        // check if remaining elements still have MIN.VALUE and fail if true
        int len = cleansedInteger.size();
        for (int i = 0; i < len; i++ ) {
            if (cleansedInteger.get(i) == Integer.MIN_VALUE) {
                System.out.println("Failed cause MIN VALUE exists");
                pass = false; 
            }
        }
        Assert.assertTrue(pass);
    }

    
    @Test
    public void TestNoIntegerCleansing() throws IOException {
        boolean pass = false;
        // get Stream of Integers with an element with the value of Integer.MIN_VALUE
        DataStream<Integer> integerStream = JavaDataProvider.integerDataStreamWithElement(Integer.MIN_VALUE);  
        // cleanse all Integer.MIN_VALUE elements from our Stream
        DataCleansingFunction DataCleansingFunction = new DataCleansingFunction<Integer>();        
        DataStream<Integer> cleansedStream = DataCleansingFunction.cleanseStream(integerStream, Integer.MIN_VALUE);
        // add all remaining elements of the Stream in an ArrayList
        ArrayList<Integer> cleansedInteger = new ArrayList<>();
        Iterator<Integer> iterator = DataStreamUtils.collect(cleansedStream);
        while(iterator.hasNext()){
            Integer integer = iterator.next();
            cleansedInteger.add(integer);
        }
        // check if remaining elements still have 1 and fail if not
        int len = cleansedInteger.size();
        for (int i = 0; i < len; i++ ) {
            if (cleansedInteger.get(i).equals(1)) {
                pass = true; 
            }
        }
        Assert.assertTrue(pass);
    }

    @Test
    public void TestStringCleansing() throws IOException {
        boolean pass = true;
        // get Stream of String with an element with the value of "Test"
        DataStream<String> stringStream = JavaDataProvider.stringDataStreamWithElement("Test");  
        // cleanse all "Test" elements from our Stream
        DataCleansingFunction DataCleansingFunction = new DataCleansingFunction<String>(); 
        DataStream<String> cleansedStream = DataCleansingFunction.cleanseStream(stringStream, "Test");
        // add all remaining elements of the Stream in an ArrayList
        ArrayList<String> cleansedStrings = new ArrayList<>();
        Iterator<String> iterator = DataStreamUtils.collect(cleansedStream);
        while(iterator.hasNext()){
            String stringValue = iterator.next();
            cleansedStrings.add(stringValue);
        }
        // check if remaining elements still have "Test" and fail if true
        int len = cleansedStrings.size();
        for (int i = 0; i < len; i++ ) {
            if (cleansedStrings.get(i).equals("Test")) {
                System.out.println("Failed cause MIN VALUE exists");
                pass = false; 
            }
        }
        Assert.assertTrue(pass);
    }

    @Test
    public void TestDoubleCleansing() throws IOException {
        boolean pass = true;
        // get Stream of Doubles with an element with the value of Double.MAX_VALUE
        DataStream<Double> stringStream = JavaDataProvider.doubleDataStreamWithElement(Double.MAX_VALUE);  
        // cleanse all Double.MAX_VALUE elements from our Stream
        DataCleansingFunction DataCleansingFunction = new DataCleansingFunction<Double>(); 
        DataStream<Double> cleansedStream = DataCleansingFunction.cleanseStream(stringStream, Double.MAX_VALUE);
        // add all remaining elements of the Stream in an ArrayList
        ArrayList<Double> cleansedDoubles = new ArrayList<>();
        Iterator<Double> iterator = DataStreamUtils.collect(cleansedStream);
        while(iterator.hasNext()){
            Double doubleValue = iterator.next();
            cleansedDoubles.add(doubleValue);
        }
        // check if remaining elements still have Double.MAX_VALUE and fail if true
        int len = cleansedDoubles.size();
        for (int i = 0; i < len; i++ ) {
            if (cleansedDoubles.get(i).equals(Double.MAX_VALUE)) {
                System.out.println("Failed cause MIN VALUE exists");
                pass = false; 
            }
        }
        Assert.assertTrue(pass);
    }

    @Test
    public void TestLongCleansing() throws IOException {
        boolean pass = true;
        // get Stream of Longs with an element with the value of Long.MAX_VALUE
        DataStream<Long> longStream = JavaDataProvider.longDataStreamWithElement(Long.MAX_VALUE);  
        // cleanse all Long.MAX_VALUE elements from our Stream
        DataCleansingFunction DataCleansingFunction = new DataCleansingFunction<Long>(); 
        DataStream<Long> cleansedStream = DataCleansingFunction.cleanseStream(longStream, Long.MAX_VALUE);
        // add all remaining elements of the Stream in an ArrayList
        ArrayList<Long> cleansedLongs = new ArrayList<>();
        Iterator<Long> iterator = DataStreamUtils.collect(cleansedStream);
        while(iterator.hasNext()){
            Long longValue = iterator.next();
            cleansedLongs.add(longValue);
        }
        // check if remaining elements still have Long.MAX_VALUE and fail if true
        int len = cleansedLongs.size();
        for (int i = 0; i < len; i++ ) {
            if (cleansedLongs.get(i).equals(Long.MAX_VALUE)) {
                System.out.println("Failed cause MIN VALUE exists");
                pass = false; 
            }
        }
        Assert.assertTrue(pass);
    }

    @Test
    public void TestBooleanCleansing() throws IOException {
        boolean pass = true;
        // get Stream of Bools 
        DataStream<Boolean> booleanStream = JavaDataProvider.booleanDataStream();  
        // cleanse all false elements from our Stream
        DataCleansingFunction DataCleansingFunction = new DataCleansingFunction<Boolean>(); 
        DataStream<Boolean> cleansedStream = DataCleansingFunction.cleanseStream(booleanStream, false);
        // add all remaining elements of the Stream in an ArrayList
        ArrayList<Boolean> cleansedBooleans = new ArrayList<>();
        Iterator<Boolean> iterator = DataStreamUtils.collect(cleansedStream);
        while(iterator.hasNext()){
            Boolean booleanValue = iterator.next();
            cleansedBooleans.add(booleanValue);
        }
        // check if remaining elements still have false and fail if true
        int len = cleansedBooleans.size();
        for (int i = 0; i < len; i++ ) {
            if (cleansedBooleans.get(i).equals(false)) {
                System.out.println("Failed cause false exists");
                pass = false; 
            }
        }
        Assert.assertTrue(pass);
    }

    @Test
    public void TestLiveTrainDataCleansing() throws Exception {
        boolean pass = true;

        DataStream<LiveTrainData> liveTrainDataStream = LiveTrainDataProvider.LiveTrainDatStream();  
        DataCleansingFunction DataCleansingFunction = new DataCleansingFunction<LiveTrainData>(); 
    
        DataStream<LiveTrainData> cleansedStream = DataCleansingFunction.cleanseStream(liveTrainDataStream, 4, "TrainId");

        ArrayList<LiveTrainData> cleansedLiveTrainData = new ArrayList<>();
        Iterator<LiveTrainData> iterator = DataStreamUtils.collect(cleansedStream);
        while(iterator.hasNext()){
            LiveTrainData liveTrainDataValue = iterator.next();
            cleansedLiveTrainData.add(liveTrainDataValue);
        }
        // check if remaining elements still have test Element and fail if true
        int len = cleansedLiveTrainData.size();
        for (int i = 0; i < len; i++ ) {
            if (cleansedLiveTrainData.get(i).getTrainId() == 4L) {
                System.out.println("Failed cause trainId exist");
                pass = false; 
            }
        }

        Assert.assertTrue(pass);
    }

    @Test
    public void TestNoLiveTrainDataCleansing() throws Exception {
        boolean pass = false;

        DataStream<LiveTrainData> liveTrainDataStream = LiveTrainDataProvider.LiveTrainDatStream();  
        DataCleansingFunction DataCleansingFunction = new DataCleansingFunction<LiveTrainData>(); 
       
        DataStream<LiveTrainData> cleansedStream = DataCleansingFunction.cleanseStream(liveTrainDataStream, 4, "TrainId");

        ArrayList<LiveTrainData> cleansedLiveTrainData = new ArrayList<>();
        Iterator<LiveTrainData> iterator = DataStreamUtils.collect(cleansedStream);
        while(iterator.hasNext()){
            LiveTrainData liveTrainDataValue = iterator.next();
            cleansedLiveTrainData.add(liveTrainDataValue);
        }
        // check if remaining elements still have test Element and fail if true
        int len = cleansedLiveTrainData.size();
        for (int i = 0; i < len; i++ ) {
            if (cleansedLiveTrainData.get(i).getTrainId() == 3L) {
                pass = true; 
            }
        }
        Assert.assertTrue(pass);
    }

}