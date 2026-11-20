package com.harsh.hospitalmanagement.config.safety;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Tag("postgres-disposable")
@EnabledIfEnvironmentVariable(named = PostgresTestTarget.ENABLED, matches = "true")
public @interface RequiresDisposablePostgres {
}
