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

File: LineMapper.java 

*/



package galois.objects;

import galois.runtime.MapInternalContext;
import galois.runtime.IterationAbortException;

import java.io.BufferedReader;
import java.io.IOException;

import util.fn.Lambda2Void;
import util.fn.Lambda3Void;
import util.fn.LambdaVoid;

/**
 * Mappable interface to files, iterating over all the lines in the file.
 * 
 *
 */
class LineMapper implements Mappable<String> {
  private BufferedReader reader;

  public LineMapper(BufferedReader reader) {
    this.reader = reader;
  }

  @Override
  public final void map(LambdaVoid<String> body) {
    map(body, MethodFlag.ALL);
  }

  @Override
  public void map(LambdaVoid<String> body, byte flags) {
    String line;
    try {
      while ((line = reader.readLine()) != null) {
        body.call(line);
      }
      reader.close();
    } catch (IOException e) {
      throw new Error(e);
    }
  }

  @Override
  public final <A1> void map(Lambda2Void<String, A1> body, A1 arg1) {
    map(body, arg1, MethodFlag.ALL);
  }

  @Override
  public <A1> void map(Lambda2Void<String, A1> body, A1 arg1, byte flags) {
    String line;
    try {
      while ((line = reader.readLine()) != null) {
        body.call(line, arg1);
      }
      reader.close();
    } catch (IOException e) {
      throw new Error(e);
    }
  }

  @Override
  public final <A1, A2> void map(Lambda3Void<String, A1, A2> body, A1 arg1, A2 arg2) {
    map(body, arg1, arg2, MethodFlag.ALL);
  }

  @Override
  public <A1, A2> void map(Lambda3Void<String, A1, A2> body, A1 arg1, A2 arg2, byte flags) {
    String line;
    try {
      while ((line = reader.readLine()) != null) {
        body.call(line, arg1, arg2);
      }
      reader.close();
    } catch (IOException e) {
      throw new Error(e);
    }
  }

  @Override
  public synchronized void mapInternal(LambdaVoid<String> body, MapInternalContext ctx) {
    String line;
    try {
      while ((line = reader.readLine()) != null) {
        while (true) {
          try {
            ctx.begin();
            body.call(line);
            ctx.commit(line);
            break;
          } catch (IterationAbortException _) {
            ctx.abort();
          }
        }
      }
    } catch (IOException e) {
      throw new Error(e);
    }
  }

  @Override
  public synchronized <A1> void mapInternal(Lambda2Void<String, A1> body, MapInternalContext ctx, A1 arg1) {
    String line;
    try {
      while ((line = reader.readLine()) != null) {
        while (true) {
          try {
            ctx.begin();
            body.call(line, arg1);
            ctx.commit(line);
            break;
          } catch (IterationAbortException _) {
            ctx.abort();
          }
        }
      }
    } catch (IOException e) {
      throw new Error(e);
    }
  }

  @Override
  public synchronized <A1, A2> void mapInternal(Lambda3Void<String, A1, A2> body, MapInternalContext ctx, A1 arg1,
      A2 arg2) {
    String line;
    try {
      while ((line = reader.readLine()) != null) {
        while (true) {
          try {
            ctx.begin();
            body.call(line, arg1, arg2);
            ctx.commit(line);
            break;
          } catch (IterationAbortException _) {
            ctx.abort();
          }
        }
      }
    } catch (IOException e) {
      throw new Error(e);
    }
  }

  @Override
  public void mapInternalDone() {
    try {
      reader.close();
    } catch (IOException e) {
      throw new Error(e);
    }
  }
}
