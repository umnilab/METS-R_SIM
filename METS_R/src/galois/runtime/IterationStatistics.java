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


 */

package galois.runtime;

import java.io.PrintStream;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import util.CollectionMath;
import util.Pair;
import util.Statistics;
import util.fn.FnIterable;
import util.fn.Lambda;

class IterationStatistics extends Statistics {
  private final Map<Thread, Integer> committed;
  private final Map<Thread, Integer> aborted;

  public IterationStatistics() {
    committed = new HashMap<Thread, Integer>();
    aborted = new HashMap<Thread, Integer>();
  }

  @Override
  public void dumpFull(PrintStream out) {
    printFullHeader(out, "Iterations");

    Collection<Integer> total = total();
    Collection<Integer> c = committed.values();
    Collection<Float> abortRatio = abortRatio(total);

    int sumCommitted = CollectionMath.sumInteger(c);
    int sumTotal = CollectionMath.sumInteger(total);
    float sumAbortRatio = sumTotal != 0 ? (sumTotal - sumCommitted) / (float) sumTotal : 0;

    out.printf("Committed Iterations: %d per thread: %s\n", sumCommitted, c);
    summarizeInts(out, total, "\t");
    out.printf("Total Iterations: %d per thread: %s\n", sumTotal, total);
    summarizeInts(out, total, "\t");
    out.printf("Abort ratio: %.4f per thread: %s\n", sumAbortRatio, abortRatio);
    summarizeFloats(out, abortRatio, "\t");
  }

  private Collection<Float> abortRatio(Collection<Integer> total) {
    Collection<Float> abortRatio = FnIterable.from(aborted.values()).zip(FnIterable.from(total)).map(
        new Lambda<Pair<Integer, Integer>, Float>() {
          @Override
          public Float call(Pair<Integer, Integer> x) {
            return x.getSecond() != 0 ? x.getFirst() / (float) x.getSecond() : 0;
          }
        }).toList();
    return abortRatio;
  }

  private Collection<Integer> total() {
    Collection<Integer> total = CollectionMath.sumInteger(committed.values(), aborted.values());
    return total;
  }

  @Override
  public void dumpSummary(PrintStream out) {
    int total = CollectionMath.sumInteger(total());
    float abortRatio;
    if (total == 0)
      abortRatio = 0;
    else
      abortRatio = CollectionMath.sumInteger(aborted.values()) / (float) total;

    printSummaryHeader(out, "Iterations");
    out.printf("Committed: %d Total: %d Abort Ratio: %.4f\n", CollectionMath.sumInteger(committed.values()), total,
        abortRatio);
  }

  public void putStats(Thread thread, int numCommitted, int numAborted) {
    Integer n = committed.get(thread);
    if (n == null)
      committed.put(thread, numCommitted);
    else
      committed.put(thread, numCommitted + n);

    n = aborted.get(thread);
    if (n == null)
      aborted.put(thread, numAborted);
    else
      aborted.put(thread, numAborted + n);
  }

  public int getNumCommitted() {
    return CollectionMath.sumInteger(committed.values());
  }

  private static <K> void mergeMap(Map<K, Integer> dst, Map<K, Integer> src) {
    for (Map.Entry<K, Integer> entry : src.entrySet()) {
      K k = entry.getKey();
      Integer v = dst.get(k);
      if (v == null) {
        dst.put(k, entry.getValue());
      } else {
        dst.put(k, v + entry.getValue());
      }
    }
  }

  @Override
  public void merge(Object obj) {
    IterationStatistics other = (IterationStatistics) obj;
    mergeMap(aborted, other.aborted);
    mergeMap(committed, other.committed);
  }
}
