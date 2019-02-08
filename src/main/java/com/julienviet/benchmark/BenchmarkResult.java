package com.julienviet.benchmark;

import io.vertx.core.AsyncResult;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

class BenchmarkResult {

  private CountDownLatch latch;
  private List<Throwable> failures;
  private int remaining;

  BenchmarkResult(int reps) {
    latch = new CountDownLatch(reps);
    failures = new ArrayList<>();
    remaining = reps;
  }

  synchronized boolean isFailed() {
    return failures.size() > 0;
  }

  synchronized void countDown(AsyncResult<?> res) {
    if (res.failed()) {
      failures.add(res.cause());
    }
    latch.countDown();
  }

  synchronized int failureCount() {
    return failures.size();
  }

  synchronized boolean shouldRun() {
    return remaining-- > 0;
  }

  void await() throws Exception {
    latch.await(2, TimeUnit.MINUTES);
  }
}
