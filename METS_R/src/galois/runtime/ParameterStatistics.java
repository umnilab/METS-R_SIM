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

import gnu.trove.list.array.TFloatArrayList;
import gnu.trove.list.array.TIntArrayList;

import java.io.PrintStream;
import java.util.List;

class ParameterStatistics extends IterationStatistics {
  private final TIntArrayList neighMins;
  private final TIntArrayList neighMaxs;
  private final TFloatArrayList neighAves;
  private final TFloatArrayList neighStdevs;
  private final TIntArrayList notUsefulSize;
  private final TIntArrayList parallelWork;
  private final TIntArrayList worklistSize;

  public ParameterStatistics() {
    parallelWork = new TIntArrayList();
    worklistSize = new TIntArrayList();
    notUsefulSize = new TIntArrayList();
    neighAves = new TFloatArrayList();
    neighStdevs = new TFloatArrayList();
    neighMaxs = new TIntArrayList();
    neighMins = new TIntArrayList();
  }

  public void putStats(Thread thread, int parallelWork, int worklistSize, int notUsefulSize, List<Integer> ns) {
    super.putStats(thread, parallelWork, 0);
    this.parallelWork.add(parallelWork);
    this.worklistSize.add(worklistSize);
    this.notUsefulSize.add(notUsefulSize);

    float[] stats = summarizeInts(ns, 0);
    if (stats != null) {
      neighAves.add(stats[0]);
      neighMins.add((int) stats[1]);
      neighMaxs.add((int) stats[2]);
      neighStdevs.add((float) Math.sqrt(stats[3]));
    } else {
      neighAves.add(0);
      neighMins.add(0);
      neighMaxs.add(0);
      neighStdevs.add(0);
    }
  }

  @Override
  public void dumpFull(PrintStream out) {
    printFullHeader(out, "Begin Parameter Statistics");
    out.append("Active Nodes,Worklist Size,Not Useful Work Size,");
    out.append("Ave Neigh,Min Neigh,Max Neigh,Stdev Neigh\n");
    for (int i = 0; i < parallelWork.size(); i++) {
      out.append(String.format("%s,%s,%s,%s,%s,%s,%s\n", parallelWork.get(i), worklistSize.get(i),
          notUsefulSize.get(i), neighAves.get(i), neighMins.get(i), neighMaxs.get(i), neighStdevs.get(i)));
    }
    printFullHeader(out, "End Parameter Statistics");
  }

  @Override
  public void merge(Object obj) {
    super.merge(obj);

    ParameterStatistics other = (ParameterStatistics) obj;
    parallelWork.addAll(other.parallelWork);
    worklistSize.addAll(other.worklistSize);
    notUsefulSize.addAll(other.notUsefulSize);

    neighAves.addAll(other.neighAves);
    neighMins.addAll(other.neighMins);
    neighMaxs.addAll(other.neighMaxs);
    neighStdevs.addAll(other.neighStdevs);
  }

  @Override
  public void dumpSummary(PrintStream out) {
    printSummaryHeader(out, "Parameter");
    int committed = getNumCommitted();
    int workDepth = parallelWork.size();

    out.printf("Committed: %d Critial Path Length: %d Average Parallelism: %.4f\n", committed, workDepth, committed
        / (float) workDepth);
  }
}
