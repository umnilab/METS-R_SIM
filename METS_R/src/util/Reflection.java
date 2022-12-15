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

File: Reflection.java 

*/



package util;

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Helper methods for the package java.lang.reflect
 */
public class Reflection {
  /**
   * Tests whether the package a class belongs to contains a given annotation.
   * Annotations in packages are not automatically added by the compiler to the classes
   * belonging to the package.
   *
   * @param className       the fully qualified name of the class
   * @param annotationClass annotation in question
   * @return true if the package to which the class belongs has been marked with
   *         the given annotation.
   */
  public static boolean packageContainsAnnotation(String className, Class<? extends Annotation> annotationClass) {
    return getPackageAnnotation(className, annotationClass) != null;
  }

  public static <A extends Annotation> A getPackageAnnotation(String className, Class<A> annotationClass) {
    Class<?> classClass = null;
    try {
      classClass = Class.forName(className);
    } catch (ClassNotFoundException e) {
      throw new RuntimeException(e);
    }
    Package classPackage = Package.getPackage(classClass.getPackage().getName());
    return classPackage.getAnnotation(annotationClass);
  }

  public static boolean containsAnnotation(String className, Class<? extends Annotation> annotationClass) {
    return getAnnotation(className, annotationClass) != null;
  }

  public static <A extends Annotation> A getAnnotation(String className, Class<A> annotationClass) {
    Class<?> classClass = null;
    try {
      classClass = Class.forName(className);
    } catch (ClassNotFoundException e) {
      throw new RuntimeException(e);
    }
    return (A) classClass.getAnnotation(annotationClass);
  }

  /**
   * Creates an object of the given class, using the default constructor
   * 
   * @param className the fully qualified name of the class
   * @return          an instance of the class
   */
  public static Object createObject(String className) {
    Object object = null;
    try {
      Class<?> classDefinition = Class.forName(className);
      object = classDefinition.newInstance();
    } catch (Exception ex) {
      throw new RuntimeException(ex);
    }
    return object;
  }

  /**
   * Invokes a instance method (of any visibility) of a given object.
   * If there is more than one method with the given name in the class
   * the instance belongs to, we invoke any of them in a non-deterministic
   * fashion.
   *
   * @param o          object in which to invoke the given method
   * @param methodName the name of the method to invoke
   * @param params     actual parameters
   * @return Object    return value of callee
   */
  public static Object invokeMethod(Object o, String methodName, Object[] params) {
    String className = o.getClass().getName();
    List<Object> actualParams = new ArrayList<Object>(Arrays.asList(params));
    actualParams.add(0, o);
    return invokeStaticMethod(className, methodName, actualParams.toArray());
  }

  /**
   * Invokes a static method (of any visibility) of a given class.
   * If there is more than one static method with the given name in the class,
   * we invoke one of them in non-deterministic fashion.
   *
   * @param className  the fully qualified name of the class
   * @param methodName the name of the method to invoke
   * @param params     actual parameters
   * @return Object    return value of callee
   */
  public static Object invokeStaticMethod(String className, String methodName, Object[] params) {
    Class<?> c = null;
    try {
      c = Class.forName(className);
    } catch (ClassNotFoundException e) {
      throw new RuntimeException(e);
    }
    final Method methods[] = c.getDeclaredMethods();
    for (Method method : methods) {
      if (methodName.equals(method.getName())) {
        try {
          method.setAccessible(true);
          return method.invoke(null, params);
        } catch (IllegalAccessException ex) {
          throw new RuntimeException(ex);
        } catch (InvocationTargetException ite) {
          throw new RuntimeException(ite);
        }
      }
    }
    throw new RuntimeException("Method '" + methodName + "' not found");
  }
}
