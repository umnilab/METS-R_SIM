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

File: MethodFlag.java 

*/



package galois.objects;

/**
 * This class defines flags to control the behavior of the Galois runtime system
 * upon invocation of a method on a Galois object such as a
 * {@link galois.objects.graph.Graph}. The use of the appropriate flag will
 * disable some of the default actions performed by the runtime and therefore
 * result in a faster execution. However, incorrect usage of these flags can
 * violate the transactional semantics of the Galois iterators and result in
 * wrong output or program crashes.
 * 
 */
public class MethodFlag {
  /**
   * This flag tells the runtime system to perform no action upon invocations of
   * methods on Galois objects, i.e., no conflict checking is performed and no
   * undo information is stored. This flag results in the fastest execution but
   * can only be used if it is guaranteed that the activity will not be rolled
   * back, the method only re-reads or re-writes data, or data are being read
   * that are guaranteed to also only be read by other activities. Activities
   * that are guaranteed to not be rolled back include reader operators and
   * cautious operators past the first update of shared data. An example of data
   * that are guaranteed to only be read by concurrent activities is reading the
   * completed top of a tree during top-down tree construction, where only the
   * leaf nodes are modified.
   */
  public static final byte NONE = 0;

  /**
   * This flag is the default. It tells the runtime system to perform all
   * actions upon invocations of methods on Galois objects. It is always safe to
   * use but results in the slowest execution.
   * 
   */
  public static final byte ALL = -1;

  /**
   * This flag tells the runtime system that it is necessary to check whether
   * the shared nodes and edges touched upon invocation of a method are not
   * already in use by another concurrent activity. If any of them are, a
   * conflict will be raised and one of the conflicting activities is aborted.
   * As a result, only one activity (transaction) can access a particular
   * instance of a {@link Lockable} object, such as a
   * {@link galois.objects.graph.GNode} in a graph. However, no undo information
   * is saved, so it must be safe to abort the activity without restoring the
   * graphs nodes and edges to the state they were in when the activity began.
   */
  public static final byte CHECK_CONFLICT = 1 << 0;

  /**
   * This flag tells the runtime system that it is necessary to save the
   * original nodes and edges before they are modified upon invocation of a
   * method. Thus, every invocation in a Galois object will result in the
   * storage of an <i>undo action</i> to be able to restore the activity's
   * initial state if a conflict is detected and the activity has to be rolled
   * back. However, no check for a conflict is performed during the method
   * invocation. Hence, this flag is useful when all the nodes and edges
   * accessed by this method have previously been read with conflict checking
   * enabled. A typical use case is an operator that conditionally modifies
   * nodes or edges that have already been read.
   */
  public static final byte SAVE_UNDO = 1 << 1;
}
