package com.cortex.cortex_common.utils;

import java.util.Map;

import org.slf4j.MDC;

public class MdcUtils {

  public static Runnable withMDC(Runnable task) {
    Map<String, String> ctx = MDC.getCopyOfContextMap();
    return () -> {
      if (ctx != null)
        MDC.setContextMap(ctx);
      else
        MDC.clear();
      try {
        task.run();
      } finally {
        MDC.clear();
      }
    };
  }
}
