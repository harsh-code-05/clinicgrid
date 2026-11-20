package com.harsh.hospitalmanagement.config.safety;

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Role;

/**
 * Auto-configuration that provides DatabaseSafetyBeanPostProcessor as an infrastructure bean
 * across Spring Boot contexts if not already registered by DatabaseSafetyApplicationContextInitializer.
 */
@AutoConfiguration
public class DatabaseSafetyAutoConfiguration {

    @Bean(name = DatabaseSafetyApplicationContextInitializer.BEAN_NAME)
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    @ConditionalOnMissingBean(DatabaseSafetyBeanPostProcessor.class)
    public static DatabaseSafetyBeanPostProcessor databaseSafetyBeanPostProcessor() {
        return new DatabaseSafetyBeanPostProcessor();
    }
}
