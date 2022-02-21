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

File: RuntimeStatistics.java 

 */

package util;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Runtime statistics (wall-clock time) with and without garbage collection
 * time. This class is intended to repeatedly measure the same piece of code
 * and return a summary of the measured runtimes. 
 * 
 *
 */
public class RuntimeStatistics extends Statistics {
  private final List<Long> times;
  private final List<Long> timesWithGc;

  public RuntimeStatistics() {
    this.times = new ArrayList<Long>();
    this.timesWithGc = new ArrayList<Long>();
  }

  /**
   * Adds the measured times to the accumulated statistics.
   * 
   * @param time       wall-clock time in milliseconds <i>excluding</i> garbage collection time
   * @param timeWithGc wall-clock time in milliseconds <i>including</i> garbage collection time
   */
  public void putStats(long time, long timeWithGc) {
    times.add(time);
    timesWithGc.add(timeWithGc);
  }

  @Override
  public void dumpFull(PrintStream out) {
    printFullHeader(out, "Runtimes");
    out.printf("Without GC (ms): %s\n", times);
    summarizeLongs(out, times, 0, "\t");
    out.printf("With GC (ms): %s\n", timesWithGc);
    summarizeLongs(out, timesWithGc, 0, "\t");
  }

  @Override
  public void dumpSummary(PrintStream out) {
    printSummaryHeader(out, "Runtimes (ms)");
    float[] wgc = summarizeLongs(timesWithGc, 1);
    float[] wogc = summarizeLongs(times, 1);
    boolean output = wgc != null;
    summarizeLongs(out, timesWithGc, 1, "", output);
    if (output) {
      float rel = 0;
      if (wgc[0] != 0)
        rel = (wgc[0] - wogc[0]) / wgc[0];
      out.printf(" Rel. GC time: %.4f\n", rel);
    }
  }

  @Override
  public void merge(Object obj) {
    RuntimeStatistics other = (RuntimeStatistics) obj;
    times.addAll(other.times);
    timesWithGc.addAll(other.timesWithGc);
  }
}
