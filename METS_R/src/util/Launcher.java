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

File: Launcher.java 

*/



package util;

import java.util.Random;

import java.util.ArrayList;
import java.util.List;

import util.ThreadTimer.Tick;

/**
 * Resets state when multiple runs of the same piece of code are executed.
 * 
 *
 */
public class Launcher {
  private static Launcher instance;
  private boolean isFirstRun;
  private Tick start;
  private Tick stop;
  private final List<Statistics> stats;
  private final List<Runnable> callbacks;
  private final Random random;

  private Launcher() {
    isFirstRun = true;
    stats = new ArrayList<Statistics>();
    callbacks = new ArrayList<Runnable>();

    random = new Random();
  }

  /**
   * Marks beginning of timed section. If there are multiple calls to this
   * method within the same run, honor the latest call. This method also
   * performs a full garbage collection.
   */
  public void startTiming() {
    System.gc();
    if (stop == null)
      start = ThreadTimer.tick();
  }

  /**
   * Marks end of timed section. If there are multiple calls to this method
   * within the same run, honor the earliest call.
   */
  public void stopTiming() {
    if (stop == null)
      stop = ThreadTimer.tick();
  }

  /**
   * 
   * @return  true if this is the first run
   */
  public boolean isFirstRun() {
    return isFirstRun;
  }

  /**
   * Returns the wall-time of the most recently completed run with or without
   * garbage collection time included
   * 
   * @param withoutGc true if garbage collection time should be excluded from result
   * @return   wall-time in milliseconds of the run
   */
  public long elapsedTime(boolean withoutGc) {
    return start.elapsedTime(withoutGc, stop);
  }

  /**
   * Resets the launcher to its initial configuration in preparation for the
   * next run. All previously accumulated statistics will be cleared.
   */
  public void reset() {
    stop = null;
    start = null;
    isFirstRun = false;
    for (Runnable r : callbacks) {
      r.run();
    }
    callbacks.clear();
    stats.clear();
  }

  /**
   * @return  the accumulated statistics for the most recently completed run
   */
  public List<Statistics> getStatistics() {
    return stats;
  }

  /**
   * Adds a callback to be called when launcher is reset.
   * 
   * @param callback  callback to called on reset
   */
  public void onRestart(Runnable callback) {
    callbacks.add(callback);
  }

  /**
   * Adds statistics to current run
   * 
   * @param stat  the statistics to add
   */
  public void addStats(Statistics stat) {
    if (stat != null)
      stats.add(stat);
  }

  /**
   * Returns the random number generator for this run
   * 
   * @param seed  the random number generator seed
   * @return      a random number generator
   */
  public Random getRandom(int seed) {
    random.setSeed(seed);
    return random;
  }

  /**
   * @return  an instance of the launcher
   */
  public static Launcher getLauncher() {
    if (instance == null)
      instance = new Launcher();
    return instance;
  }
}
