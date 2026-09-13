package aspm.app.persistence;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import javax.sql.DataSource;

/**
 * An embedded engine with EVERY migration applied, in filename order, exactly as {@code apply.sh} does.
 * {@code OPS-DEP-029}, {@code OPS-DEP-031}, {@code CON-DAT-012}.
 *
 * <p>Two things this fixture asserts by existing. First, that the full migration set applies to an
 * empty database from scratch — the verification suite stops at V013 by a documented decision, and
 * {@code deploy/README.md} recorded "nothing asserts replay" as a known gap after V021's backfill failed
 * in a real deployment. Second, that a test of a new capability runs against the platform's own schema
 * rather than a hand-made imitation of it, so a CHECK the migration added is a CHECK the test meets.
 *
 * <p>The embedded binaries are PostgreSQL 17, so the test-only {@code uuidv7()} shim from
 * {@code :kernel-verification} is applied first, as there. The connection is the embedded superuser,
 * which BYPASSES row-level security: what this fixture verifies is schema and behaviour, not
 * isolation — that is the conformance job's and the kernel suite's business, and saying so here stops
 * a green test from being read as the isolation proof it is not.
 *
 * <p>Started once per JVM and shared; each test creates its own rows and cleans up nothing, because
 * every row it writes carries a fresh tenant or a fresh id.
 */
public final class AllMigrations {

    private static io.zonky.test.db.postgres.embedded.EmbeddedPostgres embedded;
    private static DataSource dataSource;
    private static List<String> applied;

    private AllMigrations() {
    }

    public static synchronized DataSource dataSource() {
        if (dataSource == null) {
            try {
                embedded = io.zonky.test.db.postgres.embedded.EmbeddedPostgres.builder().start();
                dataSource = embedded.getPostgresDatabase();
                applied = apply(dataSource);
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    try {
                        embedded.close();
                    } catch (IOException ignored) {
                        // Shutdown; nothing to report to.
                    }
                }));
            } catch (IOException | SQLException e) {
                throw new IllegalStateException("the embedded engine could not be started or migrated", e);
            }
        }
        return dataSource;
    }

    /** The migration filenames applied, in order — for a test that asserts the set is what it expects. */
    public static synchronized List<String> applied() {
        dataSource();
        return List.copyOf(applied);
    }

    private static List<String> apply(DataSource source) throws IOException, SQLException {
        Path corpusRoot = Path.of(System.getProperty("aspm.corpusRoot", ".."));
        Path src = corpusRoot.resolve("src");
        Path shim = src.resolve("kernel-verification/src/test/resources/db/testonly/V000__uuidv7_shim.sql");
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(src)) {
            walk.filter(p -> p.toString().contains("/src/main/resources/db/migration/")
                            && p.getFileName().toString().matches("V\\d{3}__.*\\.sql")
                            && !p.toString().contains("/build/"))
                    .forEach(files::add);
        }
        // Filename order, not path order — the same rule apply.sh states and the same defect it records.
        files.sort(Comparator.comparing(p -> p.getFileName().toString()));
        if (files.size() < 70) {
            throw new IllegalStateException("found only " + files.size() + " migrations under " + src
                    + "; the walk has stopped finding them rather than the platform having fewer");
        }
        List<String> names = new ArrayList<>();
        try (Connection c = source.getConnection(); Statement s = c.createStatement()) {
            s.execute(Files.readString(shim, StandardCharsets.UTF_8));
            int serverVersion;
            try (java.sql.ResultSet r = s.executeQuery("SHOW server_version_num")) {
                r.next();
                serverVersion = Integer.parseInt(r.getString(1));
            }
            for (Path file : files) {
                String sql = Files.readString(file, StandardCharsets.UTF_8);
                // The second and last test-only accommodation, beside the uuidv7 shim. ADR-049 floors the
                // deployment at PostgreSQL 18, where a NOT VALID foreign key may be added to a partitioned
                // table; the embedded binaries are 17, which refuses it. On an EMPTY database "add NOT
                // VALID, then VALIDATE" and "add" are the same constraint, so the qualifier is dropped
                // here and nowhere else. apply.sh never sees this code path, and the conformance job
                // against a real 18 is where V065 is verified as written.
                if (serverVersion < 180000 && file.getFileName().toString().startsWith("V065__")) {
                    sql = sql.replace(" NOT VALID", "");
                }
                try {
                    s.execute(sql);
                } catch (SQLException e) {
                    throw new SQLException("migration " + file.getFileName() + " failed on an empty database: "
                            + e.getMessage(), e);
                }
                names.add(file.getFileName().toString());
            }
        }
        return names;
    }
}
