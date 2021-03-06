package org.bptlab.cepta.operators;

import com.github.jasync.sql.db.ConnectionPoolConfigurationBuilder;
import com.github.jasync.sql.db.QueryResult;
import com.github.jasync.sql.db.pool.ConnectionPool;
import com.github.jasync.sql.db.postgresql.PostgreSQLConnection;
import com.github.jasync.sql.db.postgresql.PostgreSQLConnectionBuilder;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.sql.*;
import com.google.protobuf.GeneratedMessage;
import com.google.protobuf.Descriptors.FieldDescriptor;
import org.apache.flink.api.common.functions.MapFunction;
import org.bptlab.cepta.config.PostgresConfig;
import com.google.protobuf.Message; 

public class DataToDatabase<T extends Message> implements MapFunction<T, T> {

  private String table_name;
  private PostgresConfig postgresConfig = new PostgresConfig();

  public DataToDatabase(String table, PostgresConfig postgresConfig){
    this.table_name = table;
    this.postgresConfig = postgresConfig;
  }

  @Override
  public T map(T dataSet) throws Exception {
    insert(dataSet);
    return dataSet;
  }

  public void insert(T dataSet)
      throws NoSuchFieldException, IllegalAccessException {

    // Connection to PostgreSQL DB
    ConnectionPool<PostgreSQLConnection> connection;

    ConnectionPoolConfigurationBuilder config = new ConnectionPoolConfigurationBuilder();

    config.setUsername(postgresConfig.getUser());
    config.setPassword(postgresConfig.getPassword());
    config.setHost(postgresConfig.getHost());
    config.setPort(postgresConfig.getPort());
    config.setDatabase(postgresConfig.getName());
    config.setMaxActiveConnections(100);
    connection = PostgreSQLConnectionBuilder.createConnectionPool(config);

    List<String> columnNames = new ArrayList<String>();
    for (Map.Entry<FieldDescriptor,java.lang.Object> entry : dataSet.getAllFields().entrySet()) {
      columnNames.add(entry.getKey().getName());
    }

    // store strings of values and columns for sql query
    // String valuesString = valuesToQueryString(dataSet, columnNames);
    String insertionValues = arrayToQueryString(getValuesOfProtoMessage(dataSet));
    String columnsString = arrayToQueryString(columnNames);

    // Create query
    String query = "INSERT INTO " + table_name + columnsString
        + " VALUES " + insertionValues + ";";
    System.out.println(query);

    // send query
    CompletableFuture<QueryResult> future = connection.sendPreparedStatement(query);

    // really execute query and get query result
    try{
      QueryResult result = future.get();
    }catch (InterruptedException | ExecutionException e){
      System.out.println("Could not get result");
    }

    // Close the connection pool
    try{
      connection.disconnect().get();
    }catch (ExecutionException | InterruptedException e){
      System.out.println("Could not disconnect");
    }
  }

  private List<String> getValuesOfProtoMessage(T dataSet) throws NoSuchFieldException, IllegalAccessException {
    List<String> values = new ArrayList<String>();
    
    for (Map.Entry<FieldDescriptor,java.lang.Object> entry : dataSet.getAllFields().entrySet()) {
      System.out.println(entry.getKey() + "/" + entry.getValue());
      if(entry.getValue() instanceof com.google.protobuf.Timestamp){
        values.add(String.format("'%s'",PrototimestampToSqlTimestamp((com.google.protobuf.Timestamp)entry.getValue()).toString()));
      }
      else if(entry.getValue() instanceof String){
        // add ' ' around value if it's a string
        values.add(String.format("'%s'", entry.getValue().toString()));
      }else{
        values.add(entry.getValue().toString());
      }
    }
    return values;
  }

  private java.sql.Timestamp PrototimestampToSqlTimestamp(com.google.protobuf.Timestamp protoTimestamp){
    long seconds = protoTimestamp.getSeconds();
    java.sql.Timestamp timestamp = new Timestamp(seconds);
    return timestamp;
  }

  private String arrayToQueryString(List<String> elements){
    // takes the array's elements and converts them to a "(val1, val2, ...)" String
    // necessary for usage in the sql statement
    String string;

    // remove null values
    elements.removeIf(Objects::isNull);

    // add , between elements
    string = String.join(",", elements);

    // surround it with brackets
    string = String.format("(%s)", string);
    return string;
  }
}
