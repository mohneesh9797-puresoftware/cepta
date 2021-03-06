package org.bptlab.cepta.operators;

import com.google.protobuf.Duration;
import org.apache.flink.api.common.functions.RichJoinFunction;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.windowing.assigners.SlidingEventTimeWindows;
import org.apache.flink.streaming.api.windowing.time.Time;
import org.bptlab.cepta.models.events.weather.WeatherDataOuterClass.WeatherData;
import org.bptlab.cepta.models.internal.delay.DelayOuterClass;
import org.bptlab.cepta.models.internal.notifications.notification.NotificationOuterClass;
import org.bptlab.cepta.models.events.train.LiveTrainDataOuterClass.LiveTrainData;
import org.bptlab.cepta.models.internal.types.ids.Ids;

public class WeatherLiveTrainJoinFunction {
  public static DataStream<NotificationOuterClass.Notification> delayFromWeather(DataStream<Tuple2<WeatherData, Integer>> weather, DataStream<LiveTrainData> train){
    return weather.join(train)
        .where(new KeySelector<Tuple2<WeatherData, Integer>, Integer>() {
          @Override
          public Integer getKey(Tuple2<WeatherData, Integer> weatherDataIntegerTuple2) throws Exception {
            return weatherDataIntegerTuple2.f1;
          }
        }).equalTo(new KeySelector<LiveTrainData, Integer>() {
          @Override
          public Integer getKey(LiveTrainData liveTrainData) throws Exception {
            return (int) liveTrainData.getStationId();
          }
        })
        .window(SlidingEventTimeWindows.of(Time.seconds(60), Time.seconds(60)))
        .apply(new RichJoinFunction<Tuple2<WeatherData, Integer>, LiveTrainData, NotificationOuterClass.Notification>() {
          @Override
          public NotificationOuterClass.Notification join(Tuple2<WeatherData, Integer> weatherDataIntegerTuple2,
              LiveTrainData liveTrainData) throws Exception {
            return NotificationOuterClass.Notification.newBuilder().setDelay(
                    NotificationOuterClass.DelayNotification.newBuilder()
                            .setDelay(DelayOuterClass.Delay.newBuilder().setDelta(delayFromWeather(weatherDataIntegerTuple2.f0)).build())
                            .setStationId(Ids.CeptaStationID.newBuilder().setId(String.valueOf(liveTrainData.getStationId())).build())
                            .build()
            ).build();
          }
        });
  }

  private static Duration delayFromWeather(WeatherData weather){
    String eventClass = weather.getEventClass().toString();
    int delay;
    switch (eventClass){
      case "Clear_night": delay = 0; break;
      case "rain": delay = 10; break;
      default: delay = 0;
    }
    return Duration.newBuilder().setSeconds((long) delay).build();
  }
}
