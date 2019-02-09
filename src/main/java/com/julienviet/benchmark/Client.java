package com.julienviet.benchmark;

import io.reactiverse.pgclient.PgClient;
import io.reactiverse.pgclient.PgConnectOptions;
import io.reactiverse.pgclient.PgConnection;
import io.reactiverse.pgclient.PgPool;
import io.reactiverse.pgclient.PgPoolOptions;
import io.reactiverse.pgclient.PgPreparedQuery;
import io.reactiverse.pgclient.Tuple;
import io.vertx.core.Vertx;
import org.HdrHistogram.Histogram;
import org.postgresql.PGProperty;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

public enum Client {

  JDBC() {
    @Override
    CompletableFuture<Void> run(Benchmark benchmark) {
      return run(benchmark.options, benchmark.sql, benchmark.count, benchmark.histogram);
    }

    private CompletableFuture<Void> run(PgConnectOptions options, String sql, int count, Histogram histogram) {
      CompletableFuture<Void> result = new CompletableFuture<>();
      try {
        Properties props = new Properties();
        PGProperty.PREPARE_THRESHOLD.set(props, -1);
        PGProperty.BINARY_TRANSFER.set(props, "true");
        PGProperty.USER.set(props, options.getUser());
        PGProperty.PASSWORD.set(props, options.getPassword());
        Connection conn = DriverManager.getConnection("jdbc:postgresql://"
          + options.getHost() + ":"
          + options.getPort() + "/" + options.getDatabase(), props);
        PreparedStatement ps = conn.prepareStatement(sql);
        for (int i = 0;i < count;i++) {
          long now = System.nanoTime();
          ResultSet rs = ps.executeQuery();
          while (rs.next()) {
            int size = rs.getMetaData().getColumnCount();
            for (int column = 1;column <= size;column++) {
              rs.getObject(column);
            }
          }
          rs.close();
          long elapsed = System.nanoTime() - now;
          histogram.recordValue(elapsed);
        }
        result.complete(null);
      } catch (SQLException e) {
        result.completeExceptionally(e);
      }
      return result;
    }
  },

  REACTIVE() {
    @Override
    CompletableFuture<Void> run(Benchmark benchmark) {
      return run(benchmark.options, benchmark.count, benchmark.sql, benchmark.histogram);
    }

    private final Tuple EMPTY_TUPLE = Tuple.tuple();

    private CompletableFuture<Void> run(PgConnectOptions options, int count, String sql, Histogram histogram) {
      Vertx vertx = Vertx.vertx();
      PgPool client = PgClient.pool(vertx, new PgPoolOptions()
        .setHost(options.getHost())
        .setPort(options.getPort())
        .setDatabase(options.getDatabase())
        .setUser(options.getUser())
        .setPassword(options.getPassword())
        .setCachePreparedStatements(true)
      );
      CompletableFuture<Void> result = new CompletableFuture<>();
      client.getConnection(ar1 -> {
        if (ar1.succeeded()) {
          PgConnection conn = ar1.result();
          result.whenComplete((v, err) -> {
            conn.close();
          });
          conn.prepare(sql, ar2 -> {
            if (ar2.succeeded()) {
              AtomicInteger remaining = new AtomicInteger(count);
              PgPreparedQuery query = ar2.result();
              for (int i = 0;i < Math.min(count, options.getPipeliningLimit());i++) {
                execute(query, remaining, result, histogram);
              }
            } else {
              result.completeExceptionally(ar2.cause());
            }
          });
        } else {
          result.completeExceptionally(ar1.cause());
        }
      });
      return result;
    }

    private void execute(PgPreparedQuery query, AtomicInteger remaining, CompletableFuture<Void> result, Histogram histogram) {
      int count = remaining.getAndDecrement();
      if (count == 0) {
        if (!result.isDone()) {
          result.complete(null);
        }
      } else if (count > 0) {
        long now = System.nanoTime();
        query.execute(EMPTY_TUPLE, ar3 -> {
          if (ar3.failed()) {
            if (!result.isDone()) {
              result.completeExceptionally(ar3.cause());
            }
          } else {
            long elapsed = System.nanoTime() - now;
            histogram.recordValue(elapsed);
            execute(query, remaining, result, histogram);
          }
        });
      }
    }
  }

  ;

  abstract CompletableFuture<Void> run(Benchmark benchmark) throws Exception;


}
