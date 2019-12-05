package org.bptlab.cepta.producers;

import java.util.Optional;
import java.util.Properties;
import org.bptlab.cepta.producers.Producer;

public abstract class Replayer<K, V> extends Producer<K, V> {
  public long frequency = 1000;
  public Optional<Integer> limit = Optional.empty();
  public int offset = 0;

  public Replayer(Properties props) {
    super(props);
  }

  public abstract void reset() throws Exception;

  public abstract void seekTo(int offset) throws Exception;

  public void setFrequency(long frequency) {
    this.frequency = frequency;
  }

  public long getFrequency() {
    return this.frequency;
  }

  public void setOffset(int offset) {
    this.offset = offset;
  }

  public int getOffset() {
    return this.offset;
  }

  public void setLimit(Integer limit) {
    this.limit = Optional.of(limit);
  }

  public Optional<Integer> getLimit() {
    return this.limit;
  }
}