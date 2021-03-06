package org.bptlab.cepta;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.streaming.api.datastream.AsyncDataStream;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.datastream.DataStreamUtils;
import org.bptlab.cepta.models.events.train.LiveTrainDataOuterClass.LiveTrainData;
import org.bptlab.cepta.models.events.train.PlannedTrainDataOuterClass.PlannedTrainData;
import org.bptlab.cepta.config.PostgresConfig;
import org.bptlab.cepta.operators.LivePlannedCorrelationFunction;
import org.bptlab.cepta.providers.LiveTrainDataProvider;
import org.testcontainers.containers.PostgreSQLContainer;
import org.junit.Assert;
import org.junit.Test;

import java.sql.*;

public class LivePlannedCorrelationTests {
  
  public void initDatabase(PostgreSQLContainer container) {
       // JDBC driver name and database URL
   String db_url = container.getJdbcUrl();
   String user = container.getUsername();
   String password = container.getPassword();
   
   Connection conn = null;
   Statement stmt = null;
   try{
      // Register JDBC driver
      Class.forName("org.postgresql.Driver");

      // Open a connection
      System.out.println("Connecting to a database...");
      conn = DriverManager.getConnection(db_url, user, password);
      System.out.println("Connected database successfully...");
      
      stmt = conn.createStatement();
      String sql;
      // Create table for planned data
      sql = createPlannedDatabaseQuery();
      stmt.executeUpdate(sql);

      // Execute insert queries
      System.out.println("Inserting records into the table...");
      sql = insertTrainWithTrainSectionIdStationIdQuery(42382923, 11111111);
      stmt.executeUpdate(sql);
      sql = insertTrainWithTrainSectionIdStationIdQuery(42093766, 11111111);
      stmt.executeUpdate(sql);
      System.out.println("Inserted records into the table...");

   }catch(SQLException se){
      //Handle errors for JDBC
      se.printStackTrace();
   }catch(Exception e){
      //Handle errors for Class.forName
      e.printStackTrace();
   }finally{
      //finally block used to close resources
      try{
         if(stmt!=null)
            conn.close();
      }catch(SQLException se){
      }// do nothing
      try{
         if(conn!=null)
            conn.close();
      }catch(SQLException se){
         se.printStackTrace();
      }//end finally try
   }//end try
   System.out.println("Goodbye!");

  }

  @Test
  public void testIdMatch() throws IOException {
    try(PostgreSQLContainer postgres = newPostgreSQLContainer()) {
      postgres.start();
      initDatabase(postgres);
      String address = postgres.getContainerIpAddress();
      Integer port = postgres.getFirstMappedPort();
      PostgresConfig postgresConfig = new PostgresConfig().withHost(address).withPort(port).withPassword(postgres.getPassword()).withUser(postgres.getUsername());
      
      DataStream<LiveTrainData> liveStream = LiveTrainDataProvider.matchingLiveTrainDatas(); 
      DataStream<Tuple2<LiveTrainData, PlannedTrainData>> correlatedTrainStream = AsyncDataStream
          .unorderedWait(liveStream, new LivePlannedCorrelationFunction(postgresConfig),
              100000, TimeUnit.MILLISECONDS, 1);

      Iterator<Tuple2<LiveTrainData, PlannedTrainData>> iterator = DataStreamUtils.collect(correlatedTrainStream);
      ArrayList<Tuple2<Long, Long>> correlatedIds = new ArrayList<>();
      while(iterator.hasNext()){
        Tuple2<LiveTrainData, PlannedTrainData> tuple = iterator.next();
        if (tuple.f1 == null){
          correlatedIds.add(new Tuple2<>(tuple.f0.getTrainSectionId(), null));
        } else{
          correlatedIds.add(new Tuple2<>(tuple.f0.getTrainSectionId(), tuple.f1.getTrainSectionId()));
        }
      }
      Assert.assertTrue(correlatedIds.contains(new Tuple2<>(42382923L, 42382923L)));
      Assert.assertTrue(correlatedIds.contains(new Tuple2<>(42093766L, 42093766L)));
    }
  }
  
  @Test
  public void testIdUnmatch() throws IOException {
      try(PostgreSQLContainer postgres = newPostgreSQLContainer()) {
      postgres.start();
      initDatabase(postgres);
      String address = postgres.getContainerIpAddress();
      Integer port = postgres.getFirstMappedPort();
      PostgresConfig postgresConfig = new PostgresConfig().withHost(address).withPort(port).withPassword(postgres.getPassword()).withUser(postgres.getUsername());

      DataStream<LiveTrainData> liveStream = LiveTrainDataProvider.unmatchingLiveTrainDatas();
      DataStream<Tuple2<LiveTrainData, PlannedTrainData>> correlatedTrainStream = AsyncDataStream
          .unorderedWait(liveStream, new LivePlannedCorrelationFunction(postgresConfig),
              100000, TimeUnit.MILLISECONDS, 1);

      Iterator<Tuple2<LiveTrainData, PlannedTrainData>> iterator = DataStreamUtils.collect(correlatedTrainStream);
      ArrayList<Tuple2<Long, Long>> correlatedIds = new ArrayList<>();
      while(iterator.hasNext()){
        Tuple2<LiveTrainData, PlannedTrainData> tuple = iterator.next();
        if (tuple.f1 == null){
          correlatedIds.add(new Tuple2<>(tuple.f0.getTrainSectionId(), null));
        } else{
          correlatedIds.add(new Tuple2<>(tuple.f0.getTrainSectionId(), tuple.f1.getTrainSectionId()));
        }
      }
      Assert.assertTrue(correlatedIds.contains(new Tuple2<>(11111111L, null)));
      Assert.assertTrue(correlatedIds.contains(new Tuple2<>(22222222L, null)));
    }
      //Assert.assertTrue(true);
  }

  private PostgreSQLContainer newPostgreSQLContainer(){
    return new PostgreSQLContainer<>().withDatabaseName("postgres").withUsername("postgres").withPassword("");
  }

  private String insertTrainWithTrainSectionIdStationIdQuery(long trainSectionId, long stationId){
    return String.format(
      "INSERT INTO public.planned(" +
      "id, " +
      "train_section_id , " +
      "station_id , " +
      "planned_event_time , " +
      "status , " +
      "first_train_id , " +
      "train_id , " +
      "planned_departure_time_start_station , " +
      "planned_arrival_time_end_station , " +
      "ru_id , " +
      "end_station_id , " +
      "im_id , " +
      "following_im_id , " +
      "message_status , " +
      "ingestion_time , " +
      "original_train_id )" +
      "VALUES (1, %d, %d, current_timestamp, 5, 6, 7, current_timestamp, current_timestamp, 10, 11, 12, 13, 14, current_timestamp, 16)", trainSectionId, stationId);
  }

  private String createPlannedDatabaseQuery(){
    return "CREATE TABLE public.planned ( " +
    "id integer, " +
    "train_section_id integer, " +
    "station_id integer, " +
    "planned_event_time timestamp, " +
    "status integer, " +
    "first_train_id integer, " +
    "train_id integer, " +
    "planned_departure_time_start_station timestamp, " +
    "planned_arrival_time_end_station timestamp, " +
    "ru_id integer, " +
    "end_station_id integer, " +
    "im_id integer, " +
    "following_im_id integer, " +
    "message_status integer, " +
    "ingestion_time timestamp, " +
    "original_train_id integer)";
  }
}
