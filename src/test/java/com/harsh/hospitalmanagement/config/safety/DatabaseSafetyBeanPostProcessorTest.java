package com.harsh.hospitalmanagement.config.safety;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseSafetyBeanPostProcessorTest {

    private DatabaseSafetyBeanPostProcessor postProcessor;

    @BeforeEach
    void setUp() {
        postProcessor = new DatabaseSafetyBeanPostProcessor();
    }

    @Test
    @DisplayName("Hikari wrapping another Hikari targeting forbidden database is rejected")
    void rejectNestedHikariTargetingForbiddenDatabase() {
        HikariDataSource inner = new HikariDataSource();
        inner.setJdbcUrl("jdbc:postgresql://localhost:5433/hospital_management");

        HikariDataSource outer = new HikariDataSource();
        outer.setDataSource(inner);

        assertThatThrownBy(() -> postProcessor.postProcessBeforeInitialization(outer, "outerHikari"))
                .isInstanceOf(DatabaseSafetyException.class)
                .hasMessageContaining("hospital_management");
    }

    @Test
    @DisplayName("Allowed outer Hikari with innocent URL wrapping forbidden inner Hikari is rejected")
    void rejectAllowedOuterWithForbiddenInnerDataSource() {
        HikariDataSource inner = new HikariDataSource();
        inner.addDataSourceProperty("databaseName", "hospital_management");
        inner.addDataSourceProperty("serverName", "127.0.0.1");
        inner.addDataSourceProperty("portNumber", "5433");

        HikariDataSource outer = new HikariDataSource();
        outer.setJdbcUrl("jdbc:postgresql://localhost:5433/hms_dev_innocent");
        outer.setDataSource(inner);

        assertThatThrownBy(() -> postProcessor.postProcessBeforeInitialization(outer, "outerHikari"))
                .isInstanceOf(DatabaseSafetyException.class)
                .hasMessageContaining("hospital_management");
    }

    @Test
    @DisplayName("Hikari wrapping another Hikari with safe URL passes validation")
    void acceptNestedHikariWithSafeTarget() {
        HikariDataSource inner = new HikariDataSource();
        inner.setJdbcUrl("jdbc:postgresql://localhost:5433/hms_dev_nested_safe");

        HikariDataSource outer = new HikariDataSource();
        outer.setDataSource(inner);

        assertThatCode(() -> postProcessor.postProcessBeforeInitialization(outer, "outerHikari"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Circular DataSource references are detected and rejected via identity cycle detection")
    void rejectCircularDataSourceReferences() {
        CircularDataSource ds1 = new CircularDataSource("ds1");
        CircularDataSource ds2 = new CircularDataSource("ds2");
        ds1.setTarget(ds2);
        ds2.setTarget(ds1);

        assertThatThrownBy(() -> postProcessor.postProcessBeforeInitialization(ds1, "circularDs1"))
                .isInstanceOf(DatabaseSafetyException.class)
                .hasMessageContaining("Circular DataSource reference detected");
    }

    @Test
    @DisplayName("Supported safe DataSource wrappers (DelegatingDataSource) resolve and pass")
    void acceptDelegatingDataSourceWithSafeTarget() {
        HikariDataSource inner = new HikariDataSource();
        inner.setJdbcUrl("jdbc:postgresql://localhost:5433/hms_dev_delegating_safe");

        DelegatingDataSource delegating = new DelegatingDataSource(inner);

        assertThatCode(() -> postProcessor.postProcessBeforeInitialization(delegating, "delegatingDs"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Unresolvable DataSource target fails closed")
    void rejectUnresolvableDataSourceTarget() {
        DataSource unresolvable = createDummyDataSource(null);

        assertThatThrownBy(() -> postProcessor.postProcessBeforeInitialization(unresolvable, "unresolvableDs"))
                .isInstanceOf(DatabaseSafetyException.class)
                .hasMessageContaining("could not be statically validated");
    }

    @Test
    @DisplayName("Class-name heuristic is removed: FakeEmbeddedDataSource without safe URL is rejected")
    void rejectFakeEmbeddedDatabaseWithoutSafeUrl() {
        DataSource fakeEmbedded = new FakeEmbeddedDataSource();

        assertThatThrownBy(() -> postProcessor.postProcessBeforeInitialization(fakeEmbedded, "fakeEmbeddedDs"))
                .isInstanceOf(DatabaseSafetyException.class)
                .hasMessageContaining("could not be statically validated");
    }

    @Test
    @DisplayName("Real H2 in-memory DataSource resolves and passes")
    void acceptRealH2MemDataSource() {
        HikariDataSource h2 = new HikariDataSource();
        h2.setJdbcUrl("jdbc:h2:mem:hms_test_unit;DB_CLOSE_DELAY=-1;MODE=PostgreSQL");

        assertThatCode(() -> postProcessor.postProcessBeforeInitialization(h2, "h2MemDs"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("inspectGenerateDdl reflectively accesses protected isGenerateDdl on AbstractJpaVendorAdapter")
    void inspectGenerateDdlReflectiveAccess() {
        HibernateJpaVendorAdapter adapter = new HibernateJpaVendorAdapter();

        adapter.setGenerateDdl(false);
        assertThat(DatabaseSafetyBeanPostProcessor.inspectGenerateDdl(adapter)).isFalse();

        adapter.setGenerateDdl(true);
        assertThat(DatabaseSafetyBeanPostProcessor.inspectGenerateDdl(adapter)).isTrue();
    }

    @Test
    @DisplayName("EntityManagerFactory with generateDdl=true on PostgreSQL connection is strictly rejected")
    void rejectEntityManagerFactoryWithGenerateDdlTrueOnPostgreSQL() {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl("jdbc:postgresql://localhost:5433/hms_dev_test");

        HibernateJpaVendorAdapter adapter = new HibernateJpaVendorAdapter();
        adapter.setGenerateDdl(true);

        LocalContainerEntityManagerFactoryBean emf = new LocalContainerEntityManagerFactoryBean();
        emf.setDataSource(ds);
        emf.setJpaVendorAdapter(adapter);

        assertThatThrownBy(() -> postProcessor.postProcessBeforeInitialization(emf, "entityManagerFactory"))
                .isInstanceOf(DatabaseSafetyException.class)
                .hasMessageContaining("CRITICAL SCHEMA SAFETY VIOLATION")
                .hasMessageContaining("isGenerateDdl() is true for PostgreSQL connection");
    }

    @Test
    @DisplayName("EntityManagerFactory with generateDdl=false on PostgreSQL with validate passes")
    void acceptEntityManagerFactoryWithGenerateDdlFalseOnPostgreSQL() {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl("jdbc:postgresql://localhost:5433/hms_dev_test");

        HibernateJpaVendorAdapter adapter = new HibernateJpaVendorAdapter();
        adapter.setGenerateDdl(false);

        LocalContainerEntityManagerFactoryBean emf = new LocalContainerEntityManagerFactoryBean();
        emf.setDataSource(ds);
        emf.setJpaVendorAdapter(adapter);
        Map<String, Object> props = new HashMap<>();
        props.put("hibernate.hbm2ddl.auto", "validate");
        emf.setJpaPropertyMap(props);

        assertThatCode(() -> postProcessor.postProcessBeforeInitialization(emf, "entityManagerFactory"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Outer Hikari with decoy H2 URL wrapping underlying PostgreSQL DataSource is rejected when generateDdl=true")
    void rejectDecoyH2UrlWithUnderlyingPostgresDataSourceWhenGenerateDdlTrue() {
        HikariDataSource inner = new HikariDataSource();
        inner.setJdbcUrl("jdbc:postgresql://localhost:5433/hms_dev_test");

        HikariDataSource outer = new HikariDataSource();
        outer.setJdbcUrl("jdbc:h2:mem:decoy_db;DB_CLOSE_DELAY=-1");
        outer.setDataSource(inner);

        HibernateJpaVendorAdapter adapter = new HibernateJpaVendorAdapter();
        adapter.setGenerateDdl(true);

        LocalContainerEntityManagerFactoryBean emf = new LocalContainerEntityManagerFactoryBean();
        emf.setDataSource(outer);
        emf.setJpaVendorAdapter(adapter);

        assertThatThrownBy(() -> postProcessor.postProcessBeforeInitialization(emf, "entityManagerFactory"))
                .isInstanceOf(DatabaseSafetyException.class)
                .hasMessageContaining("CRITICAL SCHEMA SAFETY VIOLATION")
                .hasMessageContaining("isGenerateDdl() is true for PostgreSQL connection");
    }

    @Test
    @DisplayName("Outer Hikari with decoy H2 URL and postgres dataSourceClassName is rejected when generateDdl=true")
    void rejectDecoyH2UrlWithPostgresDataSourceClassNameWhenGenerateDdlTrue() {
        HikariDataSource outer = new HikariDataSource();
        outer.setJdbcUrl("jdbc:h2:mem:decoy_db;DB_CLOSE_DELAY=-1");
        outer.setDataSourceClassName("org.postgresql.ds.PGSimpleDataSource");
        outer.addDataSourceProperty("databaseName", "hms_dev_test");
        outer.addDataSourceProperty("serverName", "127.0.0.1");
        outer.addDataSourceProperty("portNumber", "5433");

        HibernateJpaVendorAdapter adapter = new HibernateJpaVendorAdapter();
        adapter.setGenerateDdl(true);

        LocalContainerEntityManagerFactoryBean emf = new LocalContainerEntityManagerFactoryBean();
        emf.setDataSource(outer);
        emf.setJpaVendorAdapter(adapter);

        assertThatThrownBy(() -> postProcessor.postProcessBeforeInitialization(emf, "entityManagerFactory"))
                .isInstanceOf(DatabaseSafetyException.class)
                .hasMessageContaining("CRITICAL SCHEMA SAFETY VIOLATION");
    }

    @Test
    @DisplayName("EntityManagerFactory with generateDdl=true on H2 in-memory is permitted for testing")
    void acceptEntityManagerFactoryWithGenerateDdlTrueOnH2Mem() {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl("jdbc:h2:mem:hms_test_db;DB_CLOSE_DELAY=-1;MODE=PostgreSQL");

        HibernateJpaVendorAdapter adapter = new HibernateJpaVendorAdapter();
        adapter.setGenerateDdl(true);

        LocalContainerEntityManagerFactoryBean emf = new LocalContainerEntityManagerFactoryBean();
        emf.setDataSource(ds);
        emf.setJpaVendorAdapter(adapter);

        assertThatCode(() -> postProcessor.postProcessBeforeInitialization(emf, "entityManagerFactory"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("DelegatingDataSource subclass with decoy H2 URL wrapping Hikari with PGSimpleDataSource is rejected when generateDdl=true")
    void rejectDelegatingDataSourceWithDecoyH2UrlWrappingPostgresHikariWhenGenerateDdlTrue() {
        HikariDataSource inner = new HikariDataSource();
        inner.setDataSourceClassName("org.postgresql.ds.PGSimpleDataSource");
        inner.addDataSourceProperty("databaseName", "hms_dev_test");
        inner.addDataSourceProperty("serverName", "127.0.0.1");
        inner.addDataSourceProperty("portNumber", "5433");

        DecoyDelegatingDataSource wrapper = new DecoyDelegatingDataSource(inner);

        HibernateJpaVendorAdapter adapter = new HibernateJpaVendorAdapter();
        adapter.setGenerateDdl(true);

        LocalContainerEntityManagerFactoryBean emf = new LocalContainerEntityManagerFactoryBean();
        emf.setDataSource(wrapper);
        emf.setJpaVendorAdapter(adapter);

        assertThatThrownBy(() -> postProcessor.postProcessBeforeInitialization(emf, "entityManagerFactory"))
                .isInstanceOf(DatabaseSafetyException.class)
                .hasMessageContaining("CRITICAL SCHEMA SAFETY VIOLATION")
                .hasMessageContaining("isGenerateDdl() is true for PostgreSQL connection");
    }

    // Helper classes

    static class DecoyDelegatingDataSource extends DelegatingDataSource {
        public DecoyDelegatingDataSource(DataSource target) {
            super(target);
        }

        public String getJdbcUrl() {
            return "jdbc:h2:mem:decoy_db;DB_CLOSE_DELAY=-1";
        }

        public String getUrl() {
            return "jdbc:h2:mem:decoy_db;DB_CLOSE_DELAY=-1";
        }
    }

    static class CircularDataSource implements DataSource {
        private final String name;
        private DataSource target;

        public CircularDataSource(String name) {
            this.name = name;
        }

        public void setTarget(DataSource target) {
            this.target = target;
        }

        public DataSource getTargetDataSource() {
            return this.target;
        }

        @Override
        public Connection getConnection() { return null; }
        @Override
        public Connection getConnection(String u, String p) { return null; }
        @Override
        public PrintWriter getLogWriter() { return null; }
        @Override
        public void setLogWriter(PrintWriter out) {}
        @Override
        public void setLoginTimeout(int seconds) {}
        @Override
        public int getLoginTimeout() { return 0; }
        @Override
        public Logger getParentLogger() { return null; }
        @Override
        public <T> T unwrap(Class<T> iface) { return null; }
        @Override
        public boolean isWrapperFor(Class<?> iface) { return false; }
    }

    static class FakeEmbeddedDataSource implements DataSource {
        @Override
        public Connection getConnection() { return null; }
        @Override
        public Connection getConnection(String u, String p) { return null; }
        @Override
        public PrintWriter getLogWriter() { return null; }
        @Override
        public void setLogWriter(PrintWriter out) {}
        @Override
        public void setLoginTimeout(int seconds) {}
        @Override
        public int getLoginTimeout() { return 0; }
        @Override
        public Logger getParentLogger() { return null; }
        @Override
        public <T> T unwrap(Class<T> iface) { return null; }
        @Override
        public boolean isWrapperFor(Class<?> iface) { return false; }
    }

    @Test
    @DisplayName("Production environment permits valid Neon cloud PostgreSQL DataSource")
    void postProcessorWithProdEnvironmentPermitsNeonDataSource() {
        MockEnvironment prodEnv = new MockEnvironment();
        prodEnv.setActiveProfiles("prod");
        postProcessor.setEnvironment(prodEnv);

        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl("jdbc:postgresql://ep-round-flower-12345.us-east-2.aws.neon.tech:5432/neondb?sslmode=require");

        assertThatCode(() -> postProcessor.postProcessBeforeInitialization(ds, "dataSource"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Production environment strictly rejects hospital_management DataSource")
    void postProcessorWithProdEnvironmentRejectsHospitalManagement() {
        MockEnvironment prodEnv = new MockEnvironment();
        prodEnv.setActiveProfiles("prod");
        postProcessor.setEnvironment(prodEnv);

        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl("jdbc:postgresql://ep-round-flower-12345.us-east-2.aws.neon.tech:5432/hospital_management");

        assertThatThrownBy(() -> postProcessor.postProcessBeforeInitialization(ds, "dataSource"))
                .isInstanceOf(DatabaseSafetyException.class)
                .hasMessageContaining("hospital_management");
    }

    @Test
    @DisplayName("Production environment strictly rejects local loopback DataSource")
    void postProcessorWithProdEnvironmentRejectsLocalhost() {
        MockEnvironment prodEnv = new MockEnvironment();
        prodEnv.setActiveProfiles("prod");
        postProcessor.setEnvironment(prodEnv);

        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl("jdbc:postgresql://127.0.0.1:5433/hms_dev_safe");

        assertThatThrownBy(() -> postProcessor.postProcessBeforeInitialization(ds, "dataSource"))
                .isInstanceOf(DatabaseSafetyException.class)
                .hasMessageContaining("CRITICAL PRODUCTION SAFETY VIOLATION");
    }

    @Test
    @DisplayName("Production environment strictly rejects Hikari serverName on loopback subnet (127.0.0.2)")
    void postProcessorWithProdEnvironmentRejectsLoopbackSubnetServerName() {
        MockEnvironment prodEnv = new MockEnvironment();
        prodEnv.setActiveProfiles("prod");
        postProcessor.setEnvironment(prodEnv);

        HikariDataSource ds = new HikariDataSource();
        ds.addDataSourceProperty("serverName", "127.0.0.2");
        ds.addDataSourceProperty("portNumber", "5433");
        ds.addDataSourceProperty("databaseName", "hms_dev_safe");

        assertThatThrownBy(() -> postProcessor.postProcessBeforeInitialization(ds, "dataSource"))
                .isInstanceOf(DatabaseSafetyException.class)
                .hasMessageContaining("CRITICAL PRODUCTION SAFETY VIOLATION");
    }

    @Test
    @DisplayName("Production environment rejects dataSourceClassName with loopback URL override property (127.0.0.2)")
    void postProcessorRejectsDataSourceClassNameWithLoopbackUrlOverrideInProduction() {
        MockEnvironment prodEnv = new MockEnvironment();
        prodEnv.setActiveProfiles("prod");
        postProcessor.setEnvironment(prodEnv);

        HikariDataSource ds = new HikariDataSource();
        ds.setDataSourceClassName("org.postgresql.ds.PGSimpleDataSource");
        ds.addDataSourceProperty("serverName", "192.0.2.1");
        ds.addDataSourceProperty("portNumber", "5432");
        ds.addDataSourceProperty("databaseName", "neondb");
        ds.addDataSourceProperty("url", "jdbc:postgresql://127.0.0.2:5433/neondb");

        assertThatThrownBy(() -> postProcessor.postProcessBeforeInitialization(ds, "dataSource"))
                .isInstanceOf(DatabaseSafetyException.class)
                .satisfies(ex -> assertThat(ex.getMessage()).containsAnyOf("CRITICAL PRODUCTION SAFETY VIOLATION", "CRITICAL SAFETY VIOLATION"));
    }

    @Test
    @DisplayName("Local environment rejects dataSourceClassName with hospital_management URL override property")
    void postProcessorRejectsDataSourceClassNameWithHospitalManagementUrlOverrideInLocal() {
        HikariDataSource ds = new HikariDataSource();
        ds.setDataSourceClassName("org.postgresql.ds.PGSimpleDataSource");
        ds.addDataSourceProperty("serverName", "127.0.0.1");
        ds.addDataSourceProperty("portNumber", "5433");
        ds.addDataSourceProperty("databaseName", "hms_dev_test");
        ds.addDataSourceProperty("url", "jdbc:postgresql://127.0.0.1:5433/hospital%5Fmanagement");

        assertThatThrownBy(() -> postProcessor.postProcessBeforeInitialization(ds, "dataSource"))
                .isInstanceOf(DatabaseSafetyException.class)
                .satisfies(ex -> assertThat(ex.getMessage()).containsAnyOf("CRITICAL DATABASE SAFETY VIOLATION", "CRITICAL SAFETY VIOLATION"));
    }

    @Test
    @DisplayName("Hikari with conflicting top-level jdbcUrl and discrete dataSourceProperties is strictly rejected")
    void postProcessorRejectsConflictingJdbcUrlAndDataSourceProperties() {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl("jdbc:postgresql://127.0.0.1:5433/hms_dev_safe");
        ds.addDataSourceProperty("serverName", "127.0.0.1");
        ds.addDataSourceProperty("portNumber", "5433");
        ds.addDataSourceProperty("databaseName", "hms_dev_safe");

        assertThatThrownBy(() -> postProcessor.postProcessBeforeInitialization(ds, "dataSource"))
                .isInstanceOf(DatabaseSafetyException.class)
                .hasMessageContaining("CRITICAL SAFETY VIOLATION");
    }

    @Test
    @DisplayName("dataSourceClassName with safe discrete properties passes validation without opening connection")
    void postProcessorAcceptsSafeDataSourceClassNameDiscreteProperties() {
        HikariDataSource ds = new HikariDataSource();
        ds.setDataSourceClassName("org.postgresql.ds.PGSimpleDataSource");
        ds.addDataSourceProperty("serverName", "127.0.0.1");
        ds.addDataSourceProperty("portNumber", "5433");
        ds.addDataSourceProperty("databaseName", "hms_dev_test");

        assertThatCode(() -> postProcessor.postProcessBeforeInitialization(ds, "dataSource"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Reject duplicate URL aliases (url + URL) in dataSourceProperties")
    void postProcessorRejectsDuplicateUrlAliasesInDataSourceProperties() {
        HikariDataSource ds = new HikariDataSource();
        ds.setDataSourceClassName("org.postgresql.ds.PGSimpleDataSource");
        ds.addDataSourceProperty("url", "jdbc:postgresql://localhost:5433/hms_dev_safe");
        ds.addDataSourceProperty("URL", "jdbc:postgresql://localhost:5433/hospital%5Fmanagement");

        assertThatThrownBy(() -> postProcessor.postProcessBeforeInitialization(ds, "dataSource"))
                .isInstanceOf(DatabaseSafetyException.class)
                .satisfies(ex -> assertThat(ex.getMessage()).containsAnyOf("CRITICAL SAFETY VIOLATION", "CRITICAL DATABASE SAFETY VIOLATION"));
    }

    @Test
    @DisplayName("Reject conflicting singular and plural host setters (serverName + ServerNames) in production")
    void postProcessorRejectsConflictingSingularAndPluralHostSettersInProduction() {
        MockEnvironment prodEnv = new MockEnvironment();
        prodEnv.setActiveProfiles("prod");
        postProcessor.setEnvironment(prodEnv);

        HikariDataSource ds = new HikariDataSource();
        ds.setDataSourceClassName("org.postgresql.ds.PGSimpleDataSource");
        ds.addDataSourceProperty("databaseName", "neondb");
        ds.addDataSourceProperty("portNumber", "5432");
        ds.addDataSourceProperty("serverName", "192.0.2.1");
        ds.addDataSourceProperty("ServerNames", "127.0.0.2");

        assertThatThrownBy(() -> postProcessor.postProcessBeforeInitialization(ds, "dataSource"))
                .isInstanceOf(DatabaseSafetyException.class)
                .satisfies(ex -> assertThat(ex.getMessage()).containsAnyOf("CRITICAL SAFETY VIOLATION", "CRITICAL PRODUCTION SAFETY VIOLATION"));
    }

    @Test
    @DisplayName("Reject plural serverNames targeting loopback in production")
    void postProcessorRejectsPluralServerNamesTargetingLoopbackInProduction() {
        MockEnvironment prodEnv = new MockEnvironment();
        prodEnv.setActiveProfiles("prod");
        postProcessor.setEnvironment(prodEnv);

        HikariDataSource ds = new HikariDataSource();
        ds.setDataSourceClassName("org.postgresql.ds.PGSimpleDataSource");
        ds.addDataSourceProperty("databaseName", "neondb");
        ds.addDataSourceProperty("portNumber", "5432");
        ds.addDataSourceProperty("serverNames", "127.0.0.2");

        assertThatThrownBy(() -> postProcessor.postProcessBeforeInitialization(ds, "dataSource"))
                .isInstanceOf(DatabaseSafetyException.class)
                .hasMessageContaining("CRITICAL PRODUCTION SAFETY VIOLATION");
    }

    @Test
    @DisplayName("Reject multiple hosts in serverNames in local development")
    void postProcessorRejectsMultipleHostsInServerNamesInLocal() {
        HikariDataSource ds = new HikariDataSource();
        ds.setDataSourceClassName("org.postgresql.ds.PGSimpleDataSource");
        ds.addDataSourceProperty("databaseName", "hms_dev_test");
        ds.addDataSourceProperty("portNumber", "5433");
        ds.addDataSourceProperty("serverNames", "127.0.0.1,127.0.0.2");

        assertThatThrownBy(() -> postProcessor.postProcessBeforeInitialization(ds, "dataSource"))
                .isInstanceOf(DatabaseSafetyException.class)
                .hasMessageContaining("Multiple host specifications in serverNames are not permitted in local development");
    }

    @Test
    @DisplayName("Reject duplicate plural host aliases (serverNames + ServerNames) in production")
    void postProcessorRejectsDuplicatePluralHostAliasesInProduction() {
        MockEnvironment prodEnv = new MockEnvironment();
        prodEnv.setActiveProfiles("prod");
        postProcessor.setEnvironment(prodEnv);

        HikariDataSource ds = new HikariDataSource();
        ds.setDataSourceClassName("org.postgresql.ds.PGSimpleDataSource");
        ds.addDataSourceProperty("databaseName", "neondb");
        ds.addDataSourceProperty("portNumber", "5432");
        ds.addDataSourceProperty("serverNames", "192.0.2.1");
        ds.addDataSourceProperty("ServerNames", "127.0.0.2");

        assertThatThrownBy(() -> postProcessor.postProcessBeforeInitialization(ds, "dataSource"))
                .isInstanceOf(DatabaseSafetyException.class)
                .hasMessageContaining("CRITICAL SAFETY VIOLATION");
    }

    @Test
    @DisplayName("Reject empty host entries in serverNames that default to localhost in pgjdbc")
    void postProcessorRejectsEmptyHostEntriesInServerNames() {
        MockEnvironment prodEnv = new MockEnvironment();
        prodEnv.setActiveProfiles("prod");
        postProcessor.setEnvironment(prodEnv);

        HikariDataSource ds = new HikariDataSource();
        ds.setDataSourceClassName("org.postgresql.ds.PGSimpleDataSource");
        ds.addDataSourceProperty("databaseName", "neondb");
        ds.addDataSourceProperty("portNumbers", "5432,5432");
        ds.addDataSourceProperty("serverNames", ",");

        assertThatThrownBy(() -> postProcessor.postProcessBeforeInitialization(ds, "dataSource"))
                .isInstanceOf(DatabaseSafetyException.class)
                .hasMessageContaining("Empty host entry in serverNames is not permitted");
    }

    @Test
    @DisplayName("Reject wildcard serverName (0.0.0.0) in production")
    void postProcessorRejectsWildcardServerNameInProduction() {
        MockEnvironment prodEnv = new MockEnvironment();
        prodEnv.setActiveProfiles("prod");
        postProcessor.setEnvironment(prodEnv);

        HikariDataSource ds = new HikariDataSource();
        ds.setDataSourceClassName("org.postgresql.ds.PGSimpleDataSource");
        ds.addDataSourceProperty("databaseName", "neondb");
        ds.addDataSourceProperty("portNumber", "5432");
        ds.addDataSourceProperty("serverName", "0.0.0.0");

        assertThatThrownBy(() -> postProcessor.postProcessBeforeInitialization(ds, "dataSource"))
                .isInstanceOf(DatabaseSafetyException.class)
                .hasMessageContaining("CRITICAL PRODUCTION SAFETY VIOLATION");
    }

    @Test
    @DisplayName("Reject discrete serverName attempting URL path and query injection")
    void postProcessorRejectsServerNameWithUrlPathInjection() {
        MockEnvironment prodEnv = new MockEnvironment();
        prodEnv.setActiveProfiles("prod");
        postProcessor.setEnvironment(prodEnv);

        HikariDataSource ds = new HikariDataSource();
        ds.setDataSourceClassName("org.postgresql.ds.PGSimpleDataSource");
        ds.addDataSourceProperty("databaseName", "neondb");
        ds.addDataSourceProperty("portNumber", "5432");
        ds.addDataSourceProperty("serverName", "127.0.0.1:5433/hospital%5Fmanagement?x=");

        assertThatThrownBy(() -> postProcessor.postProcessBeforeInitialization(ds, "dataSource"))
                .isInstanceOf(DatabaseSafetyException.class)
                .satisfies(ex -> assertThat(ex.getMessage()).containsAnyOf("CRITICAL SAFETY VIOLATION", "CRITICAL DATABASE SAFETY VIOLATION"));
    }

    @Test
    @DisplayName("Reject discrete serverNames with backslash escapes")
    void postProcessorRejectsServerNamesWithBackslashEscape() {
        MockEnvironment prodEnv = new MockEnvironment();
        prodEnv.setActiveProfiles("prod");
        postProcessor.setEnvironment(prodEnv);

        HikariDataSource ds = new HikariDataSource();
        ds.setDataSourceClassName("org.postgresql.ds.PGSimpleDataSource");
        ds.addDataSourceProperty("databaseName", "neondb");
        ds.addDataSourceProperty("portNumber", "5432");
        ds.addDataSourceProperty("serverNames", "1\\27.0.0.1");

        assertThatThrownBy(() -> postProcessor.postProcessBeforeInitialization(ds, "dataSource"))
                .isInstanceOf(DatabaseSafetyException.class)
                .hasMessageContaining("CRITICAL SAFETY VIOLATION");
    }

    @Test
    @DisplayName("Reject singular serverName with comma and plural portNumbers in production")
    void postProcessorRejectsSingularServerNameWithPluralPortNumbersAndLoopbackDestination() {
        MockEnvironment prodEnv = new MockEnvironment();
        prodEnv.setActiveProfiles("prod");
        postProcessor.setEnvironment(prodEnv);

        HikariDataSource ds = new HikariDataSource();
        ds.setDataSourceClassName("org.postgresql.ds.PGSimpleDataSource");
        ds.addDataSourceProperty("databaseName", "neondb");
        ds.addDataSourceProperty("serverName", "127.0.0.1,cloud.invalid");
        ds.addDataSourceProperty("portNumbers", "5432");

        assertThatThrownBy(() -> postProcessor.postProcessBeforeInitialization(ds, "dataSource"))
                .isInstanceOf(DatabaseSafetyException.class)
                .satisfies(ex -> assertThat(ex.getMessage()).containsAnyOf("CRITICAL SAFETY VIOLATION", "CRITICAL PRODUCTION SAFETY VIOLATION"));
    }

    private static DataSource createDummyDataSource(String url) {
        return (DataSource) Proxy.newProxyInstance(
                DatabaseSafetyBeanPostProcessorTest.class.getClassLoader(),
                new Class<?>[]{DataSource.class},
                (proxy, method, args) -> {
                    if ("getUrl".equals(method.getName()) || "getJdbcUrl".equals(method.getName())) {
                        return url;
                    }
                    if ("toString".equals(method.getName())) {
                        return "DummyDataSource@" + Integer.toHexString(System.identityHashCode(proxy));
                    }
                    if ("unwrap".equals(method.getName()) || "isWrapperFor".equals(method.getName())) {
                        return null;
                    }
                    return null;
                }
        );
    }
}
