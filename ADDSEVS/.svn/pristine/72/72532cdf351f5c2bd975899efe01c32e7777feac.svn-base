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

File: SystemProperties.java 

*/



package util;

/**
 * Utility class to deal with retrieving properties from System.
 */
public class SystemProperties {
  /**
   * Returns the enum value associated with the given system property or the
   * default if the property was unset or set to an unparseable value.
   *
   * @param name Name of the System.getProperty() property to fetch
   * @param def  Default value if property not found
   * @return The value associated with the property or the default value
   */
  public static <T extends Enum<T>> T getEnumProperty(String name, Class<T> enumType, T def) {
    String value = System.getProperty(name);
    if (value == null) {
      return def;
    }
    try {
      return Enum.valueOf(enumType, value);
    } catch (IllegalArgumentException _) {
      return def;
    }
  }

  /**
   * Returns the integer value associated with the given system property, or the
   * default if the property was unset or set to an unparseable string.
   *
   * @param name Name of the System.getProperty() property to fetch
   * @param def  Default value if property not found
   * @return The value associated with the property or the default value
   */
  public static int getIntProperty(String name, int def) {
    return Integer.getInteger(name, def);
  }

  /**
   * Returns the long value associated with the given system property, or the
   * default if the property was unset or set to an unparseable string.
   *
   * @param name Name of the System.getProperty() property to fetch
   * @param def  Default value if property not found
   * @return The value associated with the property or the default value
   */
  public static long getLongProperty(String name, long def) {
    return Long.getLong(name, def);
  }

  /**
   * Returns the double value associated with the given system property, or the
   * default if the property was unset or set to an unparseable string.
   *
   * @param name Name of the System.getProperty() property to fetch
   * @param def  Default value if property not found
   * @return The value associated with the property or the default value
   */
  public static double getDoubleProperty(String name, double def) {
    String value = System.getProperty(name);
    if (value == null) {
      return def;
    }
    try {
      return Double.parseDouble(value);
    } catch (NumberFormatException _) {
      return def;
    }
  }

  /**
   * Returns the boolean value associated with the given system property, or the
   * default if the property was unset or a string other than "true" or "false."
   *
   * @param name Name of the System.getProperty() property to fetch
   * @param def  Default value if property not found
   * @return The value associated with the property or the default value
   */
  public static boolean getBooleanProperty(String name, boolean def) {
    String value = System.getProperty(name);
    if (value == null) {
      return def;
    }
    if (value.equalsIgnoreCase("true")) {
      return true;
    }
    if (value.equalsIgnoreCase("false")) {
      return false;
    }
    return def;
  }
}
