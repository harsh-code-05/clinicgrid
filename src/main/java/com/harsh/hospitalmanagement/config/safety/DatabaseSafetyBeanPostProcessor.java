package com.harsh.hospitalmanagement.config.safety;

import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.PriorityOrdered;
import org.springframework.core.env.Environment;
import org.springframework.orm.jpa.JpaVendorAdapter;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * PriorityOrdered BeanPostProcessor that intercepts DataSource and EntityManagerFactory beans
 * both before and after initialization.
 * <p>
 * Runs at HIGHEST_PRECEDENCE + 10 to ensure Spring Boot's ConfigurationPropertiesBindingPostProcessor
 * (which runs at HIGHEST_PRECEDENCE + 1) has fully bound all spring.datasource.hikari.* properties
 * before inspection, guaranteeing no hidden datasource, driver class, or unvalidated connection destination
 * can bypass safety gates.
 */
public class DatabaseSafetyBeanPostProcessor implements BeanPostProcessor, PriorityOrdered, EnvironmentAware {

    private static final Logger log = LoggerFactory.getLogger(DatabaseSafetyBeanPostProcessor.class);
    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    private boolean isProduction() {
        if (environment != null) {
            if (environment.getActiveProfiles() != null) {
                for (String profile : environment.getActiveProfiles()) {
                    if ("prod".equalsIgnoreCase(profile) || "production".equalsIgnoreCase(profile)) {
                        return true;
                    }
                }
            }
            if ("production".equalsIgnoreCase(environment.getProperty("hms.database.safety.mode"))) {
                return true;
            }
        }
        return false;
    }

