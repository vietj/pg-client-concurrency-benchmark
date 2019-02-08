package com.julienviet.benchmark;

import de.flapdoodle.embed.process.config.IRuntimeConfig;
import de.flapdoodle.embed.process.config.io.ProcessOutput;
import de.flapdoodle.embed.process.runtime.ICommandLinePostProcessor;
import de.flapdoodle.embed.process.store.IArtifactStore;
import io.reactiverse.pgclient.PgConnectOptions;
import ru.yandex.qatools.embed.postgresql.EmbeddedPostgres;
import ru.yandex.qatools.embed.postgresql.distribution.Version;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static ru.yandex.qatools.embed.postgresql.distribution.Version.V10_6;
import static ru.yandex.qatools.embed.postgresql.distribution.Version.V11_1;
import static ru.yandex.qatools.embed.postgresql.distribution.Version.V9_6_11;

public class PgBootstrap {

  private static final Map<String, Version> supportedPgVersions = new HashMap<>();

  private static EmbeddedPostgres postgres;
  protected static PgConnectOptions options;

  static {
    supportedPgVersions.put("9.6", V9_6_11);
    supportedPgVersions.put("10.6", V10_6);
    supportedPgVersions.put("11.1", V11_1);
  }

  private static Version getPostgresVersion() {
    String specifiedVersion = System.getProperty("embedded.postgres.version");
    Version version;
    if (specifiedVersion == null || specifiedVersion.isEmpty()) {
      // if version is not specified then V10 will be used by default
      version = V10_6;
    } else {
      version = supportedPgVersions.get(specifiedVersion);
    }
    if (version == null) {
      throw new IllegalArgumentException("embedded postgres only supports the following versions: " + supportedPgVersions.keySet().toString() + "instead of " + specifiedVersion);
    }
    return version;
  }

  public synchronized static PgConnectOptions startPg() throws Exception {
    return startPg(false, false);
  }

  public synchronized static PgConnectOptions startPg(boolean domainSockets, boolean ssl) throws Exception {
    if (domainSockets && ssl) {
      throw new IllegalArgumentException("ssl should be disabled when testing with Unix domain socket");
    }
    if (postgres != null) {
      throw new IllegalStateException();
    }
    IRuntimeConfig config;
    String a = System.getProperty("target.dir", "target");
    File targetDir = new File(a);
    if (targetDir.exists() && targetDir.isDirectory()) {
      config = EmbeddedPostgres.cachedRuntimeConfig(targetDir.toPath());
    } else {
      throw new AssertionError("Cannot access target dir");
    }

    // SSL
    if (ssl) {
      config = useSSLRuntimeConfig(config);
    }

    // Domain sockets
    File sock;
    if (domainSockets) {
      sock = Files.createTempFile(targetDir.toPath(), "pg_", ".sock").toFile();
      assertTrue(sock.delete());
      assertTrue(sock.mkdir());
      Files.setPosixFilePermissions(sock.toPath(), new HashSet<>(Arrays.asList(
        PosixFilePermission.OWNER_EXECUTE,
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE,
        PosixFilePermission.GROUP_EXECUTE,
        PosixFilePermission.GROUP_READ,
        PosixFilePermission.GROUP_WRITE
      )));
      config = useDomainSocketRunTimeConfig(config, sock);
    } else {
      sock = null;
    }

    postgres = new EmbeddedPostgres(getPostgresVersion());
    postgres.start(config,
      "localhost",
      8081,
      "postgres",
      "postgres",
      "postgres",
      Collections.emptyList());
    File setupFile = getTestResource("create-postgres.sql");
    postgres.getProcess().get().importFromFile(setupFile);
    PgConnectOptions options = new PgConnectOptions();
    options.setHost(domainSockets ? sock.getAbsolutePath() : "localhost");
    options.setPort(8081);
    options.setUser("postgres");
    options.setPassword("postgres");
    options.setDatabase("postgres");
    return options;
  }

  // ssl=on just enables the possibility of using SSL which does not force clients to use SSL
  private static IRuntimeConfig useSSLRuntimeConfig(IRuntimeConfig config) throws Exception {
    File sslKey = getTestResource("server.key");
    Files.setPosixFilePermissions(sslKey.toPath(), Collections.singleton(PosixFilePermission.OWNER_READ));
    File sslCrt = getTestResource("server.crt");

    return new RunTimeConfigBase(config) {
      @Override
      public ICommandLinePostProcessor getCommandLinePostProcessor() {
        ICommandLinePostProcessor commandLinePostProcessor = config.getCommandLinePostProcessor();
        return (distribution, args) -> {
          List<String> result = commandLinePostProcessor.process(distribution, args);
          if (result.get(0).endsWith("postgres")) {
            result = new ArrayList<>(result);
            result.add("--ssl=on");
            result.add("--ssl_cert_file=" + sslCrt.getAbsolutePath());
            result.add("--ssl_key_file=" + sslKey.getAbsolutePath());
          }
          return result;
        };
      }
    };
  }

  private static void assertTrue(boolean test) {
    if (!test) {
      throw new AssertionError();
    }
  }

  private static File getTestResource(String name) throws Exception {
    InputStream in = new FileInputStream(new File("docker" + File.separator + "postgres" + File.separator + "resources" + File.separator + name));
    Path path = Files.createTempFile("pg-client", ".tmp");
    Files.copy(in, path, StandardCopyOption.REPLACE_EXISTING);
    File file = path.toFile();
    file.deleteOnExit();
    return file;
  }

  private static IRuntimeConfig useDomainSocketRunTimeConfig(IRuntimeConfig config, File sock) throws Exception {
    return new RunTimeConfigBase(config) {
      @Override
      public ICommandLinePostProcessor getCommandLinePostProcessor() {
        ICommandLinePostProcessor commandLinePostProcessor = config.getCommandLinePostProcessor();
        return (distribution, args) -> {
          List<String> result = commandLinePostProcessor.process(distribution, args);
          if (result.get(0).endsWith("postgres")) {
            result = new ArrayList<>(result);
            result.add("--unix_socket_directories=" + sock.getAbsolutePath());
          }
          return result;
        };
      }
    };
  }

  private static abstract class RunTimeConfigBase implements IRuntimeConfig {
    private final IRuntimeConfig config;

    private RunTimeConfigBase(IRuntimeConfig config) {
      this.config = config;
    }

    @Override
    public ProcessOutput getProcessOutput() {
      return config.getProcessOutput();
    }

    @Override
    public IArtifactStore getArtifactStore() {
      return config.getArtifactStore();
    }

    @Override
    public boolean isDaemonProcess() {
      return config.isDaemonProcess();
    }
  }
}
