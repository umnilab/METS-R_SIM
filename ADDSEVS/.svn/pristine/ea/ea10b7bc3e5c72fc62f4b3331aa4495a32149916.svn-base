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

File: MutableInteger.java 

 */

package util;

/**
 * Object wrapper around an integer
 *
 *
 */
public class MutableInteger {
  private int value;

  /**
   * Creates a new mutable integer with value 0
   */
  public MutableInteger() {
    value = 0;
  }

  /**
   * Creates a new mutable integer with the given value
   * 
   * @param value  initial value
   */
  public MutableInteger(int value) {
    this.value = value;
  }

  public int get() {
    return value;
  }

  public void set(int value) {
    this.value = value;
  }

  public int incrementAndGet() {
    return ++value;
  }

  public int getAndIncrement() {
    return value++;
  }

  public void add(int delta) {
    value += delta;
  }

  public int decrementAndGet() {
    return --value;
  }

  public int getAndDecrement() {
    return value--;
  }

  @Override
  public String toString() {
    return String.valueOf(value);
  }
}
