/*
Galois, a framework to exploit amorphous data-parallelism in irregular
programs.

Copyright (C) 2010, The University of Texas at Austin. All rights reserved.
UNIVERSITY EXPRESSLY DISCLAIMS ANY AND ALL WARRANTIES CONCERNING THIS SOFTWARE
AND DOCUMENTATION, INCLUDING ANY WARRANTIES OF MERCHANTABILITY, FITNESS FOR ANY
PARTICULAR PURPOSE, NON-INFRINGEMENT AND WARRANTIES OF PERFORMANCE, AND ANY
WARRANTY THAT MIGHT OTHERWISE ARISE FROM COURSE OF DEALING OR USAGE OF TRADE.
NO WARRANTY IS EITHER EXPRESS OR IMPLIED WITH RESPECT TO THE USE OF THE
SOFTWARE OR DOCUMENTATION. Under no circumstances shall University be liable
for incidental, special, indirect, direct or consequential damages or loss of
profits, interruption of business, or related expenses which may arise from use
of Software or Documentation, including but not limited to those resulting from
defects in Software and/or Documentation, or loss or inaccuracy of data of any
kind.

File: ThreadTimer.java 

*/



package util;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.List;

/**
 * Class that allows per-thread timing that excludes GC time. This class is
 * thread safe.
 * 
 * The class is used by instantiating a new object at each "tick" The time
 * between two ticks (exclusive of GC time) can then be calculated using the
 * static method elapsedTime().
 * 
 * Note that this class requires the use of a stop-the-world collector (as it
 * assumes that no mutator activity occurs while the GC is running)
 * 
 */
public final class ThreadTimer {
  private final static List<GarbageCollectorMXBean> garbageCollectorMXBeans;

  static {
    garbageCollectorMXBeans = ManagementFactory.getGarbageCollectorMXBeans();
  }

  /**
   * Creates a new tick, fixing the start time of the current event
   * 
   * @return a tick object
   */
  public static Tick tick() {
    return new Tick();
  }

  /**
   * A moment in time
   */
  public static class Tick {
    private long sysTime;
    private long gcTime;

    private Tick() {
      /*
       * Here is the problem: we want to make sure that we mark the system
       * time and the gc time consistently. However, between recording the
       * system time and recording the GC time, a garbage collection might
       * occur, which would make the numbers inconsistent with each other.
       * 
       * What we will do is record the number of GCs executed, then record
       * the system time and GC time, then re-check the number of GCs
       * executed. If the two numbers agree, then we have recorded
       * consistent times. Otherwise, we must re-do the measurement.
       */
      long gcCountBegin, gcCountEnd;
      do {
        gcCountBegin = numGCs();
        sysTime = milliTime(); // don't want a GC between this line
        gcTime = milliGcTime(); // and this one
        gcCountEnd = numGCs();
      } while (gcCountBegin != gcCountEnd);
    }

    /**
     * Returns the elapsed time between the two Ticks. The returned value does
     * not include any time spent by the JVM in garbage collection.
     * 
     * @param withoutGc  true if garbage collection time should not be included in result
     * @param end        the tick marking the end time
     * @return  elapsed time in milliseconds.
     */
    public long elapsedTime(boolean withoutGc, Tick end) {
      return (end.sysTime - sysTime) - (withoutGc ? (end.gcTime - gcTime) : 0);
    }

    private long milliTime() {
      return System.nanoTime() / 1000000;
    }

    private long milliGcTime() {
      long result = 0;
      for (GarbageCollectorMXBean garbageCollectorMXBean : garbageCollectorMXBeans) {
        // the collection time is already in milliseconds,
        result += Math.max(0, garbageCollectorMXBean.getCollectionTime());
      }
      return result;
    }

    private long numGCs() {
      long result = 0;
      for (GarbageCollectorMXBean garbageCollectorMXBean : garbageCollectorMXBeans) {
        result += garbageCollectorMXBean.getCollectionCount();
      }
      return result;
    }
  }
}
