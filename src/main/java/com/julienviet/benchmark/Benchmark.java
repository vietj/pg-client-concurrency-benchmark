/*
 * Copyright (C) 2017 Julien Viet
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.julienviet.benchmark;

import io.reactiverse.pgclient.PgConnectOptions;
import org.HdrHistogram.Histogram;
import picocli.CommandLine;

import java.sql.*;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

@CommandLine.Command(name = "example", mixinStandardHelpOptions = true, version = "Todo")
public class Benchmark implements Callable<Void> {

  public static void main(String[] args) {
    CommandLine.call(new Benchmark(), args);
  }

  @CommandLine.Option(names = { "--connect-uri" }, description = "The PostgreSQL connect uri.")
  private String connectUri;

  @CommandLine.Option(names = { "--pipelining" }, description = "The connection pipelining value (only applies for the Reactive PostgreSQL Client). Default is 1.")
  private int pipelining = 1;

  @CommandLine.Option(names = { "--client" }, description = "The client to run the benchmark with.")
  private Client client = Client.REACTIVE;

  @CommandLine.Option(names = { "--count" }, description = "The number of times the statement is executed.")
  int count = 5000;

  @CommandLine.Parameters(arity = "1", paramLabel = "SQL", description = "The SQL statement to execute.")
  String sql;

  PgConnectOptions options;
  Histogram histogram;

  @Override
  public Void call() throws Exception {

    // Connect URI
    if (connectUri != null) {
      options = PgConnectOptions.fromUri(connectUri);
    } else {
      System.out.println("Starting local database");
      options = PgBootstrap.startPg();
    }
    options.setPipeliningLimit(pipelining);

    histogram = new Histogram(TimeUnit.MINUTES.toNanos(1), 2);

    // Add DB warmup ???
    System.out.println("Starting benchmark using " + options.getHost() + ":" + options.getPort());
    long now = System.currentTimeMillis();
    CompletableFuture<?> fut = client.run(this);
    fut.get(2, TimeUnit.MINUTES);
    long elapsed = System.currentTimeMillis() - now;
    System.out.println("Time elapsed " + elapsed);
    System.out.println("min    = " + getMinResponseTimeMillis());
    System.out.println("max    = " + getMaxResponseTimeMillis());
    System.out.println("50%    = " + getResponseTimeMillisPercentile(50));
    System.out.println("90%    = " + getResponseTimeMillisPercentile(90));
    System.out.println("99%    = " + getResponseTimeMillisPercentile(99));
    System.out.println("99.9%  = " + getResponseTimeMillisPercentile(99.9));
    System.out.println("99.99% = " + getResponseTimeMillisPercentile(99.99));
    return null;
  }

  private long getMinResponseTimeMillis() {
    return TimeUnit.NANOSECONDS.toMillis(histogram.getMinValue());
  }

  private long getMaxResponseTimeMillis() {
    return TimeUnit.NANOSECONDS.toMillis(histogram.getMaxValue());
  }

  private long getResponseTimeMillisPercentile(double x) {
    return TimeUnit.NANOSECONDS.toMillis(histogram.getValueAtPercentile(x));
  }

  // select id, randomnumber from WORLD where id=1
//    largeSelectJDBC(options, 5_000);
//    largeSelect(options, 5_000);
//    largePipelinedSelect(options, 5_000);
//    singleSelectJDBC(options, 200_000);
//    singleSelect(options, 200_000);
//    singlePipelinedSelect(options, 200_000);
}
