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

import galois.objects.Mappable;
import util.fn.Lambda2Void;
import util.fn.Lambda3Void;
import util.fn.LambdaVoid;

/**
 * Helper class to simplify calling different <code>Lambda</code>
 * variants with different numbers of arguments.
 *
 */
enum MappableType {
  TYPE_0, TYPE_1, TYPE_2;

  @SuppressWarnings("unchecked")
  public <T, A1, A2> void call(Object body, T item, Object[] args) {
    switch (this) {
    case TYPE_0:
      ((LambdaVoid<T>) body).call(item);
      break;
    case TYPE_1:
      ((Lambda2Void<T, A1>) body).call(item, (A1) args[0]);
      break;
    case TYPE_2:
      ((Lambda3Void<T, A1, A2>) body).call(item, (A1) args[0], (A2) args[1]);
      break;
    default:
      throw new Error();
    }
  }

  @SuppressWarnings("unchecked")
  public <T> void call(Mappable<T> mappable, Object body, MapInternalContext ctx, Object[] args) {
    switch (this) {
    case TYPE_0:
      mappable.mapInternal((LambdaVoid) body, ctx);
      break;
    case TYPE_1:
      mappable.mapInternal((Lambda2Void) body, ctx, args[0]);
      break;
    case TYPE_2:
      mappable.mapInternal((Lambda3Void) body, ctx, args[0], args[1]);
      break;
    default:
      throw new Error();
    }
  }
}
