package org.bptlab.cepta;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;

import com.google.protobuf.Duration;
import org.apache.flink.streaming.api.datastream.AsyncDataStream;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.datastream.DataStreamUtils;
import org.bptlab.cepta.models.events.train.LiveTrainDataOuterClass.LiveTrainData;
import org.bptlab.cepta.models.events.train.PlannedTrainDataOuterClass.PlannedTrainData;
import org.bptlab.cepta.models.internal.delay.DelayOuterClass;
import org.bptlab.cepta.models.internal.notifications.notification.NotificationOuterClass;
import org.bptlab.cepta.config.PostgresConfig;
import org.bptlab.cepta.models.internal.types.ids.Ids;
import org.bptlab.cepta.operators.DelayShiftFunction;
import org.bptlab.cepta.providers.LiveTrainDataProvider;
import org.bptlab.cepta.providers.PlannedTrainDataProvider;
import org.bptlab.cepta.providers.WeatherDataProvider;
import org.bptlab.cepta.utils.functions.StreamUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import com.google.protobuf.GeneratedMessage;
import com.google.protobuf.Timestamp;
import sun.awt.image.SunWritableRaster.DataStealer;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;
import java.sql.*;

public class DelayShiftFunctionTests {

   @Test
   public void testRightAmount() throws IOException {
      try(PostgreSQLContainer postgres = newPostgreSQLContainer()) {
         postgres.start();

         ArrayList<String> insertQueries = new ArrayList<String>();
         insertQueries.add(insertTrainWithSectionIdQuery(42382923));
         insertQueries.add(insertTrainWithSectionIdQuery(42382923));
         insertQueries.add(insertTrainWithSectionIdQuery(42093766));
         insertQueries.add(insertTrainWithSectionIdQuery(333));
         
         initDatabase(postgres, insertQueries);
         String address = postgres.getContainerIpAddress();
         Integer port = postgres.getFirstMappedPort();
         PostgresConfig postgresConfig = new PostgresConfig().withHost(address).withPort(port).withPassword(postgres.getPassword()).withUser(postgres.getUsername());
         
         DataStream<LiveTrainData> liveStream = LiveTrainDataProvider.matchingLiveTrainDatas(); 
         DataStream<NotificationOuterClass.DelayNotification> delayStream = AsyncDataStream
            .unorderedWait(liveStream, new DelayShiftFunction(postgresConfig),
               100000, TimeUnit.MILLISECONDS, 1);

         ArrayList<NotificationOuterClass.DelayNotification> delayEvents = StreamUtils.collectStreamToArrayList(delayStream);
         Assert.assertEquals(3, delayEvents.size());
      }
   }

   @Test
   public void testDelayNotificationGeneration() throws IOException {
      try(PostgreSQLContainer postgres = newPostgreSQLContainer()) {
         postgres.start();
         
         ArrayList<String> insertQueries = new ArrayList<String>();
         insertQueries.add(insertTrainWithSectionIdStationIdQuery(42382923, 1));
         insertQueries.add(insertTrainWithSectionIdStationIdQuery(42382923, 2));
         insertQueries.add(insertTrainWithSectionIdStationIdQuery(42093766, 3));

         initDatabase(postgres, insertQueries);
         String address = postgres.getContainerIpAddress();
         Integer port = postgres.getFirstMappedPort();
         PostgresConfig postgresConfig = new PostgresConfig().withHost(address).withPort(port).withPassword(postgres.getPassword()).withUser(postgres.getUsername());
         
         DataStream<LiveTrainData> liveStream = LiveTrainDataProvider.matchingLiveTrainDatas(); 
         DataStream<NotificationOuterClass.DelayNotification> delayStream = AsyncDataStream
            .unorderedWait(liveStream, new DelayShiftFunction(postgresConfig),
               100000, TimeUnit.MILLISECONDS, 1);

         ArrayList<NotificationOuterClass.DelayNotification> delayEvents = StreamUtils.collectStreamToArrayList(delayStream);

         ArrayList<NotificationOuterClass.DelayNotification> expectedDelayNotifications = new ArrayList<NotificationOuterClass.DelayNotification>();
         expectedDelayNotifications.add(makeDelayNotification(42382923, 1, 1));
         expectedDelayNotifications.add(makeDelayNotification(42382923, 2, 1));
         expectedDelayNotifications.add(makeDelayNotification(42093766, 3, 1));
         Assert.assertEquals(expectedDelayNotifications, delayEvents);
      }
   }