    // Run after ConfigurationPropertiesBindingPostProcessor (HIGHEST_PRECEDENCE + 1)
    // so all @ConfigurationProperties on DataSource beans are fully bound before inspection.
    public static final int ORDER = PriorityOrdered.HIGHEST_PRECEDENCE + 10;

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
        if (bean instanceof DataSource dataSource) {
            validateDataSourceBean(dataSource, beanName);
        } else if (bean instanceof LocalContainerEntityManagerFactoryBean emf) {
            validateEntityManagerFactoryBean(emf, beanName);
        }
        return bean;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (bean instanceof DataSource dataSource) {
            validateDataSourceBean(dataSource, beanName);
        } else if (bean instanceof LocalContainerEntityManagerFactoryBean emf) {
            validateEntityManagerFactoryBean(emf, beanName);
        }
        return bean;
    }

    private void validateDataSourceBean(DataSource dataSource, String beanName) {
        validateDataSourceBean(dataSource, beanName, new IdentityHashMap<>());
    }

    private void validateDataSourceBean(DataSource dataSource, String beanName, Map<DataSource, Boolean> visited) {
        if (dataSource == null) {
            return;
        }
        if (visited.containsKey(dataSource)) {
            throw new DatabaseSafetyException(
                    "CRITICAL DATABASE SAFETY VIOLATION: Circular DataSource reference detected for bean '" + beanName
                            + "' of type " + dataSource.getClass().getName() + ". Refusing circular DataSource to prevent bypass."
            );
        }
        visited.put(dataSource, Boolean.TRUE);

        if (dataSource instanceof HikariDataSource hikari) {
            validateHikariDataSource(hikari, beanName, visited);
        } else {
            validateGenericDataSource(dataSource, beanName, visited);
        }
    }

    private void validateHikariDataSource(HikariDataSource hikari, String beanName, Map<DataSource, Boolean> visited) {
        Properties dsProps = hikari.getDataSourceProperties();

        // 1. Inspect all Hikari dataSourceProperties for any reference to forbidden production database
        if (dsProps != null) {
            for (String propName : dsProps.stringPropertyNames()) {
                String val = dsProps.getProperty(propName);
                if (val != null && val.toLowerCase(Locale.ROOT).contains(DatabaseSafetyValidator.FORBIDDEN_DATABASE_NAME)) {
                    throw new DatabaseSafetyException(
                            "CRITICAL DATABASE SAFETY VIOLATION: HikariDataSource property '" + propName
                                    + "' targets forbidden database '" + DatabaseSafetyValidator.FORBIDDEN_DATABASE_NAME
                                    + "'. Bean: " + beanName
                    );
                }
            }
        }

        // 2. Check for underlying DataSource instance (Hikari prioritizes dataSource over jdbcUrl)
        DataSource underlyingDs = hikari.getDataSource();
        if (underlyingDs != null) {
            log.debug("DatabaseSafetyBeanPostProcessor: HikariDataSource '{}' has explicit underlying DataSource of type {}",
                    beanName, underlyingDs.getClass().getName());
            validateDataSourceBean(underlyingDs, beanName + ".underlyingDataSource", visited);
            return;
        }

        // 3. Check for dataSourceClassName configuration (Hikari prioritizes dataSourceClassName over jdbcUrl)
        String dsClassName = hikari.getDataSourceClassName();
        if (dsClassName != null && !dsClassName.trim().isEmpty()) {
            validateHikariDataSourceClassProperties(hikari, beanName, dsClassName, dsProps);
            return;
        }

        // 4. Validate jdbcUrl
        String jdbcUrl = hikari.getJdbcUrl();
        if (jdbcUrl != null && !jdbcUrl.trim().isEmpty()) {
            log.debug("DatabaseSafetyBeanPostProcessor: intercepting HikariDataSource '{}' with URL '{}'",
                    beanName, DatabaseSafetyValidator.sanitizeUrl(jdbcUrl));
            DatabaseSafetyValidator.validateDataSourceUrl(jdbcUrl, isProduction());

            // If dataSourceProperties are also present, ensure they do not specify conflicting destination
            if (dsProps != null && !dsProps.isEmpty()) {
                checkForConflictingProperties(dsProps, beanName);
                List<String> propUrls = extractAllUrlsFromProperties(dsProps);
                for (String u : propUrls) {
                    DatabaseSafetyValidator.validateDataSourceUrl(u, isProduction());
                }
                boolean hasDiscrete = hasDiscreteProperties(dsProps);
                if (hasDiscrete || !propUrls.isEmpty()) {
                    throw new DatabaseSafetyException(
                            "CRITICAL SAFETY VIOLATION: Ambiguous/conflicting DataSource destination in bean '" + beanName
                                    + "': both Hikari jdbcUrl and dataSourceProperties destination properties are specified. "
                                    + "Refusing connection to prevent property override bypass."
                    );
                }
            }
            return;
        }

        // 5. If jdbcUrl is not directly on HikariConfig, check if destination was supplied via dataSourceProperties
        if (dsProps != null) {
            checkForConflictingProperties(dsProps, beanName);
            List<String> urlProps = extractAllUrlsFromProperties(dsProps);
            boolean hasDiscrete = hasDiscreteProperties(dsProps);

            for (String u : urlProps) {
                DatabaseSafetyValidator.validateDataSourceUrl(u, isProduction());
            }

            if (hasDiscrete && !urlProps.isEmpty()) {
                throw new DatabaseSafetyException(
                        "CRITICAL SAFETY VIOLATION: Ambiguous/conflicting DataSource destination in bean '" + beanName
                                + "': both discrete connection properties (databaseName/serverName/portNumber) and 'url' are specified in dataSourceProperties. "
                                + "Refusing connection to prevent property override bypass."
                );
            }

            if (!urlProps.isEmpty()) {
                return;
            }

            if (hasDiscrete) {
                validateDiscreteProperties(beanName, dsProps, isProduction());
                return;
            }
        }

        // 6. Fail closed: no destination could be proven safe
        throw new DatabaseSafetyException(
                "HikariDataSource bean '" + beanName + "' has neither a valid jdbcUrl, underlying dataSource, nor proven safe dataSourceProperties. Fail closed."
        );
    }

    private void validateHikariDataSourceClassProperties(HikariDataSource hikari, String beanName, String dsClassName, Properties dsProps) {
        if (dsProps == null || dsProps.isEmpty()) {
            throw new DatabaseSafetyException(
                    "HikariDataSource bean '" + beanName + "' specifies dataSourceClassName '" + dsClassName
                            + "' but has empty dataSourceProperties. Destination cannot be verified. Fail closed."
            );
        }

        checkForConflictingProperties(dsProps, beanName);

        List<String> urlProps = extractAllUrlsFromProperties(dsProps);
        boolean hasDiscrete = hasDiscreteProperties(dsProps);

        // Validate all urls in dataSourceProperties if present (prevent override bypass via pgjdbc setUrl/setURL)
        for (String u : urlProps) {
            DatabaseSafetyValidator.validateDataSourceUrl(u, isProduction());
        }

        // Reject ambiguous/conflicting destination configuration in dataSourceProperties
        if (hasDiscrete && !urlProps.isEmpty()) {
            throw new DatabaseSafetyException(
                    "CRITICAL SAFETY VIOLATION: Ambiguous/conflicting DataSource destination in bean '" + beanName
                            + "' with dataSourceClassName '" + dsClassName
                            + "': both discrete connection properties (databaseName/serverName/portNumber) and 'url' in dataSourceProperties are specified. "
                            + "Refusing connection to prevent property override bypass."
            );
        }

        if (hasDiscrete) {
            validateDiscreteProperties(beanName, dsProps, isProduction());
        } else if (urlProps.isEmpty()) {
            throw new DatabaseSafetyException(
                    "HikariDataSource bean '" + beanName + "' specifies dataSourceClassName '" + dsClassName
                            + "' but has neither proven safe discrete properties nor a valid connection URL. Fail closed."
            );
        }
    }

    private void checkForConflictingProperties(Properties dsProps, String beanName) {
        if (dsProps == null) {
            return;
        }
        List<String> urlKeys = new ArrayList<>();
        List<String> databaseKeys = new ArrayList<>();
        List<String> singularHostKeys = new ArrayList<>();
        List<String> pluralHostKeys = new ArrayList<>();
        List<String> singularPortKeys = new ArrayList<>();
        List<String> pluralPortKeys = new ArrayList<>();

        for (String key : dsProps.stringPropertyNames()) {
            String lower = key.toLowerCase(Locale.ROOT);
            if (lower.equals("url") || lower.equals("jdbcurl") || lower.equals("connectionurl")) {
                urlKeys.add(key);
            }
            if (lower.equals("databasename") || lower.equals("pgdbname") || lower.equals("database")) {
                databaseKeys.add(key);
            }
            if (lower.equals("servername") || lower.equals("pghost") || lower.equals("host")) {
                singularHostKeys.add(key);
            }
            if (lower.equals("servernames")) {
                pluralHostKeys.add(key);
            }
            if (lower.equals("portnumber") || lower.equals("pgport") || lower.equals("port")) {
                singularPortKeys.add(key);
            }
            if (lower.equals("portnumbers")) {
                pluralPortKeys.add(key);
            }
        }

        if (urlKeys.size() > 1) {
            throw new DatabaseSafetyException(
                    "CRITICAL SAFETY VIOLATION: Multiple URL properties specified in dataSourceProperties: "
                            + urlKeys + ". Only one unambiguous URL property is permitted. Bean: " + beanName
            );
        }

        if (databaseKeys.size() > 1) {
            throw new DatabaseSafetyException(
                    "CRITICAL SAFETY VIOLATION: Multiple databaseName properties specified in dataSourceProperties: "
                            + databaseKeys + ". Only one unambiguous databaseName property is permitted. Bean: " + beanName
            );
        }

        if (singularHostKeys.size() > 1) {
            throw new DatabaseSafetyException(
                    "CRITICAL SAFETY VIOLATION: Multiple singular serverName properties specified in dataSourceProperties: "
                            + singularHostKeys + ". Only one unambiguous serverName property is permitted. Bean: " + beanName
            );
        }

        if (pluralHostKeys.size() > 1) {
            throw new DatabaseSafetyException(
                    "CRITICAL SAFETY VIOLATION: Multiple plural serverNames properties specified in dataSourceProperties: "
                            + pluralHostKeys + ". Only one unambiguous serverNames property is permitted. Bean: " + beanName
            );
        }

        if (!singularHostKeys.isEmpty() && !pluralHostKeys.isEmpty()) {
            throw new DatabaseSafetyException(
                    "CRITICAL SAFETY VIOLATION: Ambiguous/conflicting host configuration in bean '" + beanName
                            + "': both singular serverName (" + singularHostKeys + ") and plural serverNames ("
                            + pluralHostKeys + ") properties are specified in dataSourceProperties."
            );
        }

        if (singularPortKeys.size() > 1) {
            throw new DatabaseSafetyException(
                    "CRITICAL SAFETY VIOLATION: Multiple singular portNumber properties specified in dataSourceProperties: "
                            + singularPortKeys + ". Only one unambiguous portNumber property is permitted. Bean: " + beanName
            );
        }

        if (pluralPortKeys.size() > 1) {
            throw new DatabaseSafetyException(
                    "CRITICAL SAFETY VIOLATION: Multiple plural portNumbers properties specified in dataSourceProperties: "
                            + pluralPortKeys + ". Only one unambiguous portNumbers property is permitted. Bean: " + beanName
            );
        }

        if (!singularPortKeys.isEmpty() && !pluralPortKeys.isEmpty()) {
            throw new DatabaseSafetyException(
                    "CRITICAL SAFETY VIOLATION: Ambiguous/conflicting port configuration in bean '" + beanName
                            + "': both singular portNumber (" + singularPortKeys + ") and plural portNumbers ("
                            + pluralPortKeys + ") properties are specified in dataSourceProperties."
            );
        }
    }

    private String getPropertyIgnoreCase(Properties props, String... targetKeys) {
        if (props == null || targetKeys == null) {
            return null;
        }
        for (String target : targetKeys) {
            for (String key : props.stringPropertyNames()) {
                if (key.equalsIgnoreCase(target)) {
                    String val = props.getProperty(key);
                    if (val != null && !val.trim().isEmpty()) {
                        return val.trim();
                    }
                }
            }
        }
        return null;
    }

    private List<String> extractAllUrlsFromProperties(Properties props) {
        if (props == null) {
            return Collections.emptyList();
        }
        List<String> urls = new ArrayList<>();
        for (String key : props.stringPropertyNames()) {
            String lower = key.toLowerCase(Locale.ROOT);
            if (lower.equals("url") || lower.equals("jdbcurl") || lower.equals("connectionurl")) {
                String val = props.getProperty(key);
                if (val != null && !val.trim().isEmpty()) {
                    urls.add(val.trim());
                }
            }
        }
        return urls;
    }

    private boolean hasDiscreteProperties(Properties props) {
        if (props == null) {
            return false;
        }
        for (String key : props.stringPropertyNames()) {
            String lower = key.toLowerCase(Locale.ROOT);
            if (lower.equals("databasename") || lower.equals("pgdbname") || lower.equals("database")
                    || lower.equals("servername") || lower.equals("pghost") || lower.equals("host") || lower.equals("servernames")
                    || lower.equals("portnumber") || lower.equals("pgport") || lower.equals("port") || lower.equals("portnumbers")) {
                String val = props.getProperty(key);
                if (val != null && !val.trim().isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }

    private void validateDiscreteProperties(String beanName, Properties dsProps, boolean isProduction) {
        String dbName = getPropertyIgnoreCase(dsProps, "databaseName", "PGDBNAME", "database");
        if (dbName == null || dbName.trim().isEmpty()) {
            throw new DatabaseSafetyException(
                    "HikariDataSource bean '" + beanName + "' is missing databaseName/PGDBNAME property. Fail closed."
            );
        }
        if (dbName.equalsIgnoreCase(DatabaseSafetyValidator.FORBIDDEN_DATABASE_NAME) || dbName.toLowerCase(Locale.ROOT).contains(DatabaseSafetyValidator.FORBIDDEN_DATABASE_NAME)) {
            throw new DatabaseSafetyException(
                    "CRITICAL SAFETY VIOLATION: HikariDataSource databaseName targets production database '"
                            + DatabaseSafetyValidator.FORBIDDEN_DATABASE_NAME + "'. Bean: " + beanName
            );
        }
        if (!isProduction && !DatabaseSafetyValidator.ALLOWED_DB_PATTERN.matcher(dbName.trim()).matches()) {
            throw new DatabaseSafetyException(
                    "HikariDataSource databaseName '" + dbName + "' violates safety constraints. Bean: " + beanName
            );
        }

        // Host validation (singular or plural)
        String serverName = getPropertyIgnoreCase(dsProps, "serverName", "PGHOST", "host");
        String serverNames = getPropertyIgnoreCase(dsProps, "serverNames");

        if ((serverName == null || serverName.trim().isEmpty()) && (serverNames == null || serverNames.trim().isEmpty())) {
            throw new DatabaseSafetyException(
                    "HikariDataSource bean '" + beanName + "' is missing serverName/PGHOST/serverNames property. Fail closed."
            );
        }

        List<String> validatedHosts = new ArrayList<>();
        if (serverName != null && !serverName.trim().isEmpty()) {
            validateSingleHost(beanName, serverName.trim(), isProduction);
            validatedHosts.add(serverName.trim());
        }
        if (serverNames != null && !serverNames.trim().isEmpty()) {
            String[] hosts = serverNames.split(",", -1);
            if (hosts.length == 0) {
                throw new DatabaseSafetyException("HikariDataSource serverNames property is empty or invalid. Bean: " + beanName);
            }
            if (!isProduction && hosts.length > 1) {
                throw new DatabaseSafetyException(
                        "Multiple host specifications in serverNames are not permitted in local development: "
                                + DatabaseSafetyValidator.sanitizeUrl(serverNames)
                );
            }
            for (String h : hosts) {
                if (h.trim().isEmpty()) {
                    throw new DatabaseSafetyException(
                            "CRITICAL SAFETY VIOLATION: Empty host entry in serverNames is not permitted as pgjdbc defaults empty hosts to localhost. Value: '"
                                    + serverNames + "'. Bean: " + beanName
                    );
                }
                validateSingleHost(beanName, h.trim(), isProduction);
                validatedHosts.add(h.trim());
            }
        }

        // Port validation (singular or plural)
        String portStr = getPropertyIgnoreCase(dsProps, "portNumber", "PGPORT", "port");
        String portNumbers = getPropertyIgnoreCase(dsProps, "portNumbers");

        if ((portStr == null || portStr.trim().isEmpty()) && (portNumbers == null || portNumbers.trim().isEmpty())) {
            throw new DatabaseSafetyException(
                    "HikariDataSource bean '" + beanName + "' is missing portNumber/PGPORT/portNumbers property. Fail closed."
            );
        }

        List<String> validatedPorts = new ArrayList<>();
        if (portStr != null && !portStr.trim().isEmpty()) {
            validateSinglePort(beanName, portStr.trim(), isProduction);
            validatedPorts.add(portStr.trim());
        }
        if (portNumbers != null && !portNumbers.trim().isEmpty()) {
            String[] ports = portNumbers.split(",", -1);
            for (String p : ports) {
                if (p.trim().isEmpty()) {
                    throw new DatabaseSafetyException(
                            "CRITICAL SAFETY VIOLATION: Empty port entry in portNumbers is not permitted. Value: '"
                                    + portNumbers + "'. Bean: " + beanName
                    );
                }
                validateSinglePort(beanName, p.trim(), isProduction);
                validatedPorts.add(p.trim());
            }
        }

        // Validate effective constructed URLs across all host and port combinations
        String effectivePort = validatedPorts.isEmpty() ? "5432" : validatedPorts.get(0);
        for (String h : validatedHosts) {
            String effectiveUrl = "jdbc:postgresql://" + h + ":" + effectivePort + "/" + dbName.trim();
            DatabaseSafetyValidator.validateDataSourceUrl(effectiveUrl, isProduction);
        }
        String compositeUrl = "jdbc:postgresql://" + String.join(",", validatedHosts) + ":" + effectivePort + "/" + dbName.trim();
        DatabaseSafetyValidator.validateDataSourceUrl(compositeUrl, isProduction);
    }

    private void validateSingleHost(String beanName, String host, boolean isProduction) {
        if (host == null || host.trim().isEmpty()) {
            throw new DatabaseSafetyException("HikariDataSource server host cannot be empty. Bean: " + beanName);
        }
        String cleanHost = host.trim();
        // Reject unsafe characters and injection attempts in host:
        // Slashes (/ or \), query/fragment (? or #), credentials (@ or %), commas (,), and unbracketed colons (:)
        if (cleanHost.contains("/") || cleanHost.contains("\\") || cleanHost.contains("?")
                || cleanHost.contains("#") || cleanHost.contains("@") || cleanHost.contains("%")
                || cleanHost.contains(",")) {
            throw new DatabaseSafetyException(
                    "CRITICAL SAFETY VIOLATION: Invalid or unsafe characters detected in server host '"
                            + DatabaseSafetyValidator.sanitizeUrl(cleanHost)
                            + "'. Path traversal, query delimiters, escapes, commas, and userInfo injection are strictly forbidden. Bean: " + beanName
            );
        }
        // If colon is present, only permit bracketed IPv6 (e.g. [::1])
        if (cleanHost.contains(":")) {
            if (!cleanHost.startsWith("[") || !cleanHost.endsWith("]")) {
                throw new DatabaseSafetyException(
                        "CRITICAL SAFETY VIOLATION: Unbracketed colon detected in server host '"
                                + DatabaseSafetyValidator.sanitizeUrl(cleanHost)
                                + "'. Host must not specify port or path components in serverName property. Bean: " + beanName
                );
            }
        }

        if (!isProduction) {
            if (!DatabaseSafetyValidator.ALLOWED_PG_HOSTS.contains(cleanHost.toLowerCase(Locale.ROOT))) {
                throw new DatabaseSafetyException(
                        "HikariDataSource server host '" + DatabaseSafetyValidator.sanitizeUrl(cleanHost) + "' is not permitted. Bean: " + beanName
                );
            }
        } else {
            if (DatabaseSafetyValidator.isLoopbackHost(cleanHost)) {
                throw new DatabaseSafetyException(
                        "CRITICAL PRODUCTION SAFETY VIOLATION: HikariDataSource server host cannot target local/loopback host '"
                                + DatabaseSafetyValidator.sanitizeUrl(cleanHost) + "' under production profile. Bean: " + beanName
                );
            }
        }
    }

    private void validateSinglePort(String beanName, String portStr, boolean isProduction) {
        try {
            int port = Integer.parseInt(portStr.trim());
            if (!isProduction && port != DatabaseSafetyValidator.REQUIRED_PG_PORT) {
                throw new DatabaseSafetyException(
                        "HikariDataSource port " + port + " is not permitted. Approved local port is "
                                + DatabaseSafetyValidator.REQUIRED_PG_PORT + ". Bean: " + beanName
                );
            }
        } catch (NumberFormatException e) {
            throw new DatabaseSafetyException("Invalid port in HikariDataSource: " + portStr, e);
        }
    }

    private void validateGenericDataSource(DataSource dataSource, String beanName, Map<DataSource, Boolean> visited) {
        // 1. If it's a wrapper, proxy, or embedded DataSource, unwrap the target and validate recursively
        DataSource unwrapped = unwrapDataSource(dataSource);
        if (unwrapped != null && unwrapped != dataSource) {
            validateDataSourceBean(unwrapped, beanName + ".target", visited);
            return;
        }

        // Statically resolve URL without opening a connection
        String resolvedUrl = resolveUrlStatically(dataSource);

        if (resolvedUrl != null) {
            log.debug("DatabaseSafetyBeanPostProcessor: intercepting generic DataSource '{}' with URL '{}'",
                    beanName, DatabaseSafetyValidator.sanitizeUrl(resolvedUrl));
            DatabaseSafetyValidator.validateDataSourceUrl(resolvedUrl, isProduction());
        } else {
            // Fail closed: reject unresolvable datasource targets; no class-name heuristics permitted
            throw new DatabaseSafetyException(
                    "DataSource bean '" + beanName + "' of type " + dataSource.getClass().getName()
                            + " could not be statically validated. Refusing unverified DataSource connection to protect database."
            );
        }
    }

    private DataSource unwrapDataSource(DataSource dataSource) {
        if (dataSource == null) {
            return null;
        }

        // Check DelegatingDataSource.getTargetDataSource()
        try {
            Method getTarget = dataSource.getClass().getMethod("getTargetDataSource");
            Object res = getTarget.invoke(dataSource);
            if (res instanceof DataSource ds && ds != dataSource) {
                return ds;
            }
        } catch (ReflectiveOperationException ignored) {
        }

        // Check EmbeddedDataSourceProxy 'dataSource' field across class hierarchy
        Class<?> curr = dataSource.getClass();
        while (curr != null && curr != Object.class) {
            try {
                Field field = curr.getDeclaredField("dataSource");
                field.setAccessible(true);
                Object res = field.get(dataSource);
                if (res instanceof DataSource ds && ds != dataSource) {
                    return ds;
                }
            } catch (NoSuchFieldException ignored) {
            } catch (Exception ignored) {
                break;
            }
            curr = curr.getSuperclass();
        }

        // Check standard JDBC unwrap
        try {
            if (dataSource.isWrapperFor(DataSource.class)) {
                DataSource unwrapped = dataSource.unwrap(DataSource.class);
                if (unwrapped != null && unwrapped != dataSource) {
                    return unwrapped;
                }
            }
        } catch (Exception ignored) {
        }

        return null;
    }

    private String resolveUrlStatically(DataSource dataSource) {
        if (dataSource == null) {
            return null;
        }
        for (String methodName : new String[]{"getUrl", "getJdbcUrl"}) {
            try {
                Method m = dataSource.getClass().getMethod(methodName);
                Object res = m.invoke(dataSource);
                if (res instanceof String s && !s.trim().isEmpty()) {
                    return s;
                }
            } catch (ReflectiveOperationException ignored) {
            }
        }
        return null;
    }

    private String resolveUrlRecursively(DataSource ds, Map<DataSource, Boolean> visited) {
        if (ds == null || visited.containsKey(ds)) {
            return null;
        }
        visited.put(ds, Boolean.TRUE);

        // 1. HikariDataSource: strictly follow HikariCP runtime precedence
        if (ds instanceof HikariDataSource hikari) {
            // A. Explicit underlying DataSource takes absolute precedence in HikariCP
            DataSource underlying = hikari.getDataSource();
            if (underlying != null) {
                return resolveUrlRecursively(underlying, visited);
            }

            // B. Explicit dataSourceClassName takes precedence over jdbcUrl in HikariCP
            String dsClassName = hikari.getDataSourceClassName();
            if (dsClassName != null && !dsClassName.trim().isEmpty()) {
                if (hikari.getDataSourceProperties() != null) {
                    String urlProp = hikari.getDataSourceProperties().getProperty("url");
                    if (urlProp != null && !urlProp.trim().isEmpty()) {
                        return urlProp;
                    }
                }
                return null;
            }

            // C. Direct jdbcUrl configuration
            String jdbcUrl = hikari.getJdbcUrl();
            if (jdbcUrl != null && !jdbcUrl.trim().isEmpty()) {
                return jdbcUrl;
            }

            // D. Fallback url property in dataSourceProperties
            if (hikari.getDataSourceProperties() != null) {
                String urlProp = hikari.getDataSourceProperties().getProperty("url");
                if (urlProp != null && !urlProp.trim().isEmpty()) {
                    return urlProp;
                }
            }
            return null;
        }

        // 2. Generic DataSource: if it wraps an underlying target, the target's destination strictly governs
        DataSource unwrapped = unwrapDataSource(ds);
        if (unwrapped != null && unwrapped != ds) {
            return resolveUrlRecursively(unwrapped, visited);
        }

        // 3. Static reflection check (getUrl / getJdbcUrl) only for non-wrapper DataSources
        return resolveUrlStatically(ds);
    }

    private void validateEntityManagerFactoryBean(LocalContainerEntityManagerFactoryBean emf, String beanName) {
        DataSource ds = emf.getDataSource();
        boolean isH2Mem = false;
        if (ds != null) {
            validateDataSourceBean(ds, beanName + ".dataSource", new IdentityHashMap<>());
            String resolvedUrl = resolveUrlRecursively(ds, new IdentityHashMap<>());
            if (resolvedUrl != null && resolvedUrl.startsWith("jdbc:h2:mem:")) {
                isH2Mem = true;
            }
        }

        // Validate vendor adapter configuration
        JpaVendorAdapter vendorAdapter = emf.getJpaVendorAdapter();
        if (vendorAdapter instanceof HibernateJpaVendorAdapter hibernateVendorAdapter) {
            boolean isGenerateDdl = inspectGenerateDdl(hibernateVendorAdapter);
            if (isGenerateDdl && !isH2Mem) {
                throw new DatabaseSafetyException(
                        "CRITICAL SCHEMA SAFETY VIOLATION: HibernateJpaVendorAdapter.isGenerateDdl() is true for PostgreSQL connection. "
                                + "This causes Hibernate to set hibernate.hbm2ddl.auto=update, modifying tables. Only 'validate' or 'none' is permitted."
                );
            }

            Map<String, ?> vendorProps = hibernateVendorAdapter.getJpaPropertyMap();
            if (vendorProps != null) {
                DatabaseSafetyValidator.validateJpaProperties(vendorProps, isH2Mem);
            }
        }

        // Validate all schema generation and DDL properties in the JPA property map
        DatabaseSafetyValidator.validateJpaProperties(emf.getJpaPropertyMap(), isH2Mem);
    }

    static boolean inspectGenerateDdl(HibernateJpaVendorAdapter adapter) {
        if (adapter == null) {
            return false;
        }
        try {
            Method isGenMethod = findMethod(adapter.getClass(), "isGenerateDdl");
            if (isGenMethod == null) {
                throw new DatabaseSafetyException(
                        "Unable to reflectively locate isGenerateDdl() on " + adapter.getClass().getName()
                                + ". Cannot verify schema safety. Fail closed."
                );
            }
            isGenMethod.setAccessible(true);
            Object res = isGenMethod.invoke(adapter);
            if (res instanceof Boolean b) {
                return b;
            }
            throw new DatabaseSafetyException(
                    "Unexpected return type from isGenerateDdl() on " + adapter.getClass().getName()
                            + ": " + (res == null ? "null" : res.getClass().getName())
            );
        } catch (DatabaseSafetyException dse) {
            throw dse;
        } catch (Exception ex) {
            throw new DatabaseSafetyException(
                    "Failed to inspect HibernateJpaVendorAdapter.isGenerateDdl(): " + ex.getMessage(), ex
            );
        }
    }

    private static Method findMethod(Class<?> clazz, String name, Class<?>... parameterTypes) {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            try {
                return current.getDeclaredMethod(name, parameterTypes);
            } catch (NoSuchMethodException e) {
                current = current.getSuperclass();
            }
        }
        return null;
    }
}
