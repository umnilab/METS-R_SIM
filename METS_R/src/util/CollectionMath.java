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

File: CollectionMath.java 

*/



package util;

import java.util.Collection;

import util.fn.FnIterable;
import util.fn.Lambda;
import util.fn.Lambda2;

/**
 * Utility math methods defined over collections of elements
 * 
 *
 */
public class CollectionMath {
  /**
   * @param c  a collection of integers
   * @return   sum of the collection of integers
   */
  public static Integer sumInteger(Iterable<Integer> c) {
    return sumInteger(FnIterable.from(c));
  }

  /**
   * @param it  a sequence of integers
   * @return    sum of the sequence of integers
   */
  public static Integer sumInteger(FnIterable<Integer> it) {
    return it.reduce(new Lambda2<Integer, Integer, Integer>() {
      @Override
      public Integer call(Integer arg0, Integer arg1) {
        return arg0 + arg1;
      }
    }, 0);
  }

  /**
   * Pair-wise sum of two iterators over integers. Iterators should have the same
   * length.
   * 
   * @param c1  first iterator
   * @param c2  second iterator
   * @return    iterator over the pair-wise sum of two argument iterators
   */
  public static Collection<Integer> sumInteger(Iterable<Integer> c1, Collection<Integer> c2) {
    return sumInteger(FnIterable.from(c1), FnIterable.from(c2)).toList();
  }

  /**
   * Pair-wise sum of two sequences of integers. Sequences should have the same
   * length.
   * <pre>
   *  sumInteger({a1, a2, a3, ...}, {b1, b2, b3, ...}) ==> {a1+b1, a2+b2, a3+b3, ...}
   * </pre>
   * 
   * @param c1  first sequence
   * @param c2  second sequence
   * @return    sequence of pair-wise sum of two argument sequences
   */
  public static FnIterable<Integer> sumInteger(FnIterable<Integer> c1, FnIterable<Integer> c2) {
    return c1.zip(c2).map(new Lambda<Pair<Integer, Integer>, Integer>() {
      @Override
      public Integer call(Pair<Integer, Integer> x) {
        return x.getFirst() + x.getSecond();
      }
    });
  }

  /**
   * @param c  a collection of longs
   * @return   sum of the collection of longs
   */
  public static Long sumLong(Iterable<Long> c) {
    return sumLong(FnIterable.from(c));
  }

  /**
   * @param it  a sequence of longs
   * @return    sum of the sequence of longs
   */
  public static Long sumLong(FnIterable<Long> it) {
    return it.reduce(new Lambda2<Long, Long, Long>() {
      @Override
      public Long call(Long arg0, Long arg1) {
        return arg0 + arg1;
      }
    }, 0l);
  }

  /**
   * Pair-wise sum of two iterators over longs. Iterators should have the same
   * length.
   * 
   * @param c1  first iterator
   * @param c2  second iterator
   * @return    iterator over the pair-wise sum of two argument iterators
   */
  public static Collection<Long> sumLong(Iterable<Long> c1, Iterable<Long> c2) {
    return sumLong(FnIterable.from(c1), FnIterable.from(c2)).toList();
  }

  /**
   * Pair-wise sum of two sequences of longs. Sequences should have the same
   * length.
   * <pre>
   *  sumLong({a1, a2, a3, ...}, {b1, b2, b3, ...}) ==> {a1+b1, a2+b2, a3+b3, ...}
   * </pre>
   * 
   * @param c1  first sequence
   * @param c2  second sequence
   * @return    sequence of pair-wise sum of two argument sequences
   */
  public static FnIterable<Long> sumLong(FnIterable<Long> c1, FnIterable<Long> c2) {
    return c1.zip(c2).map(new Lambda<Pair<Long, Long>, Long>() {
      @Override
      public Long call(Pair<Long, Long> x) {
        return x.getFirst() + x.getSecond();
      }
    });
  }

  /**
   * @param c  a collection of floats
   * @return   sum of the collection of floats
   */
  public static Float sumFloat(Iterable<Float> c) {
    return sumFloat(FnIterable.from(c));
  }

  /**
   * @param it  a sequence of floats
   * @return    sum of the sequence of floats
   */
  public static Float sumFloat(FnIterable<Float> it) {
    return it.reduce(new Lambda2<Float, Float, Float>() {
      @Override
      public Float call(Float arg0, Float arg1) {
        return arg0 + arg1;
      }
    }, 0.0f);
  }

  /**
   * Pair-wise sum of two iterators over floats. Iterators should have the same
   * length.
   * 
   * @param c1  first iterator
   * @param c2  second iterator
   * @return    iterator over the pair-wise sum of two argument iterators
   */
  public static Collection<Float> sumFloat(Iterable<Float> c1, Iterable<Float> c2) {
    return sumFloat(FnIterable.from(c1), FnIterable.from(c2)).toList();
  }

  /**
   * Pair-wise sum of two sequences of floats. Sequences should have the same
   * length.
   * <pre>
   *  sumFloat({a1, a2, a3, ...}, {b1, b2, b3, ...}) ==> {a1+b1, a2+b2, a3+b3, ...}
   * </pre>
   * 
   * @param c1  first sequence
   * @param c2  second sequence
   * @return    sequence of pair-wise sum of two argument sequences
   */
  public static FnIterable<Float> sumFloat(FnIterable<Float> c1, FnIterable<Float> c2) {
    return c1.zip(c2).map(new Lambda<Pair<Float, Float>, Float>() {
      @Override
      public Float call(Pair<Float, Float> x) {
        return x.getFirst() + x.getSecond();
      }
    });
  }
}
