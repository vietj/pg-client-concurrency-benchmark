package com.julienviet.benchmark.jmh;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.julienviet.benchmark.PgBootstrap;
import io.reactiverse.pgclient.PgClient;
import io.reactiverse.pgclient.PgConnectOptions;
import io.reactiverse.pgclient.PgConnection;
import io.reactiverse.pgclient.PgPool;
import io.reactiverse.pgclient.PgPoolOptions;
import io.reactiverse.pgclient.PgPreparedQuery;
import io.reactiverse.pgclient.Tuple;
import io.vertx.core.Vertx;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.BenchmarkParams;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.profile.GCProfiler;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.postgresql.PGProperty;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class ClientBenchmark {

  @Param("")
  private String connectUri;

  @Param("1")
  private int pipelining;

  @Param("5000")
  int count;

  @Param("SELECT NOW()")
  String sql;

  PgConnectOptions options;
  Runnable tearDown;
  PreparedStatement preparedStatement;
  PgPreparedQuery pgPreparedQuery;
  int columns;
  Tuple emptyTuple;

  @Setup
  public void initClient(BenchmarkParams params) throws Exception {
    if (connectUri != null && !connectUri.isEmpty()) {
      options = PgConnectOptions.fromUri(connectUri);
    } else {
      options = PgBootstrap.startPg();
    }
    String benchmarkName = params.getBenchmark();
    //there is no need to specify a specific param for this one:
    //there isn't any third option for the client type!
    if (benchmarkName.contains("jdbc")) {
      Properties props = new Properties();
      PGProperty.PREPARE_THRESHOLD.set(props, -1);
      PGProperty.BINARY_TRANSFER.set(props, "true");
      PGProperty.USER.set(props, "postgres");
      PGProperty.PASSWORD.set(props, "postgres");
      Connection conn = DriverManager.getConnection("jdbc:postgresql://" + options.getHost() + ":" + options.getPort() + "/postgres", props);
      preparedStatement = conn.prepareStatement(sql);
      columns = preparedStatement.getMetaData().getColumnCount();
      tearDown = () -> {
        try {
          preparedStatement.close();
        } catch (Exception ex) {
          ex.printStackTrace();
        }
        try {
          conn.close();
        } catch (Exception ex) {
          ex.printStackTrace();
        }
      };
    } else {
      options.setPipeliningLimit(pipelining);
      Vertx vertx = Vertx.vertx();
      PgPool client = PgClient.pool(vertx, new PgPoolOptions(options));
      CompletableFuture<Void> result = new CompletableFuture<>();
      emptyTuple = Tuple.tuple();
      client.getConnection(ar1 -> {
        if (ar1.succeeded()) {
          final PgConnection pgConnection = ar1.result();
          pgConnection.prepare(sql, ar2 -> {
            if (ar2.succeeded()) {
              pgPreparedQuery = ar2.result();
              result.complete(null);
            } else {
              result.completeExceptionally(ar2.cause());
            }
          });
        } else {
          result.completeExceptionally(ar1.cause());
        }
      });
      result.get();
      tearDown = vertx::close;
    }
  }

  @Benchmark
  public void jdbcBurstQuery(Blackhole bh) throws SQLException {
    for (int i = 0; i < count; i++) {
      ResultSet rs = preparedStatement.executeQuery();
      while (rs.next()) {
        for (int column = 1; column <= columns; column++) {
          bh.consume(rs.getObject(column));
        }
      }
      rs.close();
    }
  }

  @Benchmark
  public void reactiveBurstQuery(Blackhole blackhole) throws ExecutionException, InterruptedException {
    final AtomicInteger remaining = new AtomicInteger(count);
    final PgPreparedQuery query = pgPreparedQuery;
    final CompletableFuture<Void> result = new CompletableFuture<>();
    for (int i = 0; i < Math.min(count, options.getPipeliningLimit()); i++) {
      execute(query, remaining, result, blackhole);
    }
    result.get();
  }

  private void execute(PgPreparedQuery query,
                       AtomicInteger remaining,
                       CompletableFuture<Void> result,
                       Blackhole blackhole) {
    int count = remaining.getAndDecrement();
    if (count == 0) {
      if (!result.isDone()) {
        result.complete(null);
      }
    } else if (count > 0) {
      query.execute(emptyTuple, ar3 -> {
        if (ar3.failed()) {
          if (!result.isDone()) {
            result.completeExceptionally(ar3.cause());
          }
        } else {
          //TODO consuming each column value for each row is a better option
          blackhole.consume(ar3.result());
          execute(query, remaining, result, blackhole);
        }
      });
    }
  }

  @TearDown
  public void close() {
    tearDown.run();
  }

  public static void main(String[] args) throws RunnerException {
    final Options opt = new OptionsBuilder()
      .include(ClientBenchmark.class.getSimpleName())
      .jvmArgs("-XX:BiasedLockingStartupDelay=0")
      .addProfiler(GCProfiler.class)
      .shouldDoGC(true).build();
    new Runner(opt).run();
  }

}