   private NotificationOuterClass.DelayNotification makeDelayNotification(int trainID, int stationID, int delay) {
      return NotificationOuterClass.DelayNotification.newBuilder()
              .setDelay(DelayOuterClass.Delay.newBuilder().setDelta(Duration.newBuilder().setSeconds(delay).build()))
              .setStationId(Ids.CeptaStationID.newBuilder().setId(String.valueOf(stationID)).build())
              .setTransportId(Ids.CeptaTransportID.newBuilder().setId(String.valueOf(trainID)).build())
              .build();
   }

   @Test
   public void testDateConsideration() throws IOException {
      try(PostgreSQLContainer postgres = newPostgreSQLContainer()) {
         postgres.start();

         ArrayList<String> insertQueries = new ArrayList<String>();
         insertQueries.add(insertTrainWithSectionIdStationIdPlannedTimeQuery(42382923, 11111111, "2020-04-28 10:03:40.0"));
         insertQueries.add(insertTrainWithSectionIdStationIdPlannedTimeQuery(42382923, 2, "2020-03-28 10:03:40.0"));
         insertQueries.add(insertTrainWithSectionIdStationIdPlannedTimeQuery(42382923, 3, "2020-06-28 10:03:40.0"));
         insertQueries.add(insertTrainWithSectionIdStationIdPlannedTimeQuery(42382923, 4, "2020-04-28 11:03:40.0"));
         insertQueries.add(insertTrainWithSectionIdStationIdPlannedTimeQuery(42382923, 5, "2020-04-28 09:03:40.0"));

         initDatabase(postgres, insertQueries);
         String address = postgres.getContainerIpAddress();
         Integer port = postgres.getFirstMappedPort();
         PostgresConfig postgresConfig = new PostgresConfig().withHost(address).withPort(port).withPassword(postgres.getPassword()).withUser(postgres.getUsername());
         
         DataStream<LiveTrainData> liveStream = LiveTrainDataProvider.matchingLiveTrainDatas(); 
         DataStream<NotificationOuterClass.DelayNotification> delayStream = AsyncDataStream
            .unorderedWait(liveStream, new DelayShiftFunction(postgresConfig),
               100000, TimeUnit.MILLISECONDS, 1);
         ArrayList<NotificationOuterClass.DelayNotification> delayEvents = StreamUtils.collectStreamToArrayList(delayStream);

         ArrayList<NotificationOuterClass.DelayNotification> expectedDelayNotifications = new ArrayList<NotificationOuterClass.DelayNotification>();
         expectedDelayNotifications.add(makeDelayNotification(42382923, 11111111, 1));
         expectedDelayNotifications.add(makeDelayNotification(42382923, 4, 1));
         Assert.assertEquals(expectedDelayNotifications, delayEvents);
      }
   }

   public void initDatabase(PostgreSQLContainer container, ArrayList<String> insertQueries) {
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
         // System.out.println("Connecting to a database...");
         conn = DriverManager.getConnection(db_url, user, password);
         // System.out.println("Connected database successfully...");
         
         stmt = conn.createStatement();
         String sql;
         // Create table for planned data
         sql = createPlannedDatabaseQuery();
         stmt.executeUpdate(sql);
         
         // Execute insert queries
         // System.out.println("Inserting records into the table...");
         for (String query : insertQueries){
            sql = query;
            stmt.executeUpdate(sql);
         }
         // System.out.println("Inserted records into the table...");

      }catch(Exception e){
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

   private PostgreSQLContainer newPostgreSQLContainer(){
      return new PostgreSQLContainer<>().withDatabaseName("postgres").withUsername("postgres").withPassword("");
   }

   private String insertTrainWithSectionIdQuery(long sectionId){
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
      "VALUES (1, %d, 3, '2020-04-28 10:03:40.0', 5, 6, 7, '2020-04-28 10:03:40.0', '2020-04-28 10:03:40.0', 10, 11, 12, 13, 14, '2020-04-28 10:03:40.0', 16)", sectionId);
   }
   private String insertTrainWithSectionIdStationIdQuery(long trainId, int stationId){
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
      "VALUES (1, %d, %d, '2020-04-28 10:03:40.0', 5, 6, 7, '2020-04-28 10:03:40.0', '2020-04-28 10:03:40.0', 10, 11, 12, 13, 14, '2020-04-28 10:03:40.0', 16)", trainId, stationId);
   }

   private String insertTrainWithSectionIdStationIdPlannedTimeQuery(long trainId, int stationId, String plannedEventTime){
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
      "VALUES (1, %d, %d, '%s', 5, 6, 7, '2020-04-28 10:03:40.0', '2020-04-28 10:03:40.0', 10, 11, 12, 13, 14, '2020-04-28 10:03:40.0', 16)", trainId, stationId, plannedEventTime);
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
